package com.studina.dinaapi.ai.llm;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

public record LlmRequest(
        String systemPrompt,
        List<Message> messages,
        String userPrompt,
        Double temperature,
        LlmUseCase useCase,
        String userId,
        boolean jsonResponse
) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        useCase = useCase == null ? LlmUseCase.CHAT : useCase;
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
                systemPrompt, messages, userPrompt, value, useCase, userId, jsonResponse);
    }

    public LlmRequest forUseCase(LlmUseCase value) {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, temperature, value, userId, jsonResponse);
    }

    public LlmRequest forUser(String value) {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, temperature, useCase, value, jsonResponse);
    }

    public LlmRequest withJsonResponse() {
        return new LlmRequest(
                systemPrompt, messages, userPrompt, temperature, useCase, userId, true);
    }
}
