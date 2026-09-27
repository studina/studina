package com.studina.dinaapi.learning;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.studina.dinaapi.ai.rag.QuizQuestion;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class QuizProgressService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
    private final StudySessionService sessionService;
    private final LearningAnalyticsService analyticsService;

    @Value("${directus.url}")
    private String directusUrl;

    public QuizProgressService(
            DirectusAuthorizationService authorizationService,
            StudySessionService sessionService,
            LearningAnalyticsService analyticsService) {
        this.authorizationService = authorizationService;
        this.sessionService = sessionService;
        this.analyticsService = analyticsService;
    }

    public Map<String, Object> recordAnswer(
            String userId,
            String subjectId,
            String quizId,
            QuizQuestion question,
            QuizAttemptRequest request) {
        if (request == null || request.selectedOptionId() == null || request.selectedOptionId().isBlank()) {
            throw new IllegalArgumentException("La opción seleccionada es obligatoria");
        }
        if (question.options() == null || question.options().stream()
                .noneMatch(option -> request.selectedOptionId().equals(option.id()))) {
            throw new IllegalArgumentException("La opción no pertenece a esta pregunta");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("La clave de idempotencia es obligatoria");
        }
        try {
            UUID.fromString(request.idempotencyKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("La clave de idempotencia debe ser un UUID");
        }
        boolean correct = question.correctOptionId().equals(request.selectedOptionId());
        String token = authorizationService.getAdminToken();
        Map<String, Object> previous = findOne(token, "quiz_attempts", Map.of(
                "user_id", userId,
                "idempotency_key", request.idempotencyKey()));
        if (previous != null) {
            if (!quizId.equals(String.valueOf(previous.get("quiz_id")))
                    || !question.id().equals(String.valueOf(previous.get("question_id")))
                    || !request.selectedOptionId().equals(String.valueOf(previous.get("selected_option_id")))) {
                throw new IllegalArgumentException("La clave de idempotencia ya fue usada para otra respuesta");
            }
        } else if (request.sessionId() != null && !request.sessionId().isBlank()) {
            sessionService.requireActiveQuiz(userId, subjectId, quizId, request.sessionId());
        }

        Instant createdAt = previous != null && previous.get("created_at") != null
                ? Instant.parse(String.valueOf(previous.get("created_at")))
                : Instant.now();
        if (previous == null) {
            Map<String, Object> attempt = new HashMap<>(Map.of(
                    "user_id", userId,
                    "subject_id", subjectId,
                    "quiz_id", quizId,
                    "question_id", question.id(),
                    "idempotency_key", request.idempotencyKey(),
                    "selected_option_id", request.selectedOptionId(),
                    "is_correct", correct,
                    "rating", correct ? 4 : 0,
                    "created_at", createdAt.toString()));
            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                attempt.put("session_id", request.sessionId());
            }
            create(token, "quiz_attempts", attempt);
        } else if (Boolean.TRUE.equals(previous.get("is_correct")) != correct) {
            throw new IllegalArgumentException("La respuesta no coincide con el intento original");
        }

        for (String topicId : question.topicIds().stream().distinct().toList()) {
            if (findOne(token, "learning_evidence", Map.of(
                    "user_id", userId,
                    "topic_id", topicId,
                    "resource_id", question.id(),
                    "idempotency_key", request.idempotencyKey())) != null) {
                continue;
            }
            Map<String, Object> evidence = new HashMap<>(Map.of(
                    "user_id", userId,
                    "subject_id", subjectId,
                    "topic_id", topicId,
                    "resource_type", "QUIZ",
                    "resource_id", question.id(),
                    "rating", correct ? 4 : 0,
                    "created_at", createdAt.toString(),
                    "idempotency_key", request.idempotencyKey()));
            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                evidence.put("session_id", request.sessionId());
            }
            create(token, "learning_evidence", evidence);
        }
        analyticsService.recalculateTopicProgress(userId, subjectId);
        return previous == null ? findOne(token, "quiz_attempts", Map.of(
                "user_id", userId,
                "idempotency_key", request.idempotencyKey())) : previous;
    }

    private Map<String, Object> findOne(String token, String collection, Map<String, String> filters) {
        StringBuilder uri = new StringBuilder(directusUrl + "/items/" + collection + "?limit=1");
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

    private Map<String, Object> create(String token, String collection, Map<String, Object> values) {
        Map<String, Object> response = http.post().uri(directusUrl + "/items/" + collection)
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
