package com.studina.dinaapi.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.ai.llm.LlmRequest;
import com.studina.dinaapi.ai.llm.LlmResponse;
import com.studina.dinaapi.ai.llm.LlmUseCase;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.chat.ChatConversationService;
import com.studina.dinaapi.chat.ChatMessageService;
import com.studina.dinaapi.chat.memory.ConversationContextService;

class AiControllerAuthenticationTests {

    private LlmClient llmClient;
    private ModelArmorService modelArmorService;
    private DirectusAuthorizationService authorizationService;
    private ChatConversationService conversationService;
    private ChatMessageService messageService;
    private ConversationContextService contextService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        modelArmorService = mock(ModelArmorService.class);
        authorizationService = mock(DirectusAuthorizationService.class);
        conversationService = mock(ChatConversationService.class);
        messageService = mock(ChatMessageService.class);
        contextService = mock(ConversationContextService.class);
        when(authorizationService.requireUserId(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(authorizationService.requireUserId(""))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(authorizationService.requireUserId("Bearer invalid"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(authorizationService.requireUserId("Bearer student-token")).thenReturn("student-id");
        mvc = MockMvcBuilders.standaloneSetup(new AiController(llmClient, modelArmorService,
                authorizationService, conversationService, messageService, contextService)).build();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "Bearer invalid")
    void unauthenticatedRequestsCannotReachInference(String authorization) throws Exception {
        var chat = post("/ai/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Explicá TCP\"}");
        var tags = get("/ai/suggest-tags").param("filename", "TCP.pdf");
        if (authorization != null) {
            chat.header("Authorization", authorization);
            tags.header("Authorization", authorization);
        }

        mvc.perform(chat).andExpect(status().isUnauthorized());
        mvc.perform(tags).andExpect(status().isUnauthorized());
        verifyNoInteractions(llmClient, modelArmorService, conversationService, messageService, contextService);
    }

    @Test
    void authenticatedRequestsUseTheVerifiedUserForChatAndClassification() throws Exception {
        when(llmClient.generate(any())).thenReturn(
                new LlmResponse("Respuesta", "openai", "gpt-test"),
                new LlmResponse("redes, tcp, TCP", "google-genai", "gemini-test"));

        mvc.perform(post("/ai/chat").header("Authorization", "Bearer student-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Explicá TCP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Respuesta"))
                .andExpect(jsonPath("$.provider").value("openai"));
        mvc.perform(get("/ai/suggest-tags").header("Authorization", "Bearer student-token")
                        .param("filename", "TCP.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("redes"))
                .andExpect(jsonPath("$[1]").value("tcp"));

        var requests = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient, times(2)).generate(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.userId()).isEqualTo("student-id"));
        assertThat(requests.getAllValues().get(0).useCase()).isEqualTo(LlmUseCase.CHAT);
        assertThat(requests.getAllValues().get(1).useCase()).isEqualTo(LlmUseCase.CLASSIFICATION);
        verifyNoInteractions(conversationService, messageService, contextService);
    }
}
