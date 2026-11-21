package com.studina.dinaapi.ai.rag;

import java.util.List;

public record TrueFalseGenerationResponse(
        List<TrueFalseStatement> statements,
        /** Título propuesto por la IA; se usa sólo si el estudiante no escribió uno. */
        String title,
        int chunksUsed,
        GenerationMetadata generationMetadata
) {
    public TrueFalseGenerationResponse(List<TrueFalseStatement> statements, String title, int chunksUsed) {
        this(statements, title, chunksUsed, null);
    }
}
