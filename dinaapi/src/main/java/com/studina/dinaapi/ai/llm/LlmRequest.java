package com.studina.dinaapi.ai.llm;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

public record LlmRequest(
        String systemPrompt,
        List<Message> messages,
        String userPrompt,
        Double temperature
) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return new LlmRequest(systemPrompt, List.of(), userPrompt, null);
    }

    public static LlmRequest of(
            String systemPrompt,
            List<Message> messages,
            String userPrompt
    ) {
        return new LlmRequest(systemPrompt, messages, userPrompt, null);
    }

    public LlmRequest withTemperature(double value) {
        return new LlmRequest(systemPrompt, messages, userPrompt, value);
    }
}
