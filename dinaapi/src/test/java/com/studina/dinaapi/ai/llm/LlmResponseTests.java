package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmResponseTests {

    @Test
    void defaultsToNoQueueWaitingForExistingCallers() {
        assertThat(new LlmResponse("respuesta", "openai", "generation-model").queueWaitMs()).isZero();
    }

    @Test
    void replacingContentPreservesTheProviderModelAndQueueWaiting() {
        LlmResponse response = new LlmResponse("original", "ollama", "generation-model", 7000);

        assertThat(response.withContent("reemplazo"))
                .isEqualTo(new LlmResponse("reemplazo", "ollama", "generation-model", 7000));
        assertThat(response.content()).isEqualTo("original");
    }
}
