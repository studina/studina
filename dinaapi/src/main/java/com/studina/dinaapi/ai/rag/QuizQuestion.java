package com.studina.dinaapi.ai.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuizQuestion(
        String id,
        String question,
        List<QuizOption> options,
        String correctOptionId,
                String explanation,
                @JsonProperty("topic_ids") List<String> topicIds
) {
        public QuizQuestion {
                topicIds = topicIds == null ? List.of() : List.copyOf(topicIds);
        }

        public QuizQuestion identified(String questionId, List<String> canonicalTopicIds) {
                return new QuizQuestion(questionId, question, options, correctOptionId, explanation, canonicalTopicIds);
        }
}