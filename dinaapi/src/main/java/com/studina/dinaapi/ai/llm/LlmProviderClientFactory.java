package com.studina.dinaapi.ai.llm;

import com.google.genai.Client;
import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderClientFactory {

    private final String ollamaBaseUrl;
    private final String openAiBaseUrl;

    public LlmProviderClientFactory(
            @Value("${spring.ai.ollama.base-url}") String ollamaBaseUrl,
            @Value("${spring.ai.openai.base-url}") String openAiBaseUrl
    ) {
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.openAiBaseUrl = normalizeOpenAiBaseUrl(openAiBaseUrl);
    }

    public ChatClient create(
            UserLlmRuntimeSettings settings,
            String model,
            double temperature
    ) {
        return switch (settings.provider()) {
            case "google-genai" -> google(settings.apiKey(), model, temperature);
            case "ollama" -> ollama(model, temperature);
            case "openai" -> openAi(settings.apiKey(), model, temperature);
            default -> throw new IllegalArgumentException(
                    "Proveedor de IA no soportado: " + settings.provider());
        };
    }

    private ChatClient google(String apiKey, String model, double temperature) {
        Client genAiClient = Client.builder().apiKey(apiKey).build();
        GoogleGenAiChatModel chatModel = GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .options(GoogleGenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();
        return ChatClient.create(chatModel);
    }

    private ChatClient ollama(String model, double temperature) {
        OllamaApi api = OllamaApi.builder().baseUrl(ollamaBaseUrl).build();
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(api)
                .options(OllamaChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();
        return ChatClient.create(chatModel);
    }

    private ChatClient openAi(String apiKey, String model, double temperature) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(apiKey)
                        .baseUrl(openAiBaseUrl)
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();
        return ChatClient.create(chatModel);
    }

    static String normalizeOpenAiBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }

        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if ("https://api.openai.com".equalsIgnoreCase(normalized)) {
            return normalized + "/v1";
        }

        return normalized;
    }
}
