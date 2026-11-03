package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.ai.llm.LlmRequest;
import com.studina.dinaapi.ai.llm.LlmResponse;
import com.studina.dinaapi.ai.llm.LlmUseCase;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.sun.net.httpserver.HttpServer;

class TopicClassificationProviderRoutingTests {

    @Test
    @Timeout(10)
    void documentClassificationRequestsJsonWithTheUsersClassificationSettings() throws Exception {
        AtomicReference<String> associationBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String body;
            if ("GET".equals(method) && "/items/topics".equals(path)) {
                body = """
                        {"data":[{"id":"topic-1","subject_id":"subject-1","name":"TCP",
                                  "description":"Control de congestión"}]}
                        """;
            } else if ("GET".equals(method) && "/items/file_topics".equals(path)) {
                body = "{\"data\":[]}";
            } else if ("POST".equals(method) && "/items/file_topics".equals(path)) {
                associationBody.set(requestBody);
                body = "{\"data\":{\"id\":\"association-1\"}}";
            } else {
                status = 404;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();

        try {
            DirectusAuthorizationService authorization = mock(DirectusAuthorizationService.class);
            when(authorization.getAdminToken()).thenReturn("fake-admin-token");
            LlmClient llmClient = mock(LlmClient.class);
            when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
                    "{\"existing_topic_ids\":[\"topic-1\"],\"new_topics\":[]}",
                    "ollama", "user-classification-model"));
            TopicCatalogService service = new TopicCatalogService(authorization, llmClient);
            ReflectionTestUtils.setField(service, "directusUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "classificationMaxAttempts", 1);
            String documentText = "TCP reduce su ventana cuando detecta congestión.";

            List<Topic> topics = service.detectAndAssociate("subject-1", "file-1", documentText, "user-1");

            ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
            verify(llmClient).generate(captured.capture());
            LlmRequest request = captured.getValue();
            assertThat(request.userId()).isEqualTo("user-1");
            assertThat(request.useCase()).isEqualTo(LlmUseCase.CLASSIFICATION);
            assertThat(request.jsonResponse()).isTrue();
            assertThat(request.userPrompt()).contains(documentText, "topic-1", "TCP");
            assertThat(topics).containsExactly(new Topic("topic-1", "subject-1", "TCP", "Control de congestión"));
            JsonNode association = new ObjectMapper().readTree(associationBody.get());
            assertThat(association.path("file_id").asText()).isEqualTo("file-1");
            assertThat(association.path("topic_id").asText()).isEqualTo("topic-1");
        } finally {
            server.stop(0);
        }
    }
}
