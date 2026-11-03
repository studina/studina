package com.studina.dinaapi.ai.settings;

public record StoredUserLlmSettings(
        String id,
        String userId,
        String provider,
        String chatModel,
        String generationModel,
        String classificationModel,
        String summaryModel,
        String encryptedApiKey
) {}
