package com.studina.dinaapi.directus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.studina.dinaapi.ai.rag.OpenQuestion;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.learning.LearningAnalyticsService;
import com.studina.dinaapi.learning.OpenQuestionAttemptRequest;
import com.studina.dinaapi.learning.OpenQuestionProgressService;

class OpenQuestionControllerHttpTests {

    private static final String AUTHORIZATION = "Bearer user-token";
    private static final String USER_ID = "user-1";
    private static final String SUBJECT_ID = "subject-1";
    private static final String SET_ID = "set-1";
    private static final String QUESTION_ID = "question-1";
    private static final String ATTEMPT_ID = "44444444-4444-4444-4444-444444444444";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DirectusAuthorizationService authorizationService;
    private OpenQuestionSetService setService;
    private OpenQuestionProgressService progressService;
    private LearningAnalyticsService analyticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authorizationService = mock(DirectusAuthorizationService.class);
        setService = mock(OpenQuestionSetService.class);
        progressService = mock(OpenQuestionProgressService.class);
        analyticsService = mock(LearningAnalyticsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OpenQuestionController(
                authorizationService, setService, progressService, analyticsService)).build();
    }

    @Test
    void returnsTheEvaluationOfTheAnswer() throws Exception {
        OpenQuestion question = authorizeOwnedQuestion();
        OpenQuestionAttemptRequest request = new OpenQuestionAttemptRequest(
                QUESTION_ID, "Porque interpreta la pérdida como congestión", ATTEMPT_ID);
        when(progressService.recordAnswer(USER_ID, SUBJECT_ID, SET_ID, question, request))
                .thenReturn(Map.of(
                        "verdict", "correct",
                        "score", 0.75,
                        "feedback", "Bien, te faltó mencionar la carga de la red.",
                        "attempt_id", ATTEMPT_ID));

        mockMvc.perform(post("/open-questions/{setId}/attempts", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("correct"))
                .andExpect(jsonPath("$.score").value(0.75))
                .andExpect(jsonPath("$.attempt_id").value(ATTEMPT_ID));
    }

    @Test
    void returnsBadRequestWhenTheQuestionDoesNotBelongToTheSet() throws Exception {
        authorize();
        when(setService.findQuestion(SET_ID, QUESTION_ID))
                .thenThrow(new IllegalArgumentException("La pregunta no pertenece a este set"));

        mockMvc.perform(post("/open-questions/{setId}/attempts", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpenQuestionAttemptRequest(QUESTION_ID, "Respuesta", ATTEMPT_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La pregunta no pertenece a este set"));
    }

    @Test
    void marksAnOverloadedModelAsRetryable() throws Exception {
        OpenQuestion question = authorizeOwnedQuestion();
        OpenQuestionAttemptRequest request = new OpenQuestionAttemptRequest(QUESTION_ID, "Respuesta", ATTEMPT_ID);
        when(progressService.recordAnswer(USER_ID, SUBJECT_ID, SET_ID, question, request))
                .thenThrow(new IllegalStateException("503 The model is overloaded"));

        mockMvc.perform(post("/open-questions/{setId}/attempts", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void listsOnlyAfterCheckingSubjectOwnership() throws Exception {
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        when(setService.listForSubject(SUBJECT_ID)).thenReturn(List.of(
                Map.of("id", SET_ID, "title", "Redes")));

        mockMvc.perform(get("/open-questions")
                        .param("subjectId", SUBJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Redes"));

        verify(authorizationService).requireSubjectOwnership(USER_ID, SUBJECT_ID);
    }

    @Test
    void validatesANameBeforeCallingAuthorization() throws Exception {
        mockMvc.perform(patch("/open-questions/{setId}", SET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El nombre del set es obligatorio"));

        verifyNoInteractions(authorizationService, setService);
    }

    @Test
    void trimsAndRenamesAnOwnedSet() throws Exception {
        authorize();
        when(setService.rename(SET_ID, "Final"))
                .thenReturn(Map.of("id", SET_ID, "title", "Final"));

        mockMvc.perform(patch("/open-questions/{setId}", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  Final  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Final"));

        verify(setService).rename(SET_ID, "Final");
    }

    @Test
    void deletingASetRecalculatesTheSubjectsProgress() throws Exception {
        authorize();

        mockMvc.perform(delete("/open-questions/{setId}", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isNoContent());

        verify(setService).delete(SET_ID);
        verify(analyticsService).recalculateTopicProgressForSubject(SUBJECT_ID);
    }

    private void authorize() {
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        when(setService.requireOwnedSet(USER_ID, SET_ID)).thenReturn(SUBJECT_ID);
    }

    private OpenQuestion authorizeOwnedQuestion() {
        authorize();
        OpenQuestion question = new OpenQuestion(
                QUESTION_ID,
                "¿Por qué TCP reduce la ventana ante una pérdida?",
                "Porque interpreta la pérdida como congestión.",
                List.of("la pérdida indica congestión"),
                "topic-1");
        when(setService.findQuestion(SET_ID, QUESTION_ID)).thenReturn(question);
        return question;
    }
}
