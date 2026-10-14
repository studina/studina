package com.studina.dinaapi.ai.rag;

import java.util.List;

public record TrueFalseGenerationRequest(
        String subjectId,
        String title,
        String instructions,
        List<String> fileIds,
        Integer statementCount,
        String difficulty
) {
}
