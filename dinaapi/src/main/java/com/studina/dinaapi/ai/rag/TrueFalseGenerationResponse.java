package com.studina.dinaapi.ai.rag;

import java.util.List;

public record TrueFalseGenerationResponse(
        List<TrueFalseStatement> statements,
        /** Título propuesto por la IA; se usa sólo si el estudiante no escribió uno. */
        String title,
        int chunksUsed
) {
}
