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

import com.studina.dinaapi.ai.rag.Flashcard;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.learning.AttemptNotFoundException;
import com.studina.dinaapi.learning.FlashcardAttemptRequest;
import com.studina.dinaapi.learning.FlashcardProgressService;
import com.studina.dinaapi.learning.LearningAnalyticsService;

class FlashcardControllerHttpTests {

    private static final String AUTHORIZATION = "Bearer user-token";
    private static final String USER_ID = "user-1";
    private static final String SUBJECT_ID = "subject-1";
    private static final String SET_ID = "set-1";
    private static final String CARD_ID = "card-1";
    private static final String ATTEMPT_ID = "11111111-1111-1111-1111-111111111111";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DirectusAuthorizationService authorizationService;
    private FlashcardSetService setService;
    private FlashcardProgressService progressService;
    private LearningAnalyticsService analyticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authorizationService = mock(DirectusAuthorizationService.class);
        setService = mock(FlashcardSetService.class);
        progressService = mock(FlashcardProgressService.class);
        analyticsService = mock(LearningAnalyticsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FlashcardController(
                authorizationService, setService, progressService, analyticsService)).build();
    }

    @Test
    void recordsAnAttemptUsingTheSubjectOwnedByTheSet() throws Exception {
        Flashcard card = authorizeOwnedSet();
        FlashcardAttemptRequest request = new FlashcardAttemptRequest(CARD_ID, 4, ATTEMPT_ID);
        when(progressService.recordAttempt(USER_ID, SUBJECT_ID, SET_ID, card, request))
                .thenReturn(Map.of("rating", 4, "attempt_id", ATTEMPT_ID));

        mockMvc.perform(post("/flashcards/{setId}/attempts", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.attempt_id").value(ATTEMPT_ID));

        verify(progressService).recordAttempt(USER_ID, SUBJECT_ID, SET_ID, card, request);
    }

    @Test
    void rejectsAnAttemptForACardOutsideTheSet() throws Exception {
        authorize();
        when(setService.findCard(SET_ID, CARD_ID))
                .thenThrow(new IllegalArgumentException("La tarjeta no pertenece a este set"));

        mockMvc.perform(post("/flashcards/{setId}/attempts", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new FlashcardAttemptRequest(CARD_ID, 3, ATTEMPT_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La tarjeta no pertenece a este set"));
    }

    @Test
    void returnsNotFoundWhenCorrectingAnAttemptThatWasNeverStored() throws Exception {
        Flashcard card = authorizeOwnedSet();
        when(progressService.updateAttempt(
                USER_ID, SUBJECT_ID, SET_ID, card, ATTEMPT_ID, 3))
                .thenThrow(new AttemptNotFoundException("El intento no existe"));

        mockMvc.perform(patch("/flashcards/{setId}/attempts/{attemptId}", SET_ID, ATTEMPT_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new FlashcardAttemptRequest(CARD_ID, 3, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("El intento no existe"));
    }

    @Test
    void validatesANameBeforeCallingAuthorization() throws Exception {
        mockMvc.perform(patch("/flashcards/{setId}", SET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El nombre del set es obligatorio"));

        verifyNoInteractions(authorizationService, setService);
    }

    @Test
    void trimsAndRenamesAnOwnedSet() throws Exception {
        authorize();
        when(setService.rename(SET_ID, "Parcial 1"))
                .thenReturn(Map.of("id", SET_ID, "title", "Parcial 1"));

        mockMvc.perform(patch("/flashcards/{setId}", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  Parcial 1  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Parcial 1"));

        verify(setService).rename(SET_ID, "Parcial 1");
    }

    @Test
    void rejectsNamesLongerThanTheDatabaseLimit() throws Exception {
        mockMvc.perform(patch("/flashcards/{setId}", SET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "a".repeat(161)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "El nombre del set no puede superar los 160 caracteres"));
    }

    @Test
    void deletingASetRecalculatesTheSubjectsProgress() throws Exception {
        authorize();

        mockMvc.perform(delete("/flashcards/{setId}", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isNoContent());

        verify(setService).delete(SET_ID);
        verify(analyticsService).recalculateTopicProgressForSubject(SUBJECT_ID);
    }

    @Test
    void exposesTopicProgressWithOptionalCoverage() throws Exception {
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        when(analyticsService.topicProgress(USER_ID, SUBJECT_ID, true)).thenReturn(List.of(
                Map.of("topic_id", "topic-1", "mastery_score", 0.75, "coverage", 0.5)));

        mockMvc.perform(get("/flashcards/progress")
                        .param("subjectId", SUBJECT_ID)
                        .param("coverage", "true")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].topic_id").value("topic-1"))
                .andExpect(jsonPath("$[0].coverage").value(0.5));

        verify(authorizationService).requireSubjectOwnership(USER_ID, SUBJECT_ID);
    }

    @Test
    void translatesAnInvalidReviewRoundToBadRequest() throws Exception {
        authorize();
        when(analyticsService.reviewRound(USER_ID, SUBJECT_ID, SET_ID))
                .thenThrow(new IllegalArgumentException("La ronda quedó vacía"));

        mockMvc.perform(get("/flashcards/{setId}/review-round", SET_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La ronda quedó vacía"));
    }

    private void authorize() {
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        when(setService.requireOwnedSet(USER_ID, SET_ID)).thenReturn(SUBJECT_ID);
    }

    private Flashcard authorizeOwnedSet() {
        authorize();
        Flashcard card = new Flashcard(CARD_ID, "TCP", "Protocolo", "topic-1");
        when(setService.findCard(SET_ID, CARD_ID)).thenReturn(card);
        return card;
    }
}
