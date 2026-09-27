package com.studina.dinaapi.learning;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.studina.dinaapi.ai.rag.Flashcard;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class FlashcardProgressService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
    private final LearningProgressPolicy policy;
    private final StudySessionService studySessionService;
    private final LearningAnalyticsService analyticsService;

    @Value("${directus.url}")
    private String directusUrl;

    public FlashcardProgressService(
            DirectusAuthorizationService authorizationService,
            LearningProgressPolicy policy,
            StudySessionService studySessionService,
            LearningAnalyticsService analyticsService) {
        this.authorizationService = authorizationService;
        this.policy = policy;
        this.studySessionService = studySessionService;
        this.analyticsService = analyticsService;
    }

    public Map<String, Object> recordAttempt(
            String userId,
            String subjectId,
            String flashcardSetId,
            Flashcard card,
            FlashcardAttemptRequest request) {
        validateAttempt(request);
        String token = authorizationService.getAdminToken();
        Map<String, Object> previousAttempt = null;
        try {
            UUID.fromString(request.idempotencyKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("La clave de idempotencia debe ser un UUID");
        }
        previousAttempt = findOne(token, "flashcard_attempts", Map.of(
                "user_id", userId,
                "idempotency_key", request.idempotencyKey()
        ));
        if (previousAttempt != null && !card.id().equals(String.valueOf(previousAttempt.get("card_id")))) {
            throw new IllegalArgumentException("La clave de idempotencia ya fue usada por otra tarjeta");
        }
        if (previousAttempt != null
                && !java.util.Objects.equals(
                        request.sessionId(),
                        previousAttempt.get("session_id") == null
                                ? null : String.valueOf(previousAttempt.get("session_id")))) {
            throw new IllegalArgumentException("La clave de idempotencia ya fue usada en otra sesión");
        }
        if (previousAttempt == null && request.sessionId() != null && !request.sessionId().isBlank()) {
            studySessionService.requireActive(userId, subjectId, flashcardSetId, request.sessionId());
        }
        if (previousAttempt != null
                && (!subjectId.equals(String.valueOf(previousAttempt.get("subject_id")))
                || !flashcardSetId.equals(String.valueOf(previousAttempt.get("flashcard_set_id"))))) {
            throw new IllegalArgumentException("La clave de idempotencia ya fue usada en otro contexto");
        }
        Instant now = previousAttempt != null && previousAttempt.get("created_at") != null
                ? Instant.parse(String.valueOf(previousAttempt.get("created_at")))
                : Instant.now();

        if (previousAttempt == null) {
            Map<String, Object> attempt = new java.util.HashMap<>();
            attempt.put("user_id", userId);
            attempt.put("subject_id", subjectId);
            attempt.put("flashcard_set_id", flashcardSetId);
            attempt.put("card_id", card.id());
            attempt.put("rating", request.rating());
            attempt.put("created_at", now.toString());
            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                attempt.put("session_id", request.sessionId());
            }
            attempt.put("idempotency_key", request.idempotencyKey());
            create(token, "flashcard_attempts", attempt);
        } else if (integer(previousAttempt, "rating", -1) != request.rating()) {
            throw new IllegalArgumentException("El reintento no coincide con la calificación original");
        }

        Map<String, Object> storedProgress = recalculateCardProgress(token, userId, card.id());

        for (String topicId : card.topicIds()) {
                Map<String, Object> existingEvidence = findOne(token, "learning_evidence", Map.of(
                    "user_id", userId,
                    "topic_id", topicId,
                    "idempotency_key", request.idempotencyKey()
                ));
            if (existingEvidence != null) {
                continue;
            }
            Map<String, Object> evidence = new java.util.HashMap<>(Map.of(
                    "user_id", userId,
                    "subject_id", subjectId,
                    "topic_id", topicId,
                    "resource_type", "FLASHCARD",
                    "resource_id", card.id(),
                    "rating", request.rating(),
                    "created_at", now.toString()
            ));
            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                evidence.put("session_id", request.sessionId());
            }
            evidence.put("idempotency_key", request.idempotencyKey());
            create(token, "learning_evidence", evidence);
        }
        analyticsService.recalculateTopicProgress(userId, subjectId);
        return storedProgress;
    }

        private Map<String, Object> recalculateCardProgress(String token, String userId, String cardId) {
        String uri = directusUrl + "/items/flashcard_attempts?filter[user_id][_eq]=" + userId
            + "&filter[card_id][_eq]=" + cardId
            + "&fields=id,rating,created_at,idempotency_key&sort=created_at&limit=-1";
        Map<String, Object> response = http.get().uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows) || rows.isEmpty()) {
            throw new IllegalStateException("No se pudo reconstruir el historial de la tarjeta");
        }
        List<Map<String, Object>> attempts = new java.util.ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> values) {
            @SuppressWarnings("unchecked")
            Map<String, Object> attempt = (Map<String, Object>) values;
            attempts.add(attempt);
            }
        }
        attempts.sort(java.util.Comparator
            .comparing((Map<String, Object> row) -> String.valueOf(row.get("created_at")))
            .thenComparing(row -> String.valueOf(row.get("id"))));
        Double mastery = null;
        int lastRating = -1;
        String lastSeenAt = null;
        int timesSeen = 0;
        for (Map<String, Object> attempt : attempts) {
            int rating = integer(attempt, "rating", -1);
            mastery = policy.updatedMastery(mastery, rating);
            lastRating = rating;
            lastSeenAt = String.valueOf(attempt.get("created_at"));
            timesSeen++;
        }
        Map<String, Object> current = findOne(token, "user_flashcard_progress", Map.of(
            "user_id", userId,
            "card_id", cardId
        ));
        Map<String, Object> progress = Map.of(
            "user_id", userId,
            "card_id", cardId,
            "mastery_score", mastery,
            "times_seen", timesSeen,
                "last_rating", lastRating,
            "last_seen_at", lastSeenAt
        );
        return upsert(token, "user_flashcard_progress", current, progress);
        }

    public Map<String, Object> findCardProgress(String userId, String cardId) {
        return findOne(authorizationService.getAdminToken(), "user_flashcard_progress", Map.of(
                "user_id", userId,
                "card_id", cardId
        ));
    }

    private void validateAttempt(FlashcardAttemptRequest request) {
        if (request == null || request.cardId() == null || request.cardId().isBlank()) {
            throw new IllegalArgumentException("La tarjeta es obligatoria");
        }
        if (request.rating() == null) {
            throw new IllegalArgumentException("La autoevaluación es obligatoria");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("La clave de idempotencia es obligatoria");
        }
        policy.ratingScore(request.rating());
    }

    private Map<String, Object> findOne(
            String token,
            String collection,
            Map<String, String> filters) {
        StringBuilder uri = new StringBuilder(directusUrl + "/items/" + collection + "?limit=1");
        filters.forEach((key, value) -> uri.append("&filter[").append(key)
                .append("][_eq]=").append(value));
        Map<String, Object> response = http.get().uri(uri.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().body(MAP_TYPE);
        if (response == null || !(response.get("data") instanceof List<?> rows) || rows.isEmpty()) {
            return null;
        }
        Object first = rows.getFirst();
        if (!(first instanceof Map<?, ?> row)) {
            throw new IllegalStateException("Directus devolvió progreso inválido");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) row;
        return result;
    }

    private Map<String, Object> upsert(
            String token,
            String collection,
            Map<String, Object> existing,
            Map<String, Object> values) {
        if (existing == null) {
            return create(token, collection, values);
        }
        Map<String, Object> response = http.patch()
                .uri(directusUrl + "/items/" + collection + "/" + existing.get("id"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(values).retrieve().body(MAP_TYPE);
        return dataMap(response);
    }

    private Map<String, Object> create(String token, String collection, Map<String, Object> values) {
        Map<String, Object> response = http.post().uri(directusUrl + "/items/" + collection)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(values).retrieve().body(MAP_TYPE);
        return dataMap(response);
    }

    private Map<String, Object> dataMap(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("Directus devolvió una respuesta inválida");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) data;
        return result;
    }

    private int integer(Map<?, ?> values, String key, int defaultValue) {
        if (values == null || !(values.get(key) instanceof Number value)) {
            return defaultValue;
        }
        return value.intValue();
    }
}