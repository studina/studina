package com.studina.dinaapi.ai.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Flashcard(
                String id,
        String term,
                String definition,
                @JsonProperty("topic_ids") List<String> topicIds,
                @JsonProperty("primary_topic_id") String primaryTopicId
) {
        public Flashcard(String term, String definition) {
                this(null, term, definition, List.of(), null);
        }

        public Flashcard {
                topicIds = topicIds == null ? List.of() : List.copyOf(topicIds);
        }

        public Flashcard identified(String cardId, List<String> canonicalTopicIds) {
                String primaryTopic = canonicalTopicIds.isEmpty() ? null : canonicalTopicIds.getFirst();
                return new Flashcard(cardId, term, definition, canonicalTopicIds, primaryTopic);
        }
}