package com.studina.dinaapi.ai.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;
    private final LlmProperties properties;

    @Autowired
    public SpringAiLlmClient(
            ChatClient.Builder chatClientBuilder,
            LlmProperties properties
    ) {
        this(chatClientBuilder.build(), properties);
    }

    SpringAiLlmClient(ChatClient chatClient, LlmProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        var prompt = chatClient.prompt();

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            prompt.system(request.systemPrompt());
        }
        if (!request.messages().isEmpty()) {
            prompt.messages(request.messages());
        }
        if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.user(request.userPrompt());
        }

        double temperature = request.temperature() != null
                ? request.temperature()
                : properties.getTemperature();

        String content = prompt
                .options(ChatOptions.builder()
                        .model(properties.getModel())
                        .temperature(temperature))
                .call()
                .content();

        LlmModel model = model();
        return new LlmResponse(content, model.provider(), model.model());
    }

    @Override
    public LlmModel model() {
        return new LlmModel(properties.getProvider(), properties.getModel());
    }
}
