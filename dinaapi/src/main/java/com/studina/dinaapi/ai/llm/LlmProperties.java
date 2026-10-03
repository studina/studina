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
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("El modelo de chat es obligatorio");
        }
        this.model = model;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
