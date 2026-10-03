package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;

class LlmProviderClientFactoryTests {

    private final LlmProviderClientFactory factory = new LlmProviderClientFactory(
            "http://localhost:11434",
            "https://api.openai.com");

    @Test
    void createsClientsForEverySupportedProviderWithoutCallingTheNetwork() {
        assertThat(create("google-genai", "gemini-test", "google-key"))
                .isInstanceOf(ChatClient.class);
        assertThat(create("ollama", "llama-test", null))
                .isInstanceOf(ChatClient.class);
        assertThat(create("openai", "gpt-test", "openai-key"))
                .isInstanceOf(ChatClient.class);
    }

    private ChatClient create(String provider, String model, String apiKey) {
        return factory.create(
                new UserLlmRuntimeSettings(
                        provider, model, null, null, null, apiKey),
                model,
                0.2);
    }
}
