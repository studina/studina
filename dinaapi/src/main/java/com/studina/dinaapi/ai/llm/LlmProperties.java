package com.studina.dinaapi.ai.llm;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.chat")
public class LlmProperties {

    private static final Set<String> SUPPORTED_PROVIDERS =
            Set.of("google-genai", "ollama");

    private String provider = "google-genai";
    private String model = "gemini-2.5-flash";
    private String generationModel;
    private String classificationModel;
    private String summaryModel;
    private double temperature = 0.2;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException(
                    "Proveedor de chat no soportado: " + provider
                            + ". Valores válidos: " + SUPPORTED_PROVIDERS);
        }
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = requireModel(model, "chat");
    }

    public String getGenerationModel() {
        return fallbackToChatModel(generationModel);
    }

    public void setGenerationModel(String generationModel) {
        this.generationModel = requireModel(generationModel, "generación");
    }

    public String getClassificationModel() {
        return fallbackToChatModel(classificationModel);
    }

    public void setClassificationModel(String classificationModel) {
        this.classificationModel = requireModel(classificationModel, "clasificación");
    }

    public String getSummaryModel() {
        return fallbackToChatModel(summaryModel);
    }

    public void setSummaryModel(String summaryModel) {
        this.summaryModel = requireModel(summaryModel, "resumen");
    }

    public String modelFor(LlmUseCase useCase) {
        return switch (useCase == null ? LlmUseCase.CHAT : useCase) {
            case CHAT -> getModel();
            case GENERATION -> getGenerationModel();
            case CLASSIFICATION -> getClassificationModel();
            case SUMMARY -> getSummaryModel();
        };
    }

    private String fallbackToChatModel(String configuredModel) {
        return configuredModel == null ? model : configuredModel;
    }

    private static String requireModel(String value, String useCase) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "El modelo de " + useCase + " es obligatorio");
        }
        return value.trim();
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
