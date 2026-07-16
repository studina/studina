package com.studina.dinaapi.ai;

import java.util.List;

public record FileChatRequest(
        String message,
        List<String> fileIds
) {
}