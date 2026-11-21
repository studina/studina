package com.studina.dinaapi.ai.rag;

import java.util.List;

public record FlashcardGenerationResponse(
        List<Flashcard> cards,
        /** Título propuesto por la IA; se usa sólo si el estudiante no escribió uno. */
        String title,
        int chunksUsed,
        GenerationMetadata generationMetadata
) {
    public FlashcardGenerationResponse(List<Flashcard> cards, String title, int chunksUsed) {
        this(cards, title, chunksUsed, null);
    }
}
