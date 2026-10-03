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

        String selectedModel = properties.modelFor(request.useCase());
        String content = prompt
                .options(ChatOptions.builder()
                        .model(selectedModel)
                        .temperature(temperature))
                .call()
                .content();

        return new LlmResponse(content, properties.getProvider(), selectedModel);
    }

    @Override
    public LlmModel model() {
        return model(LlmUseCase.CHAT);
    }

    public LlmModel model(LlmUseCase useCase) {
        return new LlmModel(
                properties.getProvider(), properties.modelFor(useCase));
    }
}
