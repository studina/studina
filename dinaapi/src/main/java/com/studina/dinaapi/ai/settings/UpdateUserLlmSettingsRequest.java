package com.studina.dinaapi.ai.settings;

public record UpdateUserLlmSettingsRequest(
        String provider,
        String chatModel,
        String generationModel,
        String classificationModel,
        String summaryModel,
        String apiKey
) {}
