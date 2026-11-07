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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
            "https://api.openai.com/v1",
            600);

    @Test
    void createsClientsForEverySupportedProviderWithoutCallingTheNetwork() {
        assertThat(create("google-genai", "gemini-test", "google-key"))
                .isInstanceOf(ChatClient.class);
        assertThat(create("ollama", "llama-test", null))
                .isInstanceOf(ChatClient.class);
        assertThat(create("openai", "gpt-test", "openai-key"))
                .isInstanceOf(ChatClient.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsOpenAiWithoutAPersonalCredentialBeforeBuildingAClient(String apiKey) {
        String message = "OpenAI requiere una API key personal configurada en Mi perfil";

        assertThatThrownBy(() -> create("openai", "gpt-test", apiKey))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> factory.generation("openai", apiKey, "gpt-test", 0.2, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> factory.openAiOptions(apiKey, "gpt-test", 0.2))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
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
        HttpServer server = slowOllamaServer(new AtomicInteger(), 12);

        try {
            assertThat(ollamaClient(server.getAddress().getPort())
                    .prompt().user("Reply with OK.").call().content())
                    .isEqualTo("respuesta local");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonPositiveOllamaTimeouts() {
        for (long timeout : new long[]{0, -1}) {
            assertThatThrownBy(() -> new LlmProviderClientFactory(
                    "http://localhost:11434", "http://127.0.0.1:1/v1", timeout))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mayor que cero");
        }
    }

    @Test
    @Timeout(10)
    void stopsWaitingAtTheConfiguredOllamaTimeoutWithoutRetrying() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = slowOllamaServer(requests, 2);

        try {
            ChatClient client = localFactory(server.getAddress().getPort(), 1)
                    .create(new UserLlmRuntimeSettings("ollama", "local-test-model", null, null, null, null),
                            "local-test-model", 0.0);

            assertThatThrownBy(() -> client.prompt().user("Reply with OK.").call().content())
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void sendsAFullyDelimitedRequestAndClosesTheConnectionAtTheOllamaDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
                var worker = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(4000);
            var observedRequest = worker.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(4000);
                    var input = socket.getInputStream();
                    StringBuilder rawHeaders = new StringBuilder();
                    while (!rawHeaders.toString().endsWith("\r\n\r\n")) {
                        int next = input.read();
                        if (next == -1 || rawHeaders.length() >= 16 * 1024) {
                            throw new IllegalStateException("Incomplete HTTP request headers");
                        }
                        rawHeaders.append((char) next);
                    }
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (String line : rawHeaders.toString().split("\r\n")) {
                        int separator = line.indexOf(':');
                        if (separator > 0) {
                            headers.put(line.substring(0, separator).toLowerCase(Locale.ROOT),
                                    line.substring(separator + 1).trim());
                        }
                    }
                    int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    String body = new String(input.readNBytes(contentLength), StandardCharsets.UTF_8);
                    // A Go JSON decoder consumes one object, not trailing chunk framing. A
                    // known Content-Length lets net/http observe client cancellation while
                    // the handler waits for inference without writing response headers.
                    long waitingStarted = System.nanoTime();
                    int afterBody = input.read();
                    return new PendingOllamaRequest(headers, body, afterBody,
                            Duration.ofNanos(System.nanoTime() - waitingStarted));
                }
            });

            ChatClient client = localFactory(server.getLocalPort(), 1)
                    .create(new UserLlmRuntimeSettings("ollama", "local-test-model", null, null, null, null),
                            "local-test-model", 0.0);
            assertThatThrownBy(() -> client.prompt().user("Entrada sintética: á 文").call().content())
                    .isInstanceOf(ResourceAccessException.class);

            PendingOllamaRequest request = observedRequest.get(5, TimeUnit.SECONDS);
            assertThat(request.headers()).containsKey("content-length").doesNotContainKey("transfer-encoding");
            assertThat(Integer.parseInt(request.headers().get("content-length")))
                    .isEqualTo(request.body().getBytes(StandardCharsets.UTF_8).length);
            assertThat(request.body()).contains("Entrada sintética: á 文");
            assertThat(request.afterBody()).isEqualTo(-1);
            assertThat(request.waiting()).isBetween(Duration.ofMillis(700), Duration.ofSeconds(3));
        }
    }

    private record PendingOllamaRequest(Map<String, String> headers, String body, int afterBody,
            Duration waiting) {}

    @Test
    @Timeout(10)
    void ollamaDeadlineAlsoStopsABodyThatStallsAfterImmediateHeaders() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"model":"local-test-model","message":{"role":"assistant","content":"respuesta"},"done":true}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2000);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ChatClient client = localFactory(server.getAddress().getPort(), 1)
                    .create(new UserLlmRuntimeSettings("ollama", "local-test-model", null, null, null, null),
                            "local-test-model", 0.0);
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.prompt().user("Synthetic input").call().content())
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isBetween(Duration.ofMillis(800), Duration.ofMillis(1800));
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void completesTheSameSlowResponseWithALongerConfiguredTimeout() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = slowOllamaServer(requests, 2);

        try {
            ChatClient client = localFactory(server.getAddress().getPort(), 4)
                    .create(new UserLlmRuntimeSettings("ollama", "local-test-model", null, null, null, null),
                            "local-test-model", 0.0);

            assertThat(client.prompt().user("Reply with OK.").call().content())
                    .isEqualTo("respuesta local");
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer slowOllamaServer(AtomicInteger requests, int delaySeconds) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(Duration.ofSeconds(delaySeconds).toMillis());
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
        return server;
    }

    private ChatClient ollamaClient(int port) {
        return localFactory(port)
                .create(new UserLlmRuntimeSettings("ollama", "local-test-model", null, null, null, null),
                        "local-test-model", 0.0);
    }

    private LlmProviderClientFactory localFactory(int port) {
        return localFactory(port, 600);
    }

    private LlmProviderClientFactory localFactory(int port, long timeoutSeconds) {
        return new LlmProviderClientFactory(
                "http://127.0.0.1:" + port, "http://127.0.0.1:1/v1", timeoutSeconds);
    }

    private ChatClient create(String provider, String model, String apiKey) {
        return factory.create(
                new UserLlmRuntimeSettings(
                        provider, model, null, null, null, apiKey),
                model,
                0.2);
    }
}
