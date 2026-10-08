package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import com.sun.net.httpserver.HttpServer;
import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;

class LlmProviderClientFactoryTests {

    private final LlmProviderClientFactory factory = new LlmProviderClientFactory(
            "http://localhost:11434",
            "https://api.openai.com/v1");

    @Test
    void createsClientsForEverySupportedProviderWithoutCallingTheNetwork() {
        assertThat(create("google-genai", "gemini-test", "google-key"))
                .isInstanceOf(ChatClient.class);
        assertThat(create("ollama", "llama-test", null))
                .isInstanceOf(ChatClient.class);
        assertThat(create("openai", "gpt-test", "openai-key"))
                .isInstanceOf(ChatClient.class);
    }

    @Test
    void addsTheRequiredApiVersionToTheOfficialOpenAiUrl() {
        assertThat(LlmProviderClientFactory.normalizeOpenAiBaseUrl(
                "https://api.openai.com"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(LlmProviderClientFactory.normalizeOpenAiBaseUrl(
                "https://api.openai.com/v1/"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(LlmProviderClientFactory.normalizeOpenAiBaseUrl(
                "http://compatible.local/v1"))
                .isEqualTo("http://compatible.local/v1");
    }

    @Test
    void omitsTemperatureForOpenAiReasoningModels() {
        OpenAiChatOptions reasoning = factory.openAiOptions(
                "test-key", "gpt-6-luna", 0.2);
        OpenAiChatOptions classic = factory.openAiOptions(
                "test-key", "gpt-4.1-mini", 0.2);

        assertThat(reasoning.getTemperature()).isNull();
        assertThat(classic.getTemperature()).isEqualTo(0.2);
    }

    @Test
    @Timeout(15)
    void failsOnAnOllamaServerErrorWithoutRetryingTheGlobalDefault() throws Exception {
        assertOllamaServerErrorIsNotRetried(false);
    }

    @Test
    @Timeout(15)
    void failsOnAnOllamaServerErrorWithoutRetryingTheUsersProvider() throws Exception {
        assertOllamaServerErrorIsNotRetried(true);
    }

    private void assertOllamaServerErrorIsNotRetried(boolean userSettingsEnabled) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":\"Ollama unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();

        try {
            ChatClient unusedDefaultClient = mock(ChatClient.class);
            LlmProperties properties = new LlmProperties();
            properties.setProvider(userSettingsEnabled ? "google-genai" : "ollama");
            properties.setModel("local-test-model");
            UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
            when(settingsService.runtimeFor("local-test-user")).thenReturn(userSettingsEnabled
                    ? Optional.of(new UserLlmRuntimeSettings(
                            "ollama", "local-test-model", null, null, null, null))
                    : Optional.empty());
            SpringAiLlmClient client = new SpringAiLlmClient(
                    unusedDefaultClient, properties, settingsService, localFactory(server.getAddress().getPort()));

            assertThatThrownBy(() -> client.generate(LlmRequest.of("", "Reply with OK.")
                    .forUser("local-test-user")))
                    .isInstanceOf(TransientAiException.class)
                    .hasMessageContaining("503");
            assertThat(requests).hasValue(1);
            verifyNoInteractions(unusedDefaultClient);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void failsQuicklyWhenTheLocalOllamaConnectionIsRefused() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = socket.getLocalPort();
        }
        ChatClient client = ollamaClient(unusedPort);
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.prompt().user("Reply with OK.").call().content())
                .isInstanceOf(ResourceAccessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @Timeout(20)
    void allowsLocalInferenceToTakeLongerThanTheDefaultTenSecondResponseTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(Duration.ofSeconds(12).toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            byte[] body = """
                    {"model":"local-test-model","message":{"role":"assistant","content":"respuesta local"},
                     "done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();

        try {
            assertThat(ollamaClient(server.getAddress().getPort())
                    .prompt().user("Reply with OK.").call().content())
                    .isEqualTo("respuesta local");
        } finally {
            server.stop(0);
        }
    }

    private ChatClient ollamaClient(int port) {
        return localFactory(port)
                .create(new UserLlmRuntimeSettings("ollama", "local-test-model", null, null, null, null),
                        "local-test-model", 0.0);
    }

    private LlmProviderClientFactory localFactory(int port) {
        return new LlmProviderClientFactory("http://127.0.0.1:" + port, "http://127.0.0.1:1/v1");
    }

    private ChatClient create(String provider, String model, String apiKey) {
        return factory.create(
                new UserLlmRuntimeSettings(
                        provider, model, null, null, null, apiKey),
                model,
                0.2);
    }
}
