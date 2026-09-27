package com.studina.dinaapi.directus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.studina.dinaapi.ai.rag.QuizOption;
import com.studina.dinaapi.ai.rag.QuizQuestion;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class QuizSetService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    /** Columns of {@code quiz_questions} that rebuild a {@link QuizQuestion}. */
    private static final String QUESTION_FIELDS =
            "id,quiz_id,question,options,correct_option_id,explanation,topic_id,position";

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${directus.url}")
    private String directusUrl;

    public QuizSetService(DirectusAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    public Map<String, Object> create(
            String subjectId,
            String title,
            String instructions,
            String difficulty,
            List<String> sourceFileIds,
            List<QuizQuestion> questions) {

        String adminToken = authorizationService.getAdminToken();
        Map<String, Object> response = http.post()
                .uri(directusUrl + "/items/quizzes")
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

        Map<String, Object> savedQuiz = new LinkedHashMap<>(dataMap(response));
        String quizId = String.valueOf(savedQuiz.get("id"));
        for (int position = 0; position < questions.size(); position++) {
            QuizQuestion question = questions.get(position);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", question.id());
            fields.put("quiz_id", quizId);
            fields.put("question", question.question());
            fields.put("options", question.options());
            fields.put("correct_option_id", question.correctOptionId());
            fields.put("explanation", question.explanation());
            fields.put("topic_id", question.topicId());
            fields.put("position", position);
            http.post().uri(directusUrl + "/items/quiz_questions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .body(fields)
                    .retrieve().toBodilessEntity();
        }
        savedQuiz.put("questions", questions);
        return savedQuiz;
    }

    public List<Map<String, Object>> listForSubject(String subjectId) {
        List<Map<String, Object>> quizzes = listQuizRows(subjectId,
                "id,title,instructions,difficulty,source_file_ids,date_created");
        Map<String, List<QuizQuestion>> questionsByQuiz = loadQuestions(
                quizzes.stream().map(quiz -> String.valueOf(quiz.get("id"))).toList());

        return quizzes.stream().map(quiz -> {
            Map<String, Object> result = new LinkedHashMap<>(quiz);
            result.put("questions", questionsByQuiz.getOrDefault(String.valueOf(quiz.get("id")), List.of()));
            return result;
        }).toList();
    }

    private List<Map<String, Object>> listQuizRows(String subjectId, String fields) {
        Map<String, Object> response = http.get()
                .uri(UriComponentsBuilder.fromUriString(directusUrl + "/items/quizzes")
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
            throw new IllegalStateException("Directus devolvió una lista de cuestionarios inválida");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> quizzes = (List<Map<String, Object>>) data;
        return quizzes;
    }

    /**
     * Rebuilds the questions of each quiz from {@code quiz_questions}, which is the only place
     * they are stored. Returned as {@link QuizQuestion} records so the JSON the frontend reads
     * keeps its shape instead of exposing column names.
     */
    public Map<String, List<QuizQuestion>> loadQuestions(Collection<String> quizIds) {
        if (quizIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<QuizQuestion>> questionsByQuiz = new LinkedHashMap<>();
        for (Map<?, ?> row : questionRows(Map.of(
                "filter[quiz_id][_in]", String.join(",", quizIds),
                "sort", "position",
                "limit", "-1"))) {
            questionsByQuiz.computeIfAbsent(relationId(row.get("quiz_id")), ignored -> new ArrayList<>())
                    .add(toQuestion(row));
        }
        return questionsByQuiz;
    }

    private List<Map<?, ?>> questionRows(Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(directusUrl + "/items/quiz_questions")
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
            throw new IllegalStateException("Directus devolvió una lista de preguntas inválida");
        }
        List<Map<?, ?>> results = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> values) {
                results.add(values);
            }
        }
        return results;
    }

    private QuizQuestion toQuestion(Map<?, ?> row) {
        return new QuizQuestion(
                String.valueOf(row.get("id")),
                String.valueOf(row.get("question")),
                objectMapper.convertValue(row.get("options"), new TypeReference<List<QuizOption>>() {}),
                String.valueOf(row.get("correct_option_id")),
                row.get("explanation") == null ? null : String.valueOf(row.get("explanation")),
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
        return "Cuestionario #" + (listQuizRows(subjectId, "id").size() + 1);
    }

    public Map<String, Object> rename(String quizId, String subjectId, String title) {
        Map<String, Object> existing = getById(quizId);
        if (!subjectId.equals(String.valueOf(existing.get("subject")))) {
            throw new IllegalArgumentException("No tenés permiso para editar este cuestionario");
        }

        Map<String, Object> response = http.patch()
                .uri(directusUrl + "/items/quizzes/" + quizId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .body(Map.of("title", title))
                .retrieve()
                .body(MAP_TYPE);

        return dataMap(response);
    }

    public void requireQuizOwnership(String quizId, String subjectId) {
        Map<String, Object> existing = getById(quizId);
        if (!subjectId.equals(String.valueOf(existing.get("subject")))) {
            throw new IllegalArgumentException("El cuestionario no pertenece a esta materia");
        }
    }

    public QuizQuestion findQuestion(String quizId, String subjectId, String questionId) {
        requireQuizOwnership(quizId, subjectId);

        List<Map<?, ?>> rows = questionRows(Map.of(
                "filter[id][_eq]", questionId,
                "filter[quiz_id][_eq]", quizId,
                "limit", "1"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("La pregunta no pertenece a este cuestionario");
        }
        return toQuestion(rows.get(0));
    }

    public void delete(String quizId, String subjectId) {
        Map<String, Object> existing = getById(quizId);
        if (!subjectId.equals(String.valueOf(existing.get("subject")))) {
            throw new IllegalArgumentException("No tenés permiso para eliminar este cuestionario");
        }

        http.delete()
                .uri(directusUrl + "/items/quizzes/" + quizId)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> getById(String quizId) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/items/quizzes/" + quizId + "?fields=id,subject")
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
