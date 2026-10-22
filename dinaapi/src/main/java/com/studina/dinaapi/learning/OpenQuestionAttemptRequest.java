package com.studina.dinaapi.learning;

public record OpenQuestionAttemptRequest(
        String questionId,
        /** Lo que escribió el estudiante. */
        String answer,
        String attemptId
) {
}
