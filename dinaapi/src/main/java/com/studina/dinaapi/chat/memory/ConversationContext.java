package com.studina.dinaapi.chat.memory;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

public record ConversationContext(
        String summary,
        List<Message> recentMessages
        ) {

    public static ConversationContext empty() {
        return new ConversationContext(
                null,
                List.of()
        );
    }
}
