package com.studina.dinaapi.ai.settings;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.studina.dinaapi.ai.llm.LlmProperties;

class AiProviderAvailabilityTests {

    @Test
    void advertisedGeminiModelsAndRuntimePolicyUseTheSameCatalog() {
        var availability = new AiProviderAvailability("google-genai,openai", new LlmProperties());
        var models = availability.availableModels().get("google-genai");
        assertThat(models).extracting(AiProviderAvailability.ModelOption::value)
                .containsExactly("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.6-flash");
        for (var model : models) {
            AiProviderAvailability.requireModelAvailable("google-genai", model.value());
            AiProviderAvailability.requireModelAvailable("google-genai", "models/" + model.value());
        }
        AiProviderAvailability.requireModelAvailable("google-genai", new LlmProperties().getModel());
        AiProviderAvailability.requireModelAvailable("openai", "compatible-custom-model");
        AiProviderAvailability.requireModelAvailable("ollama", "installed-custom-model");
    }

    @Test
    void rejectsAnUnknownOrEmptyProviderListAtStartup() {
        for (String configured : new String[]{"", " ", "google-genai,unknown"}) {
            assertThatThrownBy(() -> new AiProviderAvailability(configured, new LlmProperties()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("AI_ALLOWED_PROVIDERS");
        }
    }

    @Test
    void requiresTheGeneralProviderToBeAvailable() {
        LlmProperties defaults = new LlmProperties();
        defaults.setProvider("ollama");
        assertThatThrownBy(() -> new AiProviderAvailability("google-genai,openai", defaults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proveedor general");
    }
}
