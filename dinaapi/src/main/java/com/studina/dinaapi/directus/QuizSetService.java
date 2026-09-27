package com.studina.dinaapi.directus;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.studina.dinaapi.ai.rag.QuizQuestion;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class QuizSetService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

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

        Map<String, Object> response = http.post()
                .uri(directusUrl + "/items/quizzes")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + authorizationService.getAdminToken())
                .body(Map.of(
                        "subject", subjectId,
                        "title", resolveTitle(subjectId, title),
                        "questions", questions,
                        "instructions", instructions == null ? "" : instructions,
                        "difficulty", difficulty == null ? "basic" : difficulty,
                        "source_file_ids", sourceFileIds
                ))
                .retrieve()
                .body(MAP_TYPE);

        Map<String, Object> savedQuiz = dataMap(response);
        String quizId = String.valueOf(savedQuiz.get("id"));
        for (QuizQuestion question : questions) {
            for (String topicId : question.topicIds().stream().distinct().toList()) {
                http.post().uri(directusUrl + "/items/quiz_question_topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationService.getAdminToken())
                        .body(Map.of(
                                "quiz_id", quizId,
                                "question_id", question.id(),
                                "topic_id", topicId
                        ))
                        .retrieve().toBodilessEntity();
            }
        }
        return savedQuiz;
    }

    public List<Map<String, Object>> listForSubject(String subjectId) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/items/quizzes?filter[subject][_eq]=" + subjectId
                        + "&fields=id,title,questions,instructions,difficulty,source_file_ids,date_created"
                        + "&sort=-date_created&limit=-1")
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

    private String resolveTitle(String subjectId, String title) {
        if (title != null && !title.isBlank()) return title.trim();
        return "Cuestionario #" + (listForSubject(subjectId).size() + 1);
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
                Map<String, Object> response = http.get()
                                .uri(directusUrl + "/items/quizzes/" + quizId + "?fields=id,subject,questions")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationService.getAdminToken())
                                .retrieve().body(MAP_TYPE);
                Map<String, Object> quiz = dataMap(response);
                if (!subjectId.equals(String.valueOf(quiz.get("subject")))) {
                        throw new IllegalArgumentException("El cuestionario no pertenece a esta materia");
                }
                if (!(quiz.get("questions") instanceof List<?> questions)) {
                        throw new IllegalArgumentException("El cuestionario no contiene preguntas válidas");
                }
                return questions.stream()
                                .map(question -> objectMapper.convertValue(question, QuizQuestion.class))
                                .filter(question -> questionId.equals(question.id()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("La pregunta no pertenece a este cuestionario"));
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