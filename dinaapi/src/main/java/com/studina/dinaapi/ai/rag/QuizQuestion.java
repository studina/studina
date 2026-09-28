package com.studina.dinaapi.ai.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuizQuestion(
        String id,
        String question,
        List<QuizOption> options,
        String correctOptionId,
        String explanation,
        @JsonProperty("topic_id") String topicId
) implements GeneratedResource<QuizQuestion> {

    @Override
    public QuizQuestion identified(String questionId) {
        return new QuizQuestion(questionId, question, options, correctOptionId, explanation, topicId);
    }
}
