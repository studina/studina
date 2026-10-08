package com.studina.dinaapi.ai.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;

@Service
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;
    private final LlmProperties properties;
    private final UserLlmSettingsService userSettingsService;
    private final LlmProviderClientFactory clientFactory;

    @Autowired
    public SpringAiLlmClient(
            ChatClient.Builder chatClientBuilder,
            LlmProperties properties,
            UserLlmSettingsService userSettingsService,
            LlmProviderClientFactory clientFactory
    ) {
        this(
                chatClientBuilder.build(),
                properties,
                userSettingsService,
                clientFactory);
    }

    SpringAiLlmClient(ChatClient chatClient, LlmProperties properties) {
        this(chatClient, properties, null, null);
    }

    SpringAiLlmClient(
            ChatClient chatClient,
            LlmProperties properties,
            UserLlmSettingsService userSettingsService,
            LlmProviderClientFactory clientFactory
    ) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.userSettingsService = userSettingsService;
        this.clientFactory = clientFactory;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        UserLlmRuntimeSettings userSettings = resolveUserSettings(request.userId());
        String selectedModel = userSettings == null
                ? properties.modelFor(request.useCase())
                : userSettings.modelFor(request.useCase());
        double temperature = request.temperature() != null
                ? request.temperature()
                : properties.getTemperature();
        ChatClient selectedClient;
        if (userSettings != null) {
            selectedClient = clientFactory.create(userSettings, selectedModel, temperature);
        } else if ("ollama".equals(properties.getProvider()) && clientFactory != null) {
            // Use the same local failure policy for server defaults and user preferences.
            selectedClient = clientFactory.create(
                    new UserLlmRuntimeSettings("ollama", selectedModel, null, null, null, null),
                    selectedModel,
                    temperature);
        } else {
            selectedClient = chatClient;
        }

        var prompt = selectedClient.prompt();

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            prompt.system(request.systemPrompt());
        }
        if (!request.messages().isEmpty()) {
            prompt.messages(request.messages());
        }
        if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.user(request.userPrompt());
        }

        String content = prompt
                .options(ChatOptions.builder()
                        .model(selectedModel)
                        .temperature(temperature))
                .call()
                .content();

        String provider = userSettings == null
                ? properties.getProvider()
                : userSettings.provider();
        return new LlmResponse(content, provider, selectedModel);
    }

    @Override
    public LlmModel model() {
        return model(LlmUseCase.CHAT);
    }

    public LlmModel model(LlmUseCase useCase) {
        return model(useCase, null);
    }

    @Override
    public LlmModel model(LlmUseCase useCase, String userId) {
        UserLlmRuntimeSettings userSettings = resolveUserSettings(userId);
        return userSettings == null
                ? new LlmModel(
                        properties.getProvider(), properties.modelFor(useCase))
                : new LlmModel(
                        userSettings.provider(), userSettings.modelFor(useCase));
    }

    private UserLlmRuntimeSettings resolveUserSettings(String userId) {
        if (userSettingsService == null || userId == null || userId.isBlank()) {
            return null;
        }
        return userSettingsService.runtimeFor(userId).orElse(null);
    }
}
