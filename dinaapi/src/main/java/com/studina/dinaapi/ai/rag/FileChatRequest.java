package com.studina.dinaapi.ai.rag;

import java.util.List;

public record FileChatRequest(
        String message,
        List<String> fileIds
) {
}