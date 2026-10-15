package com.studina.dinaapi.learning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Rebuilds the per-resource aggregate in {@code user_resource_progress} by replaying the
 * attempt history of a single flashcard, quiz question or true/false statement.
 *
 * <p>All resource types share this code path so that a topic's mastery is always the average
 * of aggregates computed exactly once per resource, whatever the resource is.
 */
@Service
public class ResourceProgressService {

    public static final String FLASHCARD = "FLASHCARD";
    public static final String QUIZ_QUESTION = "QUIZ_QUESTION";
    public static final String TRUE_FALSE = "TRUE_FALSE";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final LearningProgressPolicy policy;

    @Value("${directus.url}")
    private String directusUrl;

    public ResourceProgressService(LearningProgressPolicy policy) {
        this.policy = policy;
    }

    public Map<String, Object> recalculate(
            String token,
            String userId,
            String subjectId,
            String resourceType,
            String resourceId) {

        List<Map<String, Object>> attempts = attemptHistory(token, userId, resourceType, resourceId);
        if (attempts.isEmpty()) {
            throw new IllegalStateException("No se pudo reconstruir el historial del recurso");
        }

        Double mastery = null;
        String lastSeenAt = null;
        for (Map<String, Object> attempt : attempts) {
            mastery = policy.advanceMastery(mastery, scoreOf(resourceType, attempt));
            lastSeenAt = String.valueOf(attempt.get("created_at"));
        }

        Map<String, Object> progress = Map.of(
                "user_id", userId,
                "subject_id", subjectId,
                "resource_type", resourceType,
                "resource_id", resourceId,
                "mastery_score", mastery,
                "times_seen", attempts.size(),
                "last_seen_at", lastSeenAt);

        Map<String, Object> current = findOne(token, "user_resource_progress", Map.of(
                "user_id", userId,
                "resource_type", resourceType,
                "resource_id", resourceId));
        return upsert(token, "user_resource_progress", current, progress);
    }

    private double scoreOf(String resourceType, Map<String, Object> attempt) {
        // Cuestionarios y verdadero o falso son respuestas booleanas; sólo las tarjetas tienen rating.
        if (!FLASHCARD.equals(resourceType)) {
            return policy.scoreOf(Boolean.TRUE.equals(attempt.get("is_correct")));
        }
        Object rating = attempt.get("rating");
        if (!(rating instanceof Number value)) {
            throw new IllegalStateException("El intento no tiene una calificación válida");
        }
        return policy.ratingScore(value.intValue());
    }

    /** Attempts of one resource, oldest first, with ties on {@code created_at} broken by id. */
    private List<Map<String, Object>> attemptHistory(
            String token, String userId, String resourceType, String resourceId) {

        String collection;
        String idField;
        String fields;
        switch (resourceType) {
            case QUIZ_QUESTION -> {
                collection = "quiz_attempts";
                idField = "question_id";
                fields = "id,created_at,is_correct";
            }
            case TRUE_FALSE -> {
                collection = "true_false_attempts";
                idField = "statement_id";
                fields = "id,created_at,is_correct";
            }
            default -> {
                collection = "flashcard_attempts";
                idField = "card_id";
                fields = "id,created_at,rating";
            }
        }

        Map<String, Object> response = http.get()
                .uri(UriComponentsBuilder.fromUriString(directusUrl + "/items/" + collection)
                        .queryParam("filter[user_id][_eq]", userId)
                        .queryParam("filter[" + idField + "][_eq]", resourceId)
                        .queryParam("fields", fields)
                        .queryParam("sort", "created_at")
                        .queryParam("limit", "-1")
                        .build().encode().toUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().body(MAP_TYPE);

        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException("Directus devolvió un historial inválido: " + collection);
        }
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> values) {
                @SuppressWarnings("unchecked")
                Map<String, Object> attempt = (Map<String, Object>) values;
                attempts.add(attempt);
            }
        }
        attempts.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("created_at")))
                .thenComparing(row -> String.valueOf(row.get("id"))));
        return attempts;
    }

    private Map<String, Object> findOne(String token, String collection, Map<String, String> filters) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(directusUrl + "/items/" + collection)
                .queryParam("limit", "1");
        filters.forEach((key, value) -> builder.queryParam("filter[" + key + "][_eq]", value));
        Map<String, Object> response = http.get()
                .uri(builder.build().encode().toUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows) || rows.isEmpty()
                || !(rows.getFirst() instanceof Map<?, ?> row)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) row;
        return result;
    }

    private Map<String, Object> upsert(
            String token, String collection, Map<String, Object> existing, Map<String, Object> values) {
        if (existing == null) {
            Map<String, Object> response = http.post().uri(directusUrl + "/items/" + collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .body(values).retrieve().body(MAP_TYPE);
            return dataMap(response);
        }
        Map<String, Object> response = http.patch()
                .uri(directusUrl + "/items/" + collection + "/" + existing.get("id"))
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
}
