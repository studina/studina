package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;
import com.sun.net.httpserver.HttpServer;

class OpenAiRequestContractTests {

    @ParameterizedTest(name = "model={0}, json={1}")
    @MethodSource("modelsAndResponseModes")
    @Timeout(15)
    void sendsCompatibleTemperatureAndResponseFormatOverHttp(String model, boolean jsonResponse)
            throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"local-test","object":"chat.completion","created":0,"model":"local-test",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"{}"},
                                 "finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            String localBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            LlmProviderClientFactory factory = new LlmProviderClientFactory(
                    localBaseUrl, localBaseUrl + "/v1", 10);
            UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
            when(settingsService.runtimeFor("local-test-user")).thenReturn(Optional.of(
                    new UserLlmRuntimeSettings("openai", model, model, null, null, "fake-test-key")));
            LlmProperties properties = new LlmProperties();
            properties.setTemperature(0.2);
            ChatClient unusedDefaultClient = mock(ChatClient.class);
            SpringAiLlmClient client = new SpringAiLlmClient(
                    unusedDefaultClient, properties, settingsService, factory);
            LlmRequest request = LlmRequest.of("Respond briefly", "Return a JSON object")
                    .forUser("local-test-user");
            if (jsonResponse) {
                request = request.forUseCase(LlmUseCase.GENERATION).withJsonResponse();
            }

            assertThat(client.generate(request).content()).isEqualTo("{}");

            JsonNode body = new ObjectMapper().readTree(capturedBody.get());
            assertThat(body.path("model").asText()).isEqualTo(model);
            if ("gpt-4.1-mini".equals(model)) {
                assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
            } else {
                assertThat(body.has("temperature")).isFalse();
            }
            if (jsonResponse) {
                assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
            } else {
                assertThat(body.has("response_format")).isFalse();
            }
            verifyNoInteractions(unusedDefaultClient);
        } finally {
            server.stop(0);
        }
    }

    private static Stream<Arguments> modelsAndResponseModes() {
        return Stream.of("gpt-5", "gpt-6-luna", "o1-mini", "o3-mini", "o4-mini", "gpt-4.1-mini")
                .flatMap(model -> Stream.of(Arguments.of(model, false), Arguments.of(model, true)));
    }
}
