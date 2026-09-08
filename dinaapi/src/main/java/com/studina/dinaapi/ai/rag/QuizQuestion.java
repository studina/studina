package com.studina.dinaapi.ai.rag;

import java.util.List;

public record QuizQuestion(
        String id,
        String question,
        List<QuizOption> options,
        String correctOptionId,
        String explanation
) {
}