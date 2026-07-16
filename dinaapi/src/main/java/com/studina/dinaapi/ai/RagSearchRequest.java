package com.studina.dinaapi.ai;

import java.util.List;

public record RagSearchRequest(
        String query,
        List<String> fileIds
) {
}