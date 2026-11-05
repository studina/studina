package com.studina.dinaapi.ai.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.util.JsonHelper;

/** Traduce el contrato de la aplicación a opciones propias de cada proveedor. */
final class LlmCallOptions {

    private static final JsonHelper JSON = new JsonHelper();

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
                    if (!jsonSchema.isEmpty()) {
                        options.responseSchema(JSON.toJson(googleSchema(jsonSchema)));
                    }
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

    /**
     * Spring AI 2.0 parses this JSON into the GenAI 1.58 OpenAPI Schema class before
     * sending responseJsonSchema. That class needs nullable instead of a type union
     * and cannot carry additionalProperties. Outermost array bounds duplicate the
     * complete resource grammar, which Gemini rejects for normal quizzes with several
     * UUID topics. Express batch counts as guidance instead, retaining native item
     * structure, topic enums and nested option bounds. Local validation still checks counts.
     * Gemini does not enforce minLength, so nonblank text also remains a local check.
     */
    private static Object googleSchema(Object value) {
        return googleSchema(value, 0);
    }

    private static Object googleSchema(Object value, int arrayDepth) {
        if (value instanceof Map<?, ?> source) {
            boolean array = "array".equals(source.get("type"));
            boolean batch = array && arrayDepth == 0;
            int childArrayDepth = array ? arrayDepth + 1 : arrayDepth;
            Map<String, Object> converted = new LinkedHashMap<>();
            source.forEach((key, child) -> {
                if (!"additionalProperties".equals(key)
                        && !(batch && ("minItems".equals(key) || "maxItems".equals(key)))) {
                    converted.put((String) key, googleSchema(child, childArrayDepth));
                }
            });
            if (batch && (source.containsKey("minItems") || source.containsKey("maxItems"))) {
                Object min = source.get("minItems");
                Object max = source.get("maxItems");
                String count = min != null && min.equals(max)
                        ? "Return exactly " + min + " items."
                        : "Item count: " + (min == null ? "no minimum" : "minimum " + min)
                                + ", " + (max == null ? "no maximum" : "maximum " + max) + ".";
                Object description = source.get("description");
                converted.put("description", description == null ? count : description + " " + count);
            }
            if (source.get("type") instanceof List<?> types) {
                List<?> nonNullTypes = types.stream().filter(type -> !"null".equals(type)).toList();
                if (types.size() != 2 || !types.contains("null") || nonNullTypes.size() != 1) {
                    throw new IllegalArgumentException("El schema de Gemini contiene una unión de tipos no admitida");
                }
                converted.put("type", nonNullTypes.getFirst());
                converted.put("nullable", true);
            }
            return converted;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(child -> googleSchema(child, arrayDepth)).toList();
        }
        return value;
    }
}
