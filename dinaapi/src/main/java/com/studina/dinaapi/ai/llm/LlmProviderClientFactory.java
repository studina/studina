package com.studina.dinaapi.ai.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.studina.dinaapi.ai.rag.AiProviderErrorMapper;
import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LlmProviderClientFactory {

    private final String ollamaBaseUrl;
    private final String openAiBaseUrl;
    private final JdkClientHttpRequestFactory ollamaRequestFactory;
    private final String defaultGoogleApiKey;
    private final String googleGenerationBaseUrl;
    private final OkHttpClient externalGenerationHttpClient;

    @Autowired
    public LlmProviderClientFactory(
            @Value("${spring.ai.ollama.base-url}") String ollamaBaseUrl,
            @Value("${spring.ai.openai.base-url}") String openAiBaseUrl,
            @Value("${ai.ollama.read-timeout-seconds:600}") long ollamaReadTimeoutSeconds,
            @Value("${spring.ai.google.genai.api-key:}") String defaultGoogleApiKey
    ) {
        this(ollamaBaseUrl, openAiBaseUrl, ollamaReadTimeoutSeconds,
                defaultGoogleApiKey, null);
    }

    public LlmProviderClientFactory(String ollamaBaseUrl, String openAiBaseUrl, long ollamaReadTimeoutSeconds) {
        this(ollamaBaseUrl, openAiBaseUrl, ollamaReadTimeoutSeconds, null, null);
    }

    LlmProviderClientFactory(String ollamaBaseUrl, String openAiBaseUrl, long ollamaReadTimeoutSeconds,
            String defaultGoogleApiKey, String googleGenerationBaseUrl) {
        if (ollamaReadTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("El tiempo de espera de Ollama debe ser mayor que cero");
        }
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.openAiBaseUrl = normalizeOpenAiBaseUrl(openAiBaseUrl);
        this.defaultGoogleApiKey = defaultGoogleApiKey;
        this.googleGenerationBaseUrl = googleGenerationBaseUrl;
        this.externalGenerationHttpClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                // Network interception also prevents OkHttp's hidden 503/Retry-After:0 follow-up.
                .addNetworkInterceptor(chain -> {
                    var response = chain.proceed(chain.request());
                    int status = response.code();
                    if (status == 429 || status == 503 || status == 504) {
                        try (response) {
                            var retryAfter = AiProviderErrorMapper.parseRetryAfter(
                                    response.header("Retry-After"), Instant.now());
                            var retryAfterMs = AiProviderErrorMapper.parseRetryAfterMillisHeader(
                                    response.header("Retry-After-Ms"));
                            Long hint = retryAfter.isEmpty() && retryAfterMs.isEmpty() ? null
                                    : Math.max(retryAfter.orElse(0), retryAfterMs.orElse(0));
                            boolean quota = status == 429 && AiProviderErrorMapper.isLongTermQuota(
                                    response.peekBody(16 * 1024).string());
                            throw new AiProviderErrorMapper.HttpFailure(status,
                                    hint, quota);
                        }
                    }
                    return response;
                }).build();
        this.ollamaRequestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
        // Document context and CPU inference can take several minutes on local machines.
        this.ollamaRequestFactory.setReadTimeout(Duration.ofSeconds(ollamaReadTimeoutSeconds));
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

    /** One HTTP attempt. The generation boundary owns retries and the remaining deadline. */
    ChatClient generation(String provider, String apiKey, String model, double temperature, long remainingMillis) {
        return switch (provider) {
            case "google-genai" -> googleGeneration(apiKey, model, temperature, remainingMillis);
            case "openai" -> ChatClient.create(OpenAiChatModel.builder()
                    // Spring AI 2.0's transport has implicit OkHttp follow-ups independent of maxRetries.
                    // Delegate the wire call to the same single-attempt transport used by Gemini.
                    .httpClientBuilderCustomizer(builder -> builder.interceptor(chain ->
                            externalGenerationHttpClient.newBuilder()
                                    .callTimeout(Math.max(1, remainingMillis), TimeUnit.MILLISECONDS).build()
                                    .newCall(chain.request()).execute()))
                    .options(openAiOptions(apiKey, model, temperature)
                            .mutate().maxRetries(0).timeout(Duration.ofMillis(Math.max(1, remainingMillis))).build())
                    .build());
            default -> throw new IllegalArgumentException("Proveedor de generación externa no soportado");
        };
    }

    ChatClient googleGeneration(String apiKey, String model, double temperature, long remainingMillis) {
        var httpOptions = HttpOptions.builder().retryOptions(HttpRetryOptions.builder()
                .attempts(1).initialDelay(0.0).httpStatusCodes(List.of()).build());
        if (googleGenerationBaseUrl != null) {
            httpOptions.baseUrl(googleGenerationBaseUrl);
        }
        var httpClient = externalGenerationHttpClient.newBuilder()
                .callTimeout(Math.max(1, remainingMillis), TimeUnit.MILLISECONDS).build();
        Client api = Client.builder().apiKey(apiKey == null ? defaultGoogleApiKey : apiKey)
                .httpOptions(httpOptions.build())
                .clientOptions(ClientOptions.builder().customHttpClient(httpClient).build()).build();
        var modelClient = GoogleGenAiChatModel.builder().genAiClient(api)
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .options(GoogleGenAiChatOptions.builder().model(model).temperature(temperature).build())
                .build();
        return ChatClient.create(modelClient);
    }

    private ChatClient ollama(String model, double temperature) {
        OllamaApi api = OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(ollamaRequestFactory))
                .build();
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(api)
                // Local configuration or connection failures should reach the user immediately.
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .options(OllamaChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();
        return ChatClient.create(chatModel);
    }

    private ChatClient openAi(String apiKey, String model, double temperature) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(openAiOptions(apiKey, model, temperature))
                .build();
        return ChatClient.create(chatModel);
    }

    OpenAiChatOptions openAiOptions(
            String apiKey,
            String model,
            double temperature
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "OpenAI requiere una API key personal configurada en Mi perfil");
        }
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(openAiBaseUrl)
                .model(model);

        if (supportsTemperature(model)) {
            options.temperature(temperature);
        }

        return options.build();
    }

    static boolean supportsTemperature(String model) {
        if (model == null) {
            return true;
        }

        String normalized = model.toLowerCase(Locale.ROOT);
        return !normalized.startsWith("gpt-5")
                && !normalized.startsWith("gpt-6")
                && !normalized.startsWith("o1")
                && !normalized.startsWith("o3")
                && !normalized.startsWith("o4");
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
