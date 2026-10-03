package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LlmPropertiesTests {

    @Test
    void acceptsTheSupportedProviders() {
        LlmProperties properties = new LlmProperties();

        properties.setProvider("ollama");
        assertThat(properties.getProvider()).isEqualTo("ollama");

        properties.setProvider("google-genai");
        assertThat(properties.getProvider()).isEqualTo("google-genai");

        properties.setProvider("openai");
        assertThat(properties.getProvider()).isEqualTo("openai");
    }

    @Test
    void rejectsAnUnknownProviderWithAnActionableMessage() {
        LlmProperties properties = new LlmProperties();

        assertThatThrownBy(() -> properties.setProvider("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Proveedor de chat no soportado")
                .hasMessageContaining("google-genai")
                .hasMessageContaining("ollama");
    }

    @Test
    void rejectsABlankModel() {
        LlmProperties properties = new LlmProperties();

        assertThatThrownBy(() -> properties.setModel("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El modelo de chat es obligatorio");
    }

    @Test
    void resolvesAModelPerUseCaseAndFallsBackToChat() {
        LlmProperties properties = new LlmProperties();
        properties.setModel("chat-model");
        properties.setGenerationModel("generation-model");
        properties.setClassificationModel("classification-model");
        properties.setSummaryModel("summary-model");

        assertThat(properties.modelFor(LlmUseCase.CHAT)).isEqualTo("chat-model");
        assertThat(properties.modelFor(LlmUseCase.GENERATION))
                .isEqualTo("generation-model");
        assertThat(properties.modelFor(LlmUseCase.CLASSIFICATION))
                .isEqualTo("classification-model");
        assertThat(properties.modelFor(LlmUseCase.SUMMARY))
                .isEqualTo("summary-model");

        LlmProperties defaults = new LlmProperties();
        defaults.setModel("shared-model");
        assertThat(defaults.modelFor(LlmUseCase.GENERATION))
                .isEqualTo("shared-model");
    }
}
