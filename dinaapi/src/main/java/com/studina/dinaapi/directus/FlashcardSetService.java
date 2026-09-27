package com.studina.dinaapi.directus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
public class FlashcardSetService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    /** Columns of {@code flashcard_cards} that rebuild a {@link Flashcard}. */
    private static final String CARD_FIELDS = "id,flashcard_set_id,term,definition,topic_id,position";

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    public FlashcardSetService(DirectusAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    public Map<String, Object> create(
            String subjectId,
            String title,
            String instructions,
            String difficulty,
            List<String> sourceFileIds,
            List<Flashcard> cards) {

        String adminToken = authorizationService.getAdminToken();
        Map<String, Object> response = http.post()
                .uri(directusUrl + "/items/flashcards")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + adminToken)
                .body(Map.of(
                        "subject", subjectId,
                        "title", resolveTitle(subjectId, title),
                        "instructions", instructions == null ? "" : instructions,
                        "difficulty", difficulty == null ? "basic" : difficulty,
                        "source_file_ids", sourceFileIds,
                        "date_created", Instant.now().toString()
                ))
                .retrieve()
                .body(MAP_TYPE);

        Map<String, Object> savedSet = new LinkedHashMap<>(dataMap(response));
        String setId = String.valueOf(savedSet.get("id"));
        for (int position = 0; position < cards.size(); position++) {
            Flashcard card = cards.get(position);
            http.post()
                    .uri(directusUrl + "/items/flashcard_cards")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .body(Map.of(
                            "id", card.id(),
                            "flashcard_set_id", setId,
                            "term", card.term(),
                            "definition", card.definition(),
                            "topic_id", card.topicId(),
                            "position", position
                    ))
                    .retrieve()
                    .toBodilessEntity();
        }
        savedSet.put("cards", cards);
        return savedSet;
    }

    private String resolveTitle(String subjectId, String title) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        return "Flashcards #" + (listSetRows(subjectId, "id").size() + 1);
    }

    public List<Map<String, Object>> listForSubject(String subjectId) {
        List<Map<String, Object>> sets = listSetRows(subjectId,
                "id,title,instructions,difficulty,source_file_ids,date_created");
        Map<String, List<Flashcard>> cardsBySet = loadCards(
                sets.stream().map(set -> String.valueOf(set.get("id"))).toList());

        return sets.stream().map(set -> {
            Map<String, Object> result = new LinkedHashMap<>(set);
            result.put("cards", cardsBySet.getOrDefault(String.valueOf(set.get("id")), List.of()));
            return result;
        }).toList();
    }

    private List<Map<String, Object>> listSetRows(String subjectId, String fields) {
        Map<String, Object> response = http.get()
                .uri(UriComponentsBuilder.fromUriString(directusUrl + "/items/flashcards")
                        .queryParam("filter[subject][_eq]", subjectId)
                        .queryParam("fields", fields)
                        .queryParam("sort", "-date_created")
                        .queryParam("limit", "-1")
                        .build().encode().toUri())
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .body(MAP_TYPE);

        Object data = response != null ? response.get("data") : null;
        if (!(data instanceof List<?>)) {
            throw new IllegalStateException("Directus devolvió una lista de flashcards inválida");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flashcardSets = (List<Map<String, Object>>) data;
        return flashcardSets;
    }

    /**
     * Rebuilds the cards of each set from {@code flashcard_cards}, which is the only place
     * they are stored. Returned as {@link Flashcard} records so the JSON the frontend reads
     * keeps its shape instead of exposing column names.
     */
    public Map<String, List<Flashcard>> loadCards(Collection<String> flashcardSetIds) {
        if (flashcardSetIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> response = http.get()
                .uri(UriComponentsBuilder.fromUriString(directusUrl + "/items/flashcard_cards")
                        .queryParam("filter[flashcard_set_id][_in]", String.join(",", flashcardSetIds))
                        .queryParam("fields", CARD_FIELDS)
                        .queryParam("sort", "position")
                        .queryParam("limit", "-1")
                        .build().encode().toUri())
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .body(MAP_TYPE);

        Object data = response != null ? response.get("data") : null;
        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException("Directus devolvió una lista de tarjetas inválida");
        }

        Map<String, List<Flashcard>> cardsBySet = new LinkedHashMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> values)) {
                continue;
            }
            cardsBySet.computeIfAbsent(relationId(values.get("flashcard_set_id")), ignored -> new ArrayList<>())
                    .add(toFlashcard(values));
        }
        return cardsBySet;
    }

    private Flashcard toFlashcard(Map<?, ?> row) {
        return new Flashcard(
                String.valueOf(row.get("id")),
                String.valueOf(row.get("term")),
                String.valueOf(row.get("definition")),
                relationId(row.get("topic_id")));
    }

    private String relationId(Object value) {
        if (value instanceof Map<?, ?> relation) {
            return String.valueOf(relation.get("id"));
        }
        return value == null ? null : String.valueOf(value);
    }

    public void requireSetOwnership(String flashcardSetId, String subjectId) {
        Map<String, Object> existing = getById(flashcardSetId);
        if (!subjectId.equals(String.valueOf(existing.get("subject")))) {
            throw new IllegalArgumentException("El set no pertenece a esta materia");
        }
    }

    public Map<String, Object> rename(String flashcardSetId, String subjectId, String title) {
        Map<String, Object> existing = getById(flashcardSetId);
        if (!subjectId.equals(String.valueOf(existing.get("subject")))) {
            throw new IllegalArgumentException("No tenés permiso para editar este set de flashcards");
        }

        Map<String, Object> response = http.patch()
                .uri(directusUrl + "/items/flashcards/" + flashcardSetId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .body(Map.of("title", title))
                .retrieve()
                .body(MAP_TYPE);

        return dataMap(response);
    }

    private Map<String, Object> getById(String flashcardSetId) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/items/flashcards/" + flashcardSetId + "?fields=id,subject")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .body(MAP_TYPE);

        return dataMap(response);
    }

    public Flashcard findCard(String flashcardSetId, String subjectId, String cardId) {
        requireSetOwnershipForCard(flashcardSetId, subjectId);

        Map<String, Object> response = http.get()
                .uri(UriComponentsBuilder.fromUriString(directusUrl + "/items/flashcard_cards")
                        .queryParam("filter[id][_eq]", cardId)
                        .queryParam("filter[flashcard_set_id][_eq]", flashcardSetId)
                        .queryParam("fields", CARD_FIELDS)
                        .queryParam("limit", "1")
                        .build().encode().toUri())
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .body(MAP_TYPE);

        Object data = response != null ? response.get("data") : null;
        if (!(data instanceof List<?> rows) || rows.isEmpty()
                || !(rows.get(0) instanceof Map<?, ?> row)) {
            throw new IllegalArgumentException("La tarjeta no pertenece a este set");
        }
        return toFlashcard(row);
    }

    private void requireSetOwnershipForCard(String flashcardSetId, String subjectId) {
        Map<String, Object> existing = getById(flashcardSetId);
        if (!subjectId.equals(String.valueOf(existing.get("subject")))) {
            throw new IllegalArgumentException("La tarjeta no pertenece a esta materia");
        }
    }

    public void delete(String flashcardSetId, String subjectId) {
        Map<String, Object> existing = getById(flashcardSetId);
        if (!subjectId.equals(String.valueOf(existing.get("subject")))) {
            throw new IllegalArgumentException("No tenés permiso para eliminar este set de flashcards");
        }

        http.delete()
                .uri(directusUrl + "/items/flashcards/" + flashcardSetId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> dataMap(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map<?, ?>)) {
            throw new IllegalStateException("Directus devolvió una respuesta inválida");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return data;
    }
}
