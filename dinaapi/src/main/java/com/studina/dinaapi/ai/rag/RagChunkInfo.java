package com.studina.dinaapi.ai.rag;

public record RagChunkInfo(
        String fileId,
        Integer chunkIndex,
        double score
) {
}