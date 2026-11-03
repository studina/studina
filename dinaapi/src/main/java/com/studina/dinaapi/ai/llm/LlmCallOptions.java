package com.studina.dinaapi.ai.llm;

import java.util.Map;
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
        return create(provider, model, temperature, jsonResponse, Map.of());
    }

    static ChatOptions create(String provider, String model, double temperature,
            boolean jsonResponse, Map<String, Object> jsonSchema) {
        return create(provider, model, temperature, jsonResponse, jsonSchema, false);
    }

    static ChatOptions create(String provider, String model, double temperature,
            boolean jsonResponse, Map<String, Object> jsonSchema, boolean resourceGeneration) {
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
                if ("ollama".equals(provider)) {
                    if (!jsonSchema.isEmpty()) {
                        options.format(jsonSchema);
                    }
                    if (resourceGeneration) {
                        options.numCtx(GenerationContextBudget.CONTEXT_TOKENS)
                                .numBatch(128).numThread(4).keepAlive("0s")
                                .numPredict(GenerationContextBudget.MAX_OUTPUT_TOKENS);
                    }
                }
                yield options.build();
            }
            case "openai" -> {
                var options = OpenAiChatOptions.builder().model(model);
                if (LlmProviderClientFactory.supportsTemperature(model)) {
                    options.temperature(temperature);
                }
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
