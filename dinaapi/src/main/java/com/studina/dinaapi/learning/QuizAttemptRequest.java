package com.studina.dinaapi.learning;

public record QuizAttemptRequest(
        String questionId,
        String selectedOptionId,
        String sessionId,
        String idempotencyKey
) {
}
