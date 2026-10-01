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

import com.studina.dinaapi.ai.rag.QuizOption;
import com.studina.dinaapi.ai.rag.QuizQuestion;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.learning.LearningAnalyticsService;
import com.studina.dinaapi.learning.QuizAttemptRequest;
import com.studina.dinaapi.learning.QuizProgressService;

class QuizControllerHttpTests {

    private static final String AUTHORIZATION = "Bearer user-token";
    private static final String USER_ID = "user-1";
    private static final String SUBJECT_ID = "subject-1";
    private static final String QUIZ_ID = "quiz-1";
    private static final String QUESTION_ID = "question-1";
    private static final String ATTEMPT_ID = "22222222-2222-2222-2222-222222222222";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DirectusAuthorizationService authorizationService;
    private QuizSetService setService;
    private QuizProgressService progressService;
    private LearningAnalyticsService analyticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authorizationService = mock(DirectusAuthorizationService.class);
        setService = mock(QuizSetService.class);
        progressService = mock(QuizProgressService.class);
        analyticsService = mock(LearningAnalyticsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new QuizController(
                authorizationService, setService, progressService, analyticsService)).build();
    }

    @Test
    void recordsAnAnswerThroughTheHttpContract() throws Exception {
        QuizQuestion question = authorizeOwnedQuiz();
        QuizAttemptRequest request = new QuizAttemptRequest(QUESTION_ID, "b", ATTEMPT_ID);
        when(progressService.recordAnswer(USER_ID, SUBJECT_ID, QUIZ_ID, question, request))
                .thenReturn(Map.of("is_correct", true, "attempt_id", ATTEMPT_ID));

        mockMvc.perform(post("/quizzes/{quizId}/attempts", QUIZ_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.attempt_id").value(ATTEMPT_ID));
    }

    @Test
    void returnsBadRequestWhenTheQuestionDoesNotBelongToTheQuiz() throws Exception {
        authorize();
        when(setService.findQuestion(QUIZ_ID, QUESTION_ID))
                .thenThrow(new IllegalArgumentException(
                        "La pregunta no pertenece a este cuestionario"));

        mockMvc.perform(post("/quizzes/{quizId}/attempts", QUIZ_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QuizAttemptRequest(QUESTION_ID, "b", ATTEMPT_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "La pregunta no pertenece a este cuestionario"));
    }

    @Test
    void listsOnlyAfterCheckingSubjectOwnership() throws Exception {
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        when(setService.listForSubject(SUBJECT_ID)).thenReturn(List.of(
                Map.of("id", QUIZ_ID, "title", "Parcial")));

        mockMvc.perform(get("/quizzes")
                        .param("subjectId", SUBJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Parcial"));

        verify(authorizationService).requireSubjectOwnership(USER_ID, SUBJECT_ID);
    }

    @Test
    void validatesANameBeforeCallingAuthorization() throws Exception {
        mockMvc.perform(patch("/quizzes/{quizId}", QUIZ_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "El nombre del cuestionario es obligatorio"));

        verifyNoInteractions(authorizationService, setService);
    }

    @Test
    void trimsAndRenamesAnOwnedQuiz() throws Exception {
        authorize();
        when(setService.rename(QUIZ_ID, "Final"))
                .thenReturn(Map.of("id", QUIZ_ID, "title", "Final"));

        mockMvc.perform(patch("/quizzes/{quizId}", QUIZ_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  Final  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Final"));

        verify(setService).rename(QUIZ_ID, "Final");
    }

    @Test
    void deletingAQuizRecalculatesTheSubjectsProgress() throws Exception {
        authorize();

        mockMvc.perform(delete("/quizzes/{quizId}", QUIZ_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isNoContent());

        verify(setService).delete(QUIZ_ID);
        verify(analyticsService).recalculateTopicProgressForSubject(SUBJECT_ID);
    }

    private void authorize() {
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        when(setService.requireOwnedQuiz(USER_ID, QUIZ_ID)).thenReturn(SUBJECT_ID);
    }

    private QuizQuestion authorizeOwnedQuiz() {
        authorize();
        QuizQuestion question = new QuizQuestion(
                QUESTION_ID,
                "¿Qué significa TCP?",
                List.of(new QuizOption("a", "Uno"), new QuizOption("b", "Dos")),
                "b",
                "Explicación",
                "topic-1");
        when(setService.findQuestion(QUIZ_ID, QUESTION_ID)).thenReturn(question);
        return question;
    }
}
