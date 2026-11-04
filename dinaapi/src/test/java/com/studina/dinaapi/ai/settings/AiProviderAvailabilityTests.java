package com.studina.dinaapi.ai.settings;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.studina.dinaapi.ai.llm.LlmProperties;

class AiProviderAvailabilityTests {

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
