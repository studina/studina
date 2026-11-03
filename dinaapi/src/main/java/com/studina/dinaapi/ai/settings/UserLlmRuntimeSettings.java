package com.studina.dinaapi.ai.settings;

import com.studina.dinaapi.ai.llm.LlmUseCase;

public record UserLlmRuntimeSettings(
        String provider,
        String chatModel,
        String generationModel,
        String classificationModel,
        String summaryModel,
        String apiKey
) {
    public String modelFor(LlmUseCase useCase) {
        return switch (useCase == null ? LlmUseCase.CHAT : useCase) {
            case CHAT -> chatModel;
            case GENERATION -> fallback(generationModel);
            case CLASSIFICATION -> fallback(classificationModel);
            case SUMMARY -> fallback(summaryModel);
        };
    }

    private String fallback(String value) {
        return value == null || value.isBlank() ? chatModel : value;
    }
}
