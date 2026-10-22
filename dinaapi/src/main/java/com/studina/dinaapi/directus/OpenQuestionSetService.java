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

import com.studina.dinaapi.ai.rag.OpenQuestion;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class OpenQuestionSetService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    /** Columns of {@code open_questions} that rebuild an {@link OpenQuestion}. */
    private static final String QUESTION_FIELDS =
            "id,set_id,question,reference_answer,key_points,topic_id,position";

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    public OpenQuestionSetService(DirectusAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    public Map<String, Object> create(
            String subjectId,
            String title,
            String instructions,
            String difficulty,
            List<String> sourceFileIds,
            List<OpenQuestion> questions) {

        String adminToken = authorizationService.getAdminToken();
        Map<String, Object> response = http.post()
                .uri(directusUrl + "/items/open_question_sets")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
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
        for (int position = 0; position < questions.size(); position++) {
            OpenQuestion question = questions.get(position);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", question.id());
            fields.put("set_id", setId);
            fields.put("question", question.question());
            fields.put("reference_answer", question.referenceAnswer());
            fields.put("key_points", question.keyPoints());
            fields.put("topic_id", question.topicId());
            fields.put("position", position);
            http.post().uri(directusUrl + "/items/open_questions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .body(fields)
                    .retrieve().toBodilessEntity();
        }
        savedSet.put("questions", questions);
        return savedSet;
    }

    public List<Map<String, Object>> listForSubject(String subjectId) {
        List<Map<String, Object>> sets = listSetRows(subjectId,
                "id,title,instructions,difficulty,source_file_ids,date_created");
        Map<String, List<OpenQuestion>> questionsBySet = loadQuestions(
                sets.stream().map(set -> String.valueOf(set.get("id"))).toList());

        return sets.stream().map(set -> {
            Map<String, Object> result = new LinkedHashMap<>(set);
            result.put("questions", questionsBySet.getOrDefault(String.valueOf(set.get("id")), List.of()));
            return result;
        }).toList();
    }

    private List<Map<String, Object>> listSetRows(String subjectId, String fields) {
        Map<String, Object> response = http.get()
                .uri(UriComponentsBuilder.fromUriString(directusUrl + "/items/open_question_sets")
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
            throw new IllegalStateException("Directus devolvió una lista de sets de preguntas abiertas inválida");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sets = (List<Map<String, Object>>) data;
        return sets;
    }

    /**
     * Rebuilds the questions of each set from {@code open_questions}, which is the only place
     * they are stored. Returned as {@link OpenQuestion} records so the JSON the frontend reads
     * keeps its shape instead of exposing column names.
     */
    public Map<String, List<OpenQuestion>> loadQuestions(Collection<String> setIds) {
        if (setIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<OpenQuestion>> questionsBySet = new LinkedHashMap<>();
        for (Map<?, ?> row : questionRows(Map.of(
                "filter[set_id][_in]", String.join(",", setIds),
                "sort", "position",
                "limit", "-1"))) {
            questionsBySet.computeIfAbsent(relationId(row.get("set_id")), ignored -> new ArrayList<>())
                    .add(toQuestion(row));
        }
        return questionsBySet;
    }

    private List<Map<?, ?>> questionRows(Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(directusUrl + "/items/open_questions")
                .queryParam("fields", QUESTION_FIELDS);
        params.forEach(builder::queryParam);
        Map<String, Object> response = http.get()
                .uri(builder.build().encode().toUri())
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .body(MAP_TYPE);

        Object data = response != null ? response.get("data") : null;
        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException("Directus devolvió una lista de preguntas abiertas inválida");
        }
        List<Map<?, ?>> results = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> values) {
                results.add(values);
            }
        }
        return results;
    }

    private OpenQuestion toQuestion(Map<?, ?> row) {
        List<String> keyPoints = row.get("key_points") instanceof List<?> points
                ? points.stream().map(String::valueOf).toList()
                : List.of();
        return new OpenQuestion(
                String.valueOf(row.get("id")),
                String.valueOf(row.get("question")),
                row.get("reference_answer") == null ? null : String.valueOf(row.get("reference_answer")),
                keyPoints,
                relationId(row.get("topic_id")));
    }

    private String relationId(Object value) {
        if (value instanceof Map<?, ?> relation) {
            return String.valueOf(relation.get("id"));
        }
        return value == null ? null : String.valueOf(value);
    }

    private String resolveTitle(String subjectId, String title) {
        if (title != null && !title.isBlank()) return title.trim();
        return "Preguntas abiertas #" + (listSetRows(subjectId, "id").size() + 1);
    }

    public Map<String, Object> rename(String setId, String title) {
        Map<String, Object> response = http.patch()
                .uri(directusUrl + "/items/open_question_sets/" + setId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .body(Map.of("title", title))
                .retrieve()
                .body(MAP_TYPE);

        return dataMap(response);
    }

    /**
     * Resuelve la materia del set y verifica que sea del usuario. Devuelve el subjectId.
     * Igual que en los demás recursos, la materia sale del propio recurso y no del cliente.
     */
    public String requireOwnedSet(String userId, String setId) {
        String subjectId = relationId(getById(setId).get("subject"));
        if (subjectId == null) {
            throw new IllegalArgumentException("El set de preguntas abiertas no pertenece a ninguna materia");
        }
        authorizationService.requireSubjectOwnership(userId, subjectId);
        return subjectId;
    }

    public OpenQuestion findQuestion(String setId, String questionId) {
        List<Map<?, ?>> rows = questionRows(Map.of(
                "filter[id][_eq]", questionId,
                "filter[set_id][_eq]", setId,
                "limit", "1"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("La pregunta no pertenece a este set");
        }
        return toQuestion(rows.get(0));
    }

    public void delete(String setId) {
        http.delete()
                .uri(directusUrl + "/items/open_question_sets/" + setId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> getById(String setId) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/items/open_question_sets/" + setId + "?fields=id,subject")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .body(MAP_TYPE);

        return dataMap(response);
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
