package com.studina.dinaapi.learning;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.studina.dinaapi.ai.rag.Flashcard;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class FlashcardProgressService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
    private final LearningProgressPolicy policy;
    private final ResourceProgressService resourceProgressService;
    private final LearningAnalyticsService analyticsService;

    @Value("${directus.url}")
    private String directusUrl;

    public FlashcardProgressService(
            DirectusAuthorizationService authorizationService,
            LearningProgressPolicy policy,
            ResourceProgressService resourceProgressService,
            LearningAnalyticsService analyticsService) {
        this.authorizationService = authorizationService;
        this.policy = policy;
        this.resourceProgressService = resourceProgressService;
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
        try {
            UUID.fromString(request.attemptId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("El identificador del intento debe ser un UUID");
        }
        Map<String, Object> previousAttempt = findOne(token, "flashcard_attempts", Map.of(
                "user_id", userId,
                "attempt_id", request.attemptId()
        ));
        if (previousAttempt != null && !card.id().equals(String.valueOf(previousAttempt.get("card_id")))) {
            throw new IllegalArgumentException("El identificador del intento ya fue usado por otra tarjeta");
        }
        if (previousAttempt != null
                && (!subjectId.equals(String.valueOf(previousAttempt.get("subject_id")))
                || !flashcardSetId.equals(String.valueOf(previousAttempt.get("flashcard_set_id"))))) {
            throw new IllegalArgumentException("El identificador del intento ya fue usado en otro contexto");
        }

        if (previousAttempt == null) {
            Map<String, Object> attempt = new HashMap<>();
            attempt.put("user_id", userId);
            attempt.put("subject_id", subjectId);
            attempt.put("flashcard_set_id", flashcardSetId);
            attempt.put("card_id", card.id());
            attempt.put("rating", request.rating());
            attempt.put("created_at", Instant.now().toString());
            attempt.put("attempt_id", request.attemptId());
            create(token, "flashcard_attempts", attempt);
        } else if (integer(previousAttempt, "rating", -1) != request.rating()) {
            // Un POST reenviado es un reintento de red y tiene que traer lo mismo. Cambiar la
            // nota a propósito es otra operación: PATCH /attempts/{attemptId} (updateAttempt).
            throw new IllegalArgumentException("El reintento no coincide con la calificación original");
        }

        Map<String, Object> storedProgress = resourceProgressService.recalculate(
                token, userId, subjectId, ResourceProgressService.FLASHCARD, card.id());
        analyticsService.recalculateTopicProgress(userId, subjectId);
        return storedProgress;
    }

    /**
     * Corrige la calificación de un intento existente, editando la fila en su lugar.
     *
     * <p>No agrega un intento nuevo a propósito: el mastery se reconstruye reproduciendo todo el
     * historial, así que una segunda calificación aplicaría el EMA dos veces y un misclick
     * quedaría pesando para siempre. Editar es barato porque {@code recalculate} ya rehace el
     * agregado desde cero en cada intento.
     */
    public Map<String, Object> updateAttempt(
            String userId,
            String subjectId,
            String flashcardSetId,
            Flashcard card,
            String attemptId,
            Integer rating) {
        if (rating == null) {
            throw new IllegalArgumentException("La autoevaluación es obligatoria");
        }
        policy.ratingScore(rating);
        try {
            UUID.fromString(attemptId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("El identificador del intento debe ser un UUID");
        }

        String token = authorizationService.getAdminToken();
        Map<String, Object> attempt = findOne(token, "flashcard_attempts", Map.of(
                "user_id", userId,
                "attempt_id", attemptId
        ));
        if (attempt == null) {
            throw new AttemptNotFoundException("No existe un intento con ese identificador");
        }
        if (!card.id().equals(String.valueOf(attempt.get("card_id")))
                || !subjectId.equals(String.valueOf(attempt.get("subject_id")))
                || !flashcardSetId.equals(String.valueOf(attempt.get("flashcard_set_id")))) {
            throw new IllegalArgumentException("El intento no corresponde a esta tarjeta");
        }

        // Sólo el rating. created_at no se toca: el replay ordena por esa fecha, así que
        // moverla reordenaría el historial y cambiaría el mastery por un motivo ajeno.
        http.patch()
                .uri(directusUrl + "/items/flashcard_attempts/" + attempt.get("id"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(Map.of("rating", rating))
                .retrieve()
                .toBodilessEntity();

        Map<String, Object> storedProgress = resourceProgressService.recalculate(
                token, userId, subjectId, ResourceProgressService.FLASHCARD, card.id());
        analyticsService.recalculateTopicProgress(userId, subjectId);
        return storedProgress;
    }

    private void validateAttempt(FlashcardAttemptRequest request) {
        if (request == null || request.cardId() == null || request.cardId().isBlank()) {
            throw new IllegalArgumentException("La tarjeta es obligatoria");
        }
        if (request.rating() == null) {
            throw new IllegalArgumentException("La autoevaluación es obligatoria");
        }
        if (request.attemptId() == null || request.attemptId().isBlank()) {
            throw new IllegalArgumentException("El identificador del intento es obligatorio");
        }
        policy.ratingScore(request.rating());
    }

    private Map<String, Object> findOne(
            String token,
            String collection,
            Map<String, String> filters) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(directusUrl + "/items/" + collection)
                .queryParam("limit", "1");
        filters.forEach((key, value) -> builder.queryParam("filter[" + key + "][_eq]", value));
        Map<String, Object> response = http.get()
                .uri(builder.build().encode().toUri())
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

    private Map<String, Object> create(String token, String collection, Map<String, Object> values) {
        Map<String, Object> response = http.post().uri(directusUrl + "/items/" + collection)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(values).retrieve().body(MAP_TYPE);
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
