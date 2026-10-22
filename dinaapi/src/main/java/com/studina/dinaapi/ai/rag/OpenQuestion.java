package com.studina.dinaapi.ai.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OpenQuestion(
        String id,
        String question,
        /** Respuesta modelo sacada del material. Es la rúbrica con la que se corrige. */
        @JsonProperty("reference_answer") String referenceAnswer,
        /** Ideas que una respuesta completa tiene que cubrir. */
        @JsonProperty("key_points") List<String> keyPoints,
        @JsonProperty("topic_id") String topicId
) implements GeneratedResource<OpenQuestion> {

    @Override
    public OpenQuestion identified(String questionId) {
        return new OpenQuestion(questionId, question, referenceAnswer, keyPoints, topicId);
    }
}
