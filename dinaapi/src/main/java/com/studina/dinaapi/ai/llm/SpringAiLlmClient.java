package com.studina.dinaapi.ai.llm;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.atomic.AtomicInteger;
import com.studina.dinaapi.ai.rag.AiRetry;
import com.studina.dinaapi.ai.rag.AiProviderErrorMapper;
import org.springframework.stereotype.Service;

import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;

@Service
public class SpringAiLlmClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiLlmClient.class);

    private final ChatClient chatClient;
    private final LlmProperties properties;
    private final UserLlmSettingsService userSettingsService;
    private final LlmProviderClientFactory clientFactory;
    private final AiRetry.Sleeper generationSleeper;

    // Only resource generation has an application retry policy; classification/evaluation retain theirs.
    @Value("${ai.generation.max-attempts:2}")
    private int generationMaxAttempts = 2;
    @Value("${ai.generation.retry-delay-ms:1000}")
    private long generationRetryDelayMs = 1000;
    @Value("${ai.generation.timeout-ms:60000}")
    private long generationTimeoutMs = 60000;
    // Compartido por todos los usuarios y tareas de esta API; Gemini/OpenAI no esperan.
    private final Semaphore ollamaCalls = new Semaphore(1, true);

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
        this(chatClient, properties, userSettingsService, clientFactory, AiRetry.realSleeper());
    }

    SpringAiLlmClient(ChatClient chatClient, LlmProperties properties,
            UserLlmSettingsService userSettingsService, LlmProviderClientFactory clientFactory,
            AiRetry.Sleeper generationSleeper) {
        this.generationSleeper = generationSleeper;
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
        String provider = userSettings == null ? properties.getProvider() : userSettings.provider();
        boolean controlledGeneration = ("google-genai".equals(provider) || "openai".equals(provider))
                && request.resourceGeneration() && clientFactory != null;
        ChatClient selectedClient;
        if (controlledGeneration) {
            selectedClient = null; // Built per attempt with the remaining total deadline.
        } else if (userSettings != null) {
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

        String callId = UUID.randomUUID().toString();
        boolean localOllama = "ollama".equals(provider);
        long waitingStarted = System.nanoTime();
        if (localOllama) {
            try {
                ollamaCalls.acquire();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logger.warn("LLM call interrupted: callId={}, provider={}, phase=gate, gateWaitMs={}",
                        callId, provider, elapsedMillis(waitingStarted));
                throw new IllegalStateException("Se interrumpió la espera de Ollama", exception);
            }
        }
        long gateWaitMs = localOllama ? elapsedMillis(waitingStarted) : 0;
        long callStarted = System.nanoTime();
        logger.info("LLM call started: callId={}, provider={}, model={}, useCase={}, json={}, schemaAttached={}, nativeSchema={}, gateWaitMs={}",
                callId, provider, safeModelName(selectedModel), request.useCase(), request.jsonResponse(),
                !request.jsonSchema().isEmpty(), !request.jsonSchema().isEmpty()
                        && (localOllama || ("google-genai".equals(provider) && request.jsonResponse())), gateWaitMs);
        AtomicInteger attempts = new AtomicInteger();
        try {
            ChatResponse response;
            if (controlledGeneration) {
                response = AiRetry.withBackoff(generationMaxAttempts, generationRetryDelayMs,
                        generationTimeoutMs, generationSleeper, remainingMillis -> {
                            int attempt = attempts.incrementAndGet();
                            long attemptStarted = System.nanoTime();
                            logger.info("LLM attempt: callId={}, provider={}, attempt={}, maxAttempts={}, remainingMs={}",
                                    callId, provider, attempt, generationMaxAttempts, remainingMillis);
                            try {
                                ChatClient attemptClient = clientFactory.generation(provider,
                                        userSettings == null ? null : userSettings.apiKey(), selectedModel,
                                        temperature, remainingMillis);
                                return call(attemptClient, request, provider, selectedModel, temperature);
                            } catch (RuntimeException exception) {
                                var failure = AiProviderErrorMapper.diagnostic(exception);
                                logger.warn("LLM attempt failed: callId={}, provider={}, attempt={}, attemptMs={}, "
                                                + "kind={}, upstreamStatus={}, providerCode={}, retryAfterMs={}, rootCauseClass={}",
                                        callId, provider, attempt, elapsedMillis(attemptStarted), failure.kind(),
                                        failure.upstreamStatus(), failure.providerCode(), failure.retryAfterMs(),
                                        failure.rootCauseClass());
                                throw exception;
                            }
                        }, (attempt, maxAttempts, delayMs) -> logger.warn(
                                "LLM retry scheduled: callId={}, provider={}, attempt={}, maxAttempts={}, delayMs={}",
                                callId, provider, attempt, maxAttempts, delayMs));
            } else {
                attempts.incrementAndGet();
                response = call(selectedClient, request, provider, selectedModel, temperature);
            }
            var generation = response == null ? null : response.getResult();
            String content = generation == null || generation.getOutput() == null
                    ? null : generation.getOutput().getText();
            String finishReason = safeFinishReason(generation == null
                    ? null : generation.getMetadata().getFinishReason());
            Usage usage = response == null ? null : response.getMetadata().getUsage();
            logger.info("LLM call completed: callId={}, provider={}, model={}, reportedModel={}, useCase={}, "
                            + "gateWaitMs={}, callMs={}, attempts={}, finishReason={}, promptTokens={}, completionTokens={}, "
                            + "loadMs={}, promptEvalMs={}, evalMs={}, totalMs={}",
                    callId, provider, safeModelName(selectedModel),
                    safeModelName(response == null ? null : response.getMetadata().getModel()),
                    request.useCase(), gateWaitMs, elapsedMillis(callStarted), attempts.get(), finishReason,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens(),
                    durationMillis(response, "load-duration"), durationMillis(response, "prompt-eval-duration"),
                    durationMillis(response, "eval-duration"), durationMillis(response, "total-duration"));
            if (request.resourceGeneration() && request.jsonResponse()
                    && "length".equals(finishReason)) {
                throw new IllegalArgumentException(
                        "La respuesta de IA se interrumpió al alcanzar el límite de generación. "
                                + "No se guardó un recurso incompleto");
            }
            return new LlmResponse(content, provider, selectedModel, gateWaitMs);
        } catch (RuntimeException exception) {
            var failure = AiProviderErrorMapper.diagnostic(exception);
            logger.warn("LLM call failed: callId={}, provider={}, model={}, useCase={}, gateWaitMs={}, callMs={}, attempts={}, "
                            + "kind={}, upstreamStatus={}, providerCode={}, retryAfterMs={}, exceptionClass={}, rootCauseClass={}",
                    callId, provider, safeModelName(selectedModel), request.useCase(), gateWaitMs,
                    elapsedMillis(callStarted), attempts.get(), failure.kind(), failure.upstreamStatus(),
                    failure.providerCode(), failure.retryAfterMs(), exception.getClass().getSimpleName(),
                    failure.rootCauseClass());
            throw exception;
        } finally {
            if (localOllama) {
                ollamaCalls.release();
            }
        }
    }

    private static ChatResponse call(ChatClient client, LlmRequest request, String provider,
            String model, double temperature) {
        var prompt = client.prompt();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            prompt.system(request.systemPrompt());
        }
        if (!request.messages().isEmpty()) {
            prompt.messages(request.messages());
        }
        if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.user(request.userPrompt());
        }
        return prompt.options(LlmCallOptions.create(provider, model, temperature,
                request.jsonResponse(), request.jsonSchema(), request.resourceGeneration()).mutate()).call().chatResponse();
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static Long durationMillis(ChatResponse response, String key) {
        Object value = response == null ? null : response.getMetadata().get(key);
        return value instanceof Duration duration ? duration.toMillis() : null;
    }

    private static String safeModelName(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}") ? value : "unknown";
    }

    private static String safeFinishReason(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "length", "max_tokens", "max_output_tokens" -> "length";
            case "stop", "end_turn", "tool_calls", "content_filter", "safety", "recitation" ->
                    value.trim().toLowerCase(Locale.ROOT);
            default -> "other";
        };
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
