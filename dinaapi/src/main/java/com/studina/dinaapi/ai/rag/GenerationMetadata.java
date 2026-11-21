package com.studina.dinaapi.ai.rag;

import java.util.concurrent.TimeUnit;

import com.studina.dinaapi.ai.llm.LlmResponse;

/** Historical generation details, stored with the resource rather than inferred from current settings. */
public record GenerationMetadata(String provider, String model, long durationMs) {

    static GenerationMetadata completed(LlmResponse response, long startedAtNanos) {
        return completed(response, startedAtNanos, System.nanoTime());
    }

    static GenerationMetadata completed(LlmResponse response, long startedAtNanos, long completedAtNanos) {
        long elapsedMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(completedAtNanos - startedAtNanos));
        long queueWaitMs = Math.max(0, response.queueWaitMs());
        return new GenerationMetadata(response.provider(), response.model(),
                Math.max(0, elapsedMs - queueWaitMs));
    }
}
