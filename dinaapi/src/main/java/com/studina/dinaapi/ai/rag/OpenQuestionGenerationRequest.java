package com.studina.dinaapi.ai.rag;

import java.util.List;

public record OpenQuestionGenerationRequest(
        String subjectId,
        String title,
        String instructions,
        List<String> fileIds,
        Integer questionCount,
        String difficulty
) {
}
