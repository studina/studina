package com.studina.dinaapi.learning;

public record TrueFalseAttemptRequest(
        String statementId,
        /** Lo que respondió el estudiante: true si eligió "Verdadero". */
        Boolean answer,
        String attemptId
) {
}
