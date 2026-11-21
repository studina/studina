package com.studina.dinaapi.ai.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OllamaModelCatalogService {

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;

    public OllamaModelCatalogService(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public List<String> installedModels() {
        Map<String, Object> response = client.get()
                .uri("/api/tags")
                .retrieve()
                .body(RESPONSE_TYPE);
        if (response == null || !(response.get("models") instanceof List<?> models)) {
            throw new IllegalStateException("Ollama devolvió una lista de modelos inválida");
        }

        List<String> names = new ArrayList<>();
        for (Object item : models) {
            if (!(item instanceof Map<?, ?> model)) {
                throw new IllegalStateException("Ollama devolvió una lista de modelos inválida");
            }
            Object name = model.get("name");
            if (!(name instanceof String text) || text.isBlank()) {
                name = model.get("model");
            }
            if (!(name instanceof String text) || text.isBlank()) {
                throw new IllegalStateException("Ollama devolvió un nombre de modelo inválido");
            }
            names.add(text);
        }
        return names.stream().distinct().sorted().toList();
    }
}
