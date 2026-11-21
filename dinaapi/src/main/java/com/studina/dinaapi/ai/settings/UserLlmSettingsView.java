package com.studina.dinaapi.ai.settings;

import java.util.List;
import java.util.Map;

public record UserLlmSettingsView(
        String source,
        String provider,
        String chatModel,
        String generationModel,
        String classificationModel,
        String summaryModel,
        boolean credentialConfigured,
        List<String> availableProviders,
        Map<String, List<AiProviderAvailability.ModelOption>> availableModels
) {}
