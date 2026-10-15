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

import com.studina.dinaapi.ai.rag.TrueFalseStatement;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.learning.LearningAnalyticsService;
import com.studina.dinaapi.learning.TrueFalseAttemptRequest;
import com.studina.dinaapi.learning.TrueFalseProgressService;

class TrueFalseControllerHttpTests {

    private static final String AUTHORIZATION = "Bearer user-token";
    private static final String USER_ID = "user-1";
    private static final String SUBJECT_ID = "subject-1";
    private static final String SET_ID = "set-1";
    private static final String STATEMENT_ID = "statement-1";
    private static final String ATTEMPT_ID = "22222222-2222-2222-2222-222222222222";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DirectusAuthorizationService authorizationService;
    private TrueFalseSetService setService;
    private TrueFalseProgressService progressService;
    private LearningAnalyticsService analyticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authorizationService = mock(DirectusAuthorizationService.class);
        setService = mock(TrueFalseSetService.class);
        progressService = mock(TrueFalseProgressService.class);
        analyticsService = mock(LearningAnalyticsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TrueFalseController(
                authorizationService, setService, progressService, analyticsService)).build();
    }

    @Test
    void recordsAnAnswerThroughTheHttpContract() throws Exception {
        TrueFalseStatement statement = authorizeOwnedStatement();
        TrueFalseAttemptRequest request = new TrueFalseAttemptRequest(STATEMENT_ID, false, ATTEMPT_ID);
        when(progressService.recordAnswer(USER_ID, SUBJECT_ID, SET_ID, statement, request))
                .thenReturn(Map.of("is_correct", true, "attempt_id", ATTEMPT_ID));

        mockMvc.perform(post("/true-false/{setId}/attempts", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.attempt_id").value(ATTEMPT_ID));
    }

    @Test
    void returnsBadRequestWhenTheStatementDoesNotBelongToTheSet() throws Exception {
        authorize();
        when(setService.findStatement(SET_ID, STATEMENT_ID))
                .thenThrow(new IllegalArgumentException("El enunciado no pertenece a este set"));

        mockMvc.perform(post("/true-false/{setId}/attempts", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TrueFalseAttemptRequest(STATEMENT_ID, true, ATTEMPT_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El enunciado no pertenece a este set"));
    }

    @Test
    void listsOnlyAfterCheckingSubjectOwnership() throws Exception {
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        when(setService.listForSubject(SUBJECT_ID)).thenReturn(List.of(
                Map.of("id", SET_ID, "title", "Redes")));

        mockMvc.perform(get("/true-false")
                        .param("subjectId", SUBJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Redes"));

        verify(authorizationService).requireSubjectOwnership(USER_ID, SUBJECT_ID);
    }

    @Test
    void validatesANameBeforeCallingAuthorization() throws Exception {
        mockMvc.perform(patch("/true-false/{setId}", SET_ID)
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

        mockMvc.perform(patch("/true-false/{setId}", SET_ID)
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

        mockMvc.perform(delete("/true-false/{setId}", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isNoContent());

        verify(setService).delete(SET_ID);
        verify(analyticsService).recalculateTopicProgressForSubject(SUBJECT_ID);
    }

    private void authorize() {
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        when(setService.requireOwnedSet(USER_ID, SET_ID)).thenReturn(SUBJECT_ID);
    }

    private TrueFalseStatement authorizeOwnedStatement() {
        authorize();
        TrueFalseStatement statement = new TrueFalseStatement(
                STATEMENT_ID, "TCP es un protocolo sin conexión", false, "Es orientado a conexión", "topic-1");
        when(setService.findStatement(SET_ID, STATEMENT_ID)).thenReturn(statement);
        return statement;
    }
}
