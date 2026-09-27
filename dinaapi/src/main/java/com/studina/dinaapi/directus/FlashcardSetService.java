package com.studina.dinaapi.directus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.studina.dinaapi.ai.rag.Flashcard;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class FlashcardSetService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
        private final ObjectMapper objectMapper = new ObjectMapper();

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
                        "cards", cards,
                        "instructions", instructions == null ? "" : instructions,
                        "difficulty", difficulty == null ? "basic" : difficulty,
                        "source_file_ids", sourceFileIds,
                        "date_created", Instant.now().toString()
                ))
                .retrieve()
                .body(MAP_TYPE);

        Map<String, Object> savedSet = dataMap(response);
        String setId = String.valueOf(savedSet.get("id"));
        for (int position = 0; position < cards.size(); position++) {
            Flashcard card = cards.get(position);
            Map<String, Object> cardFields = new java.util.HashMap<>();
            cardFields.put("id", card.id());
            cardFields.put("flashcard_set_id", setId);
            cardFields.put("card_id", card.id());
            cardFields.put("term", card.term());
            cardFields.put("definition", card.definition());
            cardFields.put("topic_ids", card.topicIds());
            cardFields.put("position", position);
            if (card.primaryTopicId() != null && !card.primaryTopicId().isBlank()) {
                                cardFields.put("primary_topic_id", card.primaryTopicId());
            }
            http.post()
                    .uri(directusUrl + "/items/flashcard_cards")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .body(cardFields)
                    .retrieve()
                    .toBodilessEntity();
            for (String topicId : card.topicIds().stream().distinct().toList()) {
                http.post()
                        .uri(directusUrl + "/items/flashcard_card_topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .body(Map.of(
                                "flashcard_set_id", setId,
                                "card_id", card.id(),
                                "topic_id", topicId
                        ))
                        .retrieve()
                        .toBodilessEntity();
            }
        }
        return savedSet;
    }

        private String resolveTitle(String subjectId, String title) {
                if (title != null && !title.isBlank()) {
                        return title.trim();
                }
                return "Flashcards #" + (listForSubject(subjectId).size() + 1);
        }

    public List<Map<String, Object>> listForSubject(String subjectId) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/items/flashcards?filter[subject][_eq]="
                        + subjectId
                        + "&fields=id,title,cards,instructions,difficulty,source_file_ids,date_created"
                        + "&sort=-date_created&limit=-1")
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
                Map<String, Object> response = http.get()
                                .uri(directusUrl + "/items/flashcards/" + flashcardSetId
                                                + "?fields=id,subject,cards")
                                .header(HttpHeaders.AUTHORIZATION,
                                                "Bearer " + authorizationService.getAdminToken())
                                .retrieve()
                                .body(MAP_TYPE);
                Map<String, Object> flashcardSet = dataMap(response);
                if (!subjectId.equals(String.valueOf(flashcardSet.get("subject")))) {
                        throw new IllegalArgumentException("La tarjeta no pertenece a esta materia");
                }
                Object cards = flashcardSet.get("cards");
                if (!(cards instanceof List<?> cardList)) {
                        throw new IllegalArgumentException("El set no contiene tarjetas válidas");
                }
                return cardList.stream()
                                .map(card -> objectMapper.convertValue(card, Flashcard.class))
                                .filter(card -> cardId.equals(card.id()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "La tarjeta no pertenece a este set"
                                ));
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