package com.studina.dinaapi.ai.llm;

public record LlmResponse(String content, String provider, String model) {

    public LlmResponse withContent(String replacement) {
        return new LlmResponse(replacement, provider, model);
    }
}
