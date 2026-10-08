package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;

import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;

/**
 * Opt-in integration with an already running, isolated local Ollama instance.
 *
 * <p>Set OLLAMA_TEST_URL and OLLAMA_TEST_MODEL after downloading the model to that
 * instance. These tests do not start services, download models, access Directus or
 * pgvector, or use API keys. Only loopback HTTP endpoints are accepted.
 *
 * <p>From dinaapi:
 * OLLAMA_TEST_URL=http://127.0.0.1:11434 OLLAMA_TEST_MODEL=llama3.2:1b
 * ./gradlew test --tests '*OllamaLlmIntegrationTests' --rerun-tasks
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_TEST_URL", matches = ".+")
@Timeout(180)
class OllamaLlmIntegrationTests {

    private String model;
    private LlmProviderClientFactory factory;

    @BeforeEach
    void configureLocalInstance() {
        String baseUrl = System.getenv("OLLAMA_TEST_URL");
        URI endpoint = URI.create(baseUrl);
        assertThat(endpoint.getScheme()).as("Use a local HTTP Ollama endpoint").isEqualTo("http");
        assertThat(endpoint.getHost()).as("Remote providers are excluded from these tests")
                .isIn("localhost", "127.0.0.1", "::1", "[::1]");
        assertThat(endpoint.getUserInfo()).as("Ollama tests do not use credentials").isNull();

        model = System.getenv("OLLAMA_TEST_MODEL");
        assertThat(model).as("OLLAMA_TEST_MODEL must name an already downloaded model").isNotBlank();
        factory = new LlmProviderClientFactory(baseUrl, "http://127.0.0.1:1/v1");
    }

    @Test
    void generatesWithTheServerDefaultAndConversationHistory() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("ollama");
        properties.setModel(model);
        properties.setTemperature(0.0);
        ChatClient unusedDefaultClient = mock(ChatClient.class);
        UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
        when(settingsService.runtimeFor("ollama-default-integration-user")).thenReturn(Optional.empty());
        SpringAiLlmClient client = new SpringAiLlmClient(
                unusedDefaultClient, properties, settingsService, factory);

        LlmResponse response = client.generate(LlmRequest.of(
                "Follow the user's instructions. Return only the requested code.",
                List.of(new UserMessage("The code to remember is MENTA.")),
                "What code did I give you? Return only that code.")
                .forUser("ollama-default-integration-user"));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.model()).isEqualTo(model);
        assertThat(response.content()).containsIgnoringCase("MENTA");
        verifyNoInteractions(unusedDefaultClient);
    }

    @Test
    void selectsTheUsersProviderAndGenerationModelInsteadOfServerDefaults() {
        UserLlmRuntimeSettings settings = new UserLlmRuntimeSettings(
                "ollama", "unused-chat-model", model, null, null, null);
        UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
        when(settingsService.runtimeFor("ollama-integration-user"))
                .thenReturn(Optional.of(settings));
        ChatClient unusedDefaultClient = mock(ChatClient.class);
        SpringAiLlmClient client = new SpringAiLlmClient(
                unusedDefaultClient, new LlmProperties(), settingsService, factory);

        LlmResponse response = client.generate(LlmRequest.of(
                "Follow the user's instructions precisely.",
                "Reply with only the word OK.")
                .forUseCase(LlmUseCase.GENERATION)
                .forUser("ollama-integration-user")
                .withTemperature(0.0));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.model()).isEqualTo(model);
        assertThat(response.content()).containsIgnoringCase("OK");
        assertThat(client.model(LlmUseCase.GENERATION, "ollama-integration-user"))
                .isEqualTo(new LlmModel("ollama", model));
        verifyNoInteractions(unusedDefaultClient);
    }
}
