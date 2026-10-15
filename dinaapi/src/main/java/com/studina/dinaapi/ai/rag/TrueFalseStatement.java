package com.studina.dinaapi.ai.rag;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TrueFalseStatement(
        String id,
        String statement,
        /** Si el enunciado es verdadero. Es la respuesta correcta del ejercicio. */
        @JsonProperty("isTrue") Boolean isTrue,
        String explanation,
        @JsonProperty("topic_id") String topicId
) implements GeneratedResource<TrueFalseStatement> {

    @Override
    public TrueFalseStatement identified(String statementId) {
        return new TrueFalseStatement(statementId, statement, isTrue, explanation, topicId);
    }
}
