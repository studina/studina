package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;

class SpringAiLlmClientTests {

    @Test
    void sendsPortableOptionsAndReturnsProviderMetadata() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("respuesta"))));
        };
        LlmProperties properties = properties("google-genai", "gemini-test", 0.2);
        SpringAiLlmClient client = new SpringAiLlmClient(
                ChatClient.create(chatModel), properties);

        LlmResponse response = client.generate(new LlmRequest(
                "instrucciones",
                List.of(new UserMessage("mensaje anterior")),
                "pregunta",
                0.1));

        assertThat(response).isEqualTo(
                new LlmResponse("respuesta", "google-genai", "gemini-test"));
        assertThat(capturedPrompt.get().getOptions().getModel()).isEqualTo("gemini-test");
        assertThat(capturedPrompt.get().getOptions().getTemperature()).isEqualTo(0.1);
        assertThat(capturedPrompt.get().getInstructions())
                .extracting(message -> message.getText())
                .containsExactly("instrucciones", "mensaje anterior", "pregunta");
    }

    @Test
    void usesConfiguredTemperatureWhenTheRequestDoesNotOverrideIt() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("respuesta"))));
        };
        SpringAiLlmClient client = new SpringAiLlmClient(
                ChatClient.create(chatModel),
                properties("ollama", "llama-test", 0.35));

        client.generate(LlmRequest.of("sistema", "usuario"));

        assertThat(capturedPrompt.get().getOptions().getTemperature()).isEqualTo(0.35);
        assertThat(client.model()).isEqualTo(new LlmModel("ollama", "llama-test"));
    }

    @Test
    void selectsTheConfiguredModelForTheRequestedUseCase() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("respuesta"))));
        };
        LlmProperties properties = properties(
                "google-genai", "chat-model", 0.2);
        properties.setGenerationModel("generation-model");
        SpringAiLlmClient client = new SpringAiLlmClient(
                ChatClient.create(chatModel), properties);

        LlmResponse response = client.generate(
                LlmRequest.of("sistema", "usuario")
                        .forUseCase(LlmUseCase.GENERATION));

        assertThat(capturedPrompt.get().getOptions().getModel())
                .isEqualTo("generation-model");
        assertThat(response.model()).isEqualTo("generation-model");
        assertThat(client.model(LlmUseCase.GENERATION))
                .isEqualTo(new LlmModel("google-genai", "generation-model"));
    }

    @Test
    void routesAuthenticatedRequestsThroughTheUsersProviderAndCredential() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel userChatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("respuesta de usuario"))));
        };
        ChatClient userChatClient = ChatClient.create(userChatModel);
        UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
        LlmProviderClientFactory factory = mock(LlmProviderClientFactory.class);
        UserLlmRuntimeSettings settings = new UserLlmRuntimeSettings(
                "openai",
                "chat-model",
                "generation-model",
                null,
                null,
                "user-secret");
        when(settingsService.runtimeFor("user-a")).thenReturn(Optional.of(settings));
        when(factory.create(settings, "generation-model", 0.2))
                .thenReturn(userChatClient);

        SpringAiLlmClient client = new SpringAiLlmClient(
                ChatClient.create(prompt -> new ChatResponse(List.of())),
                properties("google-genai", "server-model", 0.2),
                settingsService,
                factory);

        LlmResponse response = client.generate(
                LlmRequest.of("sistema", "usuario")
                        .forUseCase(LlmUseCase.GENERATION)
                        .forUser("user-a"));

        assertThat(response).isEqualTo(new LlmResponse(
                "respuesta de usuario", "openai", "generation-model"));
        assertThat(capturedPrompt.get().getOptions().getModel())
                .isEqualTo("generation-model");
        verify(factory).create(settings, "generation-model", 0.2);
    }

    @Test
    void routesTheGlobalOllamaDefaultThroughTheFactoryWithoutUserSettings() {
        ChatClient unusedDefaultClient = mock(ChatClient.class);
        ChatClient localClient = ChatClient.create(prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("respuesta local")))));
        UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
        LlmProviderClientFactory factory = mock(LlmProviderClientFactory.class);
        when(settingsService.runtimeFor("user-without-settings")).thenReturn(Optional.empty());
        UserLlmRuntimeSettings selectedSettings = new UserLlmRuntimeSettings(
                "ollama", "generation-model", null, null, null, null);
        when(factory.create(selectedSettings, "generation-model", 0.1)).thenReturn(localClient);
        LlmProperties properties = properties("ollama", "chat-model", 0.2);
        properties.setGenerationModel("generation-model");
        SpringAiLlmClient client = new SpringAiLlmClient(
                unusedDefaultClient, properties, settingsService, factory);

        LlmResponse response = client.generate(LlmRequest.of("sistema", "usuario")
                .forUseCase(LlmUseCase.GENERATION)
                .forUser("user-without-settings")
                .withTemperature(0.1));

        assertThat(response).isEqualTo(new LlmResponse("respuesta local", "ollama", "generation-model"));
        verify(factory).create(selectedSettings, "generation-model", 0.1);
        verifyNoInteractions(unusedDefaultClient);
    }

    private static LlmProperties properties(
            String provider,
            String model,
            double temperature
    ) {
        LlmProperties properties = new LlmProperties();
        properties.setProvider(provider);
        properties.setModel(model);
        properties.setTemperature(temperature);
        return properties;
    }
}
