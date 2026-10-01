package com.studina.dinaapi.learning;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TopicClassification(
        @JsonProperty("existing_topic_ids") List<String> existingTopicIds,
        @JsonProperty("new_topics") List<NewTopicProposal> newTopics
) {
    public TopicClassification {
        existingTopicIds = existingTopicIds == null ? List.of() : List.copyOf(existingTopicIds);
        newTopics = newTopics == null ? List.of() : List.copyOf(newTopics);
    }
}