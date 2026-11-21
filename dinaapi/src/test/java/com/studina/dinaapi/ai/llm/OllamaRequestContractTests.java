package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;
import com.sun.net.httpserver.HttpServer;

@Timeout(20)
class OllamaRequestContractTests {

    @Test
    void sendsNativeSchemaAndOutputLimitsForSequentialResourceRequests() throws Exception {
        runWithServer((client, bodies, maxActive) -> {
            for (String collection : List.of("cards", "questions", "statements")) {
                client.generate(LlmRequest.of("Sistema original", "Usuario original")
                        .forUser("a").forResourceGeneration()
                        .withJsonSchema(Map.of("type", "object", "required", List.of(collection))));
            }
            assertThat(bodies).hasSize(3);
            assertThat(maxActive.get()).isEqualTo(1);
            for (int index = 0; index < bodies.size(); index++) {
                JsonNode body = bodies.get(index);
                assertThat(body.path("format").path("required").get(0).asText())
                        .isEqualTo(List.of("cards", "questions", "statements").get(index));
                assertThat(body.path("model").asText()).isEqualTo("generation-a");
                assertThat(body.path("options").path("num_ctx").asInt()).isEqualTo(4096);
                assertThat(body.path("options").path("num_predict").asInt())
                        .isEqualTo(GenerationContextBudget.MAX_OUTPUT_TOKENS);
                assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Sistema original");
                assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("Usuario original");
            }
        });
    }

    @Test
    void serializesThreeUsersAndModelsAndSendsNativeSchemaWithoutChangingPrompts() throws Exception {
        runWithServer((client, bodies, maxActive) -> {
            CyclicBarrier start = new CyclicBarrier(3);
            try (var workers = Executors.newFixedThreadPool(3)) {
                var results = new ArrayList<java.util.concurrent.Future<LlmResponse>>();
                for (String user : List.of("a", "b", "c")) {
                    results.add(workers.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return client.generate(LlmRequest.of("Sistema original", "Usuario original")
                                .forUser(user).forResourceGeneration()
                                .withJsonSchema(Map.of("type", "object", "required", List.of("cards"))));
                    }));
                }
                for (var result : results) {
                    assertThat(result.get(10, TimeUnit.SECONDS).content()).isEqualTo("{}");
                }
            }
            assertThat(maxActive.get()).isEqualTo(1);
            assertThat(bodies).hasSize(3);
            for (JsonNode body : bodies) {
                assertThat(body.path("format").path("required").get(0).asText()).isEqualTo("cards");
                assertThat(body.path("options").path("num_ctx").asInt()).isEqualTo(4096);
                assertThat(body.path("options").path("num_batch").asInt()).isEqualTo(128);
                assertThat(body.path("options").path("num_predict").asInt())
                        .isEqualTo(GenerationContextBudget.MAX_OUTPUT_TOKENS);
                assertThat(body.path("keep_alive").asText()).isEqualTo("0s");
                assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Sistema original");
                assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("Usuario original");
            }
        });
    }

    @Test
    void releasesTheOllamaSlotWhenTheProviderFails() throws Exception {
        runWithServer((client, bodies, maxActive) -> {
            assertThatThrownBy(() -> client.generate(LlmRequest.of("System", "fail").forUser("a")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(client.generate(LlmRequest.of("System", "ok").forUser("b")).content()).isEqualTo("{}");
            assertThat(bodies).hasSize(2);
        });
    }

    @Test
    void chatClassificationSummaryAndEvaluationDoNotReceiveTheResourceProfile() throws Exception {
        runWithServer((client, bodies, maxActive) -> {
            for (LlmUseCase useCase : LlmUseCase.values()) {
                client.generate(LlmRequest.of("Original system", "Original user")
                        .forUser("a").forUseCase(useCase).withJsonResponse());
            }
            assertThat(bodies).hasSize(4);
            for (JsonNode body : bodies) {
                assertThat(body.path("format").asText()).isEqualTo("json");
                for (String field : List.of("num_ctx", "num_predict", "num_batch", "num_thread")) {
                    assertThat(body.path("options").hasNonNull(field)).as(field).isFalse();
                }
                assertThat(body.hasNonNull("keep_alive")).isFalse();
                assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Original system");
                assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("Original user");
            }
        });
    }

    private void runWithServer(Check check) throws Exception {
        var bodies = new CopyOnWriteArrayList<JsonNode>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newFixedThreadPool(3)) {
            server.setExecutor(handlers);
            server.createContext("/api/chat", exchange -> {
                JsonNode body = mapper.readTree(exchange.getRequestBody());
                bodies.add(body);
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    // Simula una inferencia en curso para detectar solapamientos reales por HTTP.
                    Thread.sleep(75);
                    boolean fail = body.path("messages").get(1).path("content").asText().equals("fail");
                    byte[] response = (fail ? "{\"error\":\"test failure\"}" : """
                            {"model":"test-model","message":{"role":"assistant","content":"{}"},"done":true}
                            """).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(fail ? 500 : 200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                    exchange.close();
                }
            });
            server.start();
            try {
                String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
                var settings = mock(UserLlmSettingsService.class);
                for (String user : List.of("a", "b", "c")) {
                    when(settings.runtimeFor(user)).thenReturn(Optional.of(new UserLlmRuntimeSettings(
                            "ollama", "chat-" + user, "generation-" + user, null, null, null)));
                }
                var client = new SpringAiLlmClient(mock(ChatClient.class), new LlmProperties(), settings,
                        new LlmProviderClientFactory(baseUrl, baseUrl + "/v1", 5));
                check.run(client, bodies, maxActive);
            } finally {
                server.stop(0);
            }
        }
    }

    private interface Check {
        void run(SpringAiLlmClient client, List<JsonNode> bodies, AtomicInteger maxActive) throws Exception;
    }
}
