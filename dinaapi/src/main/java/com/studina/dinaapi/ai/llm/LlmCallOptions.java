package com.studina.dinaapi.ai.llm;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Traduce el contrato de la aplicación a opciones propias de cada proveedor. */
final class LlmCallOptions {

    private LlmCallOptions() {
    }

    static ChatOptions create(String provider, String model, double temperature,
            boolean jsonResponse) {
        return switch (provider) {
            case "google-genai" -> {
                var options = GoogleGenAiChatOptions.builder().model(model).temperature(temperature);
                if (jsonResponse) {
                    options.responseMimeType("application/json");
                }
                yield options.build();
            }
            case "ollama", "ollama-cloud" -> {
                var options = OllamaChatOptions.builder().model(model).temperature(temperature);
                if (jsonResponse) {
                    options.format("json");
                }
                yield options.build();
            }
            case "openai" -> {
                var options = OpenAiChatOptions.builder().model(model).temperature(temperature);
                if (jsonResponse) {
                    options.responseFormat(ResponseFormat.builder()
                            .type(ResponseFormat.Type.JSON_OBJECT).build());
                }
                yield options.build();
            }
            default -> throw new IllegalArgumentException("Proveedor de IA no soportado: " + provider);
        };
    }
}
