package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.studina.dinaapi.ai.llm.LlmResponse;

class GenerationMetadataTests {

    @ParameterizedTest
    @CsvSource({"0,10000", "7000,3000", "10000,0", "12000,0", "-100,10000", "9223372036854775807,0"})
    void excludesOnlyNonNegativeQueueWaitingFromTheGenerationDuration(long queueWaitMs, long expectedDurationMs) {
        LlmResponse response = new LlmResponse("respuesta", "ollama", "generation-model", queueWaitMs);
        long startedAtNanos = TimeUnit.SECONDS.toNanos(1);
        long completedAtNanos = TimeUnit.SECONDS.toNanos(11);

        GenerationMetadata metadata = GenerationMetadata.completed(response, startedAtNanos, completedAtNanos);

        assertThat(metadata).isEqualTo(new GenerationMetadata("ollama", "generation-model", expectedDurationMs));
    }

    @Test
    void keepsTheFullDurationForResponsesWithoutQueueMetadata() {
        GenerationMetadata metadata = GenerationMetadata.completed(
                new LlmResponse("respuesta", "openai", "generation-model"), 0, TimeUnit.SECONDS.toNanos(10));

        assertThat(metadata.durationMs()).isEqualTo(10000);
    }

    @Test
    void neverReportsANegativeDuration() {
        GenerationMetadata metadata = GenerationMetadata.completed(
                new LlmResponse("respuesta", "ollama", "generation-model", 100),
                TimeUnit.SECONDS.toNanos(10), 0);

        assertThat(metadata.durationMs()).isZero();
    }
}
