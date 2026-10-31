package com.studina.dinaapi.ai.llm;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;

public record LlmRequest(
        String systemPrompt,
        List<Message> messages,
        String userPrompt,
        Double temperature,
        LlmUseCase useCase,
        String userId,
        boolean jsonResponse,
        Map<String, Object> jsonSchema
) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        useCase = useCase == null ? LlmUseCase.CHAT : useCase;
        jsonSchema = jsonSchema == null ? Map.of() : Map.copyOf(jsonSchema);
    }

    public LlmRequest(String systemPrompt, List<Message> messages, String userPrompt,
            Double temperature, LlmUseCase useCase, String userId, boolean jsonResponse) {
        this(systemPrompt, messages, userPrompt, temperature, useCase, userId, jsonResponse, Map.of());
    }

    public LlmRequest(String systemPrompt, List<Message> messages, String userPrompt,
            Double temperature, LlmUseCase useCase, String userId) {
        this(systemPrompt, messages, userPrompt, temperature, useCase, userId, false);
    }

    public LlmRequest(
            String systemPrompt,
            List<Message> messages,
            String userPrompt,
            Double temperature
    ) {
        this(systemPrompt, messages, userPrompt, temperature, LlmUseCase.CHAT, null);
    }

    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return new LlmRequest(
                systemPrompt, List.of(), userPrompt, null, LlmUseCase.CHAT, null);
    }

    public static LlmRequest of(
            String systemPrompt,
            List<Message> messages,
            String userPrompt
    ) {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, null, LlmUseCase.CHAT, null);
    }

    public LlmRequest withTemperature(double value) {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, value, useCase, userId, jsonResponse, jsonSchema);
    }

    public LlmRequest forUseCase(LlmUseCase value) {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, temperature, value, userId, jsonResponse, jsonSchema);
    }

    public LlmRequest forUser(String value) {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, temperature, useCase, value, jsonResponse, jsonSchema);
    }

    public LlmRequest withJsonResponse() {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, temperature, useCase, userId, true, jsonSchema);
    }

    public LlmRequest withJsonSchema(Map<String, Object> schema) {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, temperature, useCase, userId, true, schema);
    }
}
