package com.studina.dinaapi.ai.rag;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Flashcard(
        String id,
        String term,
        String definition,
        @JsonProperty("topic_id") String topicId
) implements GeneratedResource<Flashcard> {

    @Override
    public Flashcard identified(String cardId) {
        return new Flashcard(cardId, term, definition, topicId);
    }
}
