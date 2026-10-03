package com.studina.dinaapi.ai.settings;

public record UserLlmSettingsView(
        String source,
        String provider,
        String chatModel,
        String generationModel,
        String classificationModel,
        String summaryModel,
        boolean credentialConfigured
) {}
