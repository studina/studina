package com.studina.dinaapi.ai.rag;

import java.util.List;

public record FlashcardGenerationRequest(
        String subjectId,
        String title,
        String instructions,
        List<String> fileIds
) {
}