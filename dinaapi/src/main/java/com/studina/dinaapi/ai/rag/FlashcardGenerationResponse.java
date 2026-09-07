package com.studina.dinaapi.ai.rag;

import java.util.List;

public record FlashcardGenerationResponse(
        List<Flashcard> cards,
        int chunksUsed
) {
}