package com.studina.dinaapi.ai.rag;

import java.util.List;

public record QuizGenerationRequest(
        String subjectId,
        String title,
        String instructions,
        List<String> fileIds,
        Integer questionCount,
        Integer answerCount,
        String difficulty
) {
}