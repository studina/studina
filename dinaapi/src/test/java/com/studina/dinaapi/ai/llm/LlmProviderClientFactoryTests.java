package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;

class LlmProviderClientFactoryTests {

    private final LlmProviderClientFactory factory = new LlmProviderClientFactory(
            "http://localhost:11434",
            "https://api.openai.com/v1");

    @Test
    void createsClientsForEverySupportedProviderWithoutCallingTheNetwork() {
        assertThat(create("google-genai", "gemini-test", "google-key"))
                .isInstanceOf(ChatClient.class);
        assertThat(create("ollama", "llama-test", null))
                .isInstanceOf(ChatClient.class);
        assertThat(create("openai", "gpt-test", "openai-key"))
                .isInstanceOf(ChatClient.class);
    }

    @Test
    void addsTheRequiredApiVersionToTheOfficialOpenAiUrl() {
        assertThat(LlmProviderClientFactory.normalizeOpenAiBaseUrl(
                "https://api.openai.com"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(LlmProviderClientFactory.normalizeOpenAiBaseUrl(
                "https://api.openai.com/v1/"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(LlmProviderClientFactory.normalizeOpenAiBaseUrl(
                "http://compatible.local/v1"))
                .isEqualTo("http://compatible.local/v1");
    }

    @Test
    void omitsTemperatureForOpenAiReasoningModels() {
        OpenAiChatOptions reasoning = factory.openAiOptions(
                "test-key", "gpt-6-luna", 0.2);
        OpenAiChatOptions classic = factory.openAiOptions(
                "test-key", "gpt-4.1-mini", 0.2);

        assertThat(reasoning.getTemperature()).isNull();
        assertThat(classic.getTemperature()).isEqualTo(0.2);
    }

    private ChatClient create(String provider, String model, String apiKey) {
        return factory.create(
                new UserLlmRuntimeSettings(
                        provider, model, null, null, null, apiKey),
                model,
                0.2);
    }
}
