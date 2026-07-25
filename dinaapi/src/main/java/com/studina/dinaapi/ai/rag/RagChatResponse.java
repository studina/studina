package com.studina.dinaapi.ai.rag;

import java.util.List;

public record RagChatResponse(
        String answer,
        int chunksUsed,
        List<RagChunkInfo> retrievedChunks
) {
}