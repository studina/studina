package com.studina.dinaapi.learning;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.studina.dinaapi.ai.rag.OpenAnswerEvaluator;
import com.studina.dinaapi.ai.rag.OpenQuestion;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Registra las respuestas de preguntas abiertas. La corrige la IA y el score sale del nivel que
 * eligió ({@link LearningProgressPolicy#scoreOfVerdict}); a partir de ahí es el mismo EMA que
 * el resto de los recursos.
 *
 * <p>Reintentar con el mismo {@code attempt_id} devuelve la corrección guardada sin volver a
 * llamar a la IA: un corte de red no cuesta otra corrección ni puede cambiar la nota.
 * Reescribir la respuesta es un intento nuevo, con otro {@code attempt_id}.
 */
@Service
public class OpenQuestionProgressService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
    private final ResourceProgressService resourceProgressService;
    private final LearningAnalyticsService analyticsService;
    private final OpenAnswerEvaluator evaluator;

    @Value("${directus.url}")
    private String directusUrl;

    public OpenQuestionProgressService(
            DirectusAuthorizationService authorizationService,
            ResourceProgressService resourceProgressService,
            LearningAnalyticsService analyticsService,
            OpenAnswerEvaluator evaluator) {
        this.authorizationService = authorizationService;
        this.resourceProgressService = resourceProgressService;
        this.analyticsService = analyticsService;
        this.evaluator = evaluator;
    }

    public Map<String, Object> recordAnswer(
            String userId,
            String subjectId,
            String setId,
            OpenQuestion question,
            OpenQuestionAttemptRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("La respuesta es obligatoria");
        }
        String answer = OpenAnswerEvaluator.requireValidAnswer(request.answer());
        if (request.attemptId() == null || request.attemptId().isBlank()) {
            throw new IllegalArgumentException("El identificador del intento es obligatorio");
        }
        try {
            UUID.fromString(request.attemptId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("El identificador del intento debe ser un UUID");
        }
        String token = authorizationService.getAdminToken();
        Map<String, Object> previous = findOne(token, Map.of(
                "user_id", userId,
                "attempt_id", request.attemptId()));
        if (previous != null) {
            if (!setId.equals(String.valueOf(previous.get("set_id")))
                    || !question.id().equals(String.valueOf(previous.get("question_id")))
                    || !answer.equals(previous.get("answer"))) {
                throw new IllegalArgumentException("El identificador del intento ya fue usado para otra respuesta");
            }
        } else {
            OpenAnswerEvaluator.Evaluation evaluation = evaluator.evaluate(question, answer, userId);
            Map<String, Object> attempt = new HashMap<>();
            attempt.put("user_id", userId);
            attempt.put("subject_id", subjectId);
            attempt.put("set_id", setId);
            attempt.put("question_id", question.id());
            attempt.put("attempt_id", request.attemptId());
            attempt.put("answer", answer);
            attempt.put("verdict", evaluation.verdict());
            attempt.put("score", evaluation.score());
            attempt.put("is_correct",
                    evaluation.score() >= LearningProgressPolicy.OPEN_ANSWER_CORRECT_THRESHOLD);
            attempt.put("feedback", evaluation.feedback());
            attempt.put("corrections", evaluation.corrections());
            attempt.put("missing_points", evaluation.missingPoints());
            attempt.put("created_at", Instant.now().toString());
            previous = create(token, attempt);
        }

        resourceProgressService.recalculate(
                token, userId, subjectId, ResourceProgressService.OPEN_QUESTION, question.id());
        analyticsService.recalculateTopicProgress(userId, subjectId, question.topicId());
        return previous;
    }

    private Map<String, Object> findOne(String token, Map<String, String> filters) {
        StringBuilder uri = new StringBuilder(directusUrl + "/items/open_question_attempts?limit=1");
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
        Map<String, Object> response = http.post().uri(directusUrl + "/items/open_question_attempts")
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
