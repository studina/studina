package com.studina.dinaapi.ai.llm;

public record LlmResponse(String content, String provider, String model, long queueWaitMs) {

    public LlmResponse(String content, String provider, String model) {
        this(content, provider, model, 0);
    }

    public LlmResponse withContent(String replacement) {
        return new LlmResponse(replacement, provider, model, queueWaitMs);
    }
}
