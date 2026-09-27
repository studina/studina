package com.studina.dinaapi.learning;

public record FlashcardAttemptRequest(
        String cardId,
        Integer rating,
        String sessionId,
        String idempotencyKey
) {
}