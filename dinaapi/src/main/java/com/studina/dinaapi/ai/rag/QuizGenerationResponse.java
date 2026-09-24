package com.studina.dinaapi.ai.rag;

import java.util.List;

public record QuizGenerationResponse(
        List<QuizQuestion> questions,
        int chunksUsed
) {
}