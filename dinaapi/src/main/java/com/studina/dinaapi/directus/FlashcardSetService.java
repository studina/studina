package com.studina.dinaapi.directus;

import java.util.List;
import java.util.Map;

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

        Map<String, Object> response = http.post()
                .uri(directusUrl + "/items/flashcards")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .body(Map.of(
                        "subject", subjectId,
                        "title", resolveTitle(subjectId, title),
                        "cards", cards,
                        "instructions", instructions == null ? "" : instructions,
                        "difficulty", difficulty == null ? "basic" : difficulty,
                        "source_file_ids", sourceFileIds
                ))
                .retrieve()
                .body(MAP_TYPE);

        return dataMap(response);
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