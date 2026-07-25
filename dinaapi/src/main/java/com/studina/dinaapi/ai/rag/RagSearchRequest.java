package com.studina.dinaapi.ai.rag;

import java.util.List;

public record RagSearchRequest(
        String query,
        List<String> fileIds
) {
}