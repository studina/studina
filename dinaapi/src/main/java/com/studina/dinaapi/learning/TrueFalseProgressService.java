package com.studina.dinaapi.learning;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.studina.dinaapi.ai.rag.TrueFalseStatement;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Registra las respuestas de verdadero o falso. Igual que una respuesta de cuestionario, es un
 * hecho booleano: {@code is_correct} es la fuente y el score sale de ahí para el mismo EMA.
 */
@Service
public class TrueFalseProgressService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
    private final ResourceProgressService resourceProgressService;
    private final LearningAnalyticsService analyticsService;

    @Value("${directus.url}")
    private String directusUrl;

    public TrueFalseProgressService(
            DirectusAuthorizationService authorizationService,
            ResourceProgressService resourceProgressService,
            LearningAnalyticsService analyticsService) {
        this.authorizationService = authorizationService;
        this.resourceProgressService = resourceProgressService;
        this.analyticsService = analyticsService;
    }

    public Map<String, Object> recordAnswer(
            String userId,
            String subjectId,
            String setId,
            TrueFalseStatement statement,
            TrueFalseAttemptRequest request) {
        if (request == null || request.answer() == null) {
            throw new IllegalArgumentException("La respuesta es obligatoria");
        }
        if (request.attemptId() == null || request.attemptId().isBlank()) {
            throw new IllegalArgumentException("El identificador del intento es obligatorio");
        }
        try {
            UUID.fromString(request.attemptId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("El identificador del intento debe ser un UUID");
        }
        boolean correct = request.answer().equals(statement.isTrue());
        String token = authorizationService.getAdminToken();
        Map<String, Object> previous = findOne(token, Map.of(
                "user_id", userId,
                "attempt_id", request.attemptId()));
        if (previous != null) {
            if (!setId.equals(String.valueOf(previous.get("set_id")))
                    || !statement.id().equals(String.valueOf(previous.get("statement_id")))
                    || !request.answer().equals(previous.get("answer"))) {
                throw new IllegalArgumentException("El identificador del intento ya fue usado para otra respuesta");
            }
        }

        if (previous == null) {
            Map<String, Object> attempt = new HashMap<>(Map.of(
                    "user_id", userId,
                    "subject_id", subjectId,
                    "set_id", setId,
                    "statement_id", statement.id(),
                    "attempt_id", request.attemptId(),
                    "answer", request.answer(),
                    "is_correct", correct,
                    "created_at", Instant.now().toString()));
            create(token, attempt);
        } else if (Boolean.TRUE.equals(previous.get("is_correct")) != correct) {
            throw new IllegalArgumentException("La respuesta no coincide con el intento original");
        }

        resourceProgressService.recalculate(
                token, userId, subjectId, ResourceProgressService.TRUE_FALSE, statement.id());
        analyticsService.recalculateTopicProgress(userId, subjectId, statement.topicId());
        return previous == null ? findOne(token, Map.of(
                "user_id", userId,
                "attempt_id", request.attemptId())) : previous;
    }

    private Map<String, Object> findOne(String token, Map<String, String> filters) {
        StringBuilder uri = new StringBuilder(directusUrl + "/items/true_false_attempts?limit=1");
        filters.forEach((key, value) -> uri.append("&filter[").append(key).append("][_eq]=").append(value));
        Map<String, Object> response = http.get().uri(uri.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows) || rows.isEmpty() || !(rows.getFirst() instanceof Map<?, ?> row)) {
            return null;
        }
        return castMap(row);
    }

    private Map<String, Object> create(String token, Map<String, Object> values) {
        Map<String, Object> response = http.post().uri(directusUrl + "/items/true_false_attempts")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(values).retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof Map<?, ?> row)) {
            throw new IllegalStateException("Directus devolvió una respuesta inválida");
        }
        return castMap(row);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> row) {
        return (Map<String, Object>) row;
    }
}
