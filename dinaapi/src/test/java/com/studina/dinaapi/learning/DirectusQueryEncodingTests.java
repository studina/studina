package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression: the services pre-encode their URLs with {@code UriComponentsBuilder.encode()}.
 * {@code RestClient.uri(String)} treats a String as a URI template and encodes it again, so a
 * pre-encoded URL arrives double-encoded and Directus sees a query param literally named
 * {@code filter%5Buser_id%5D%5B_eq%5D}, which it ignores. The filter is silently dropped and the
 * query returns the first row of the whole collection instead of the requested one.
 *
 * <p>Passing a {@code URI} instead keeps the encoding at one level. This test runs the real
 * RestClient against a stub server and asserts what Directus would actually receive.
 */
class DirectusQueryEncodingTests {

    private HttpServer server;
    private final List<String> receivedQueries = new ArrayList<>();

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/items", exchange -> {
            // getQuery() decodes exactly once, which is what a correct request looks like on the
            // server side. A double-encoded request still shows %5B here.
            receivedQueries.add(String.valueOf(exchange.getRequestURI().getQuery()));
            byte[] body = responseFor(exchange.getRequestMethod(), exchange.getRequestURI().getPath())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private static String responseFor(String method, String path) {
        if (!"GET".equals(method)) {
            return "{\"data\":{\"id\":\"created\"}}";
        }
        if (path.endsWith("/flashcard_attempts")) {
            return """
                    {"data":[{"id":"attempt-1","created_at":"2026-09-27T10:00:00Z","rating":4}]}
                    """;
        }
        if (path.endsWith("/user_resource_progress")) {
            return "{\"data\":[]}";
        }
        return "{\"data\":{\"id\":\"created\"}}";
    }

    @AfterEach
    void stopStubDirectus() {
        server.stop(0);
    }

    @Test
    void sendsFiltersDirectusCanReadInsteadOfDoubleEncodingThem() {
        ResourceProgressService service = new ResourceProgressService(
                new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30));
        ReflectionTestUtils.setField(service, "directusUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());

        service.recalculate("token", "user-1", "subject-1",
                ResourceProgressService.FLASHCARD, "card-1");

        assertThat(receivedQueries).isNotEmpty();
        assertThat(receivedQueries).allSatisfy(query -> assertThat(query)
                .as("Directus recibe los corchetes del filtro sin un segundo nivel de encoding")
                .doesNotContain("%5B")
                .doesNotContain("%5D"));

        assertThat(receivedQueries.get(0))
                .contains("filter[user_id][_eq]=user-1")
                .contains("filter[card_id][_eq]=card-1");
        assertThat(receivedQueries.get(1))
                .contains("filter[resource_id][_eq]=card-1")
                .contains("filter[resource_type][_eq]=FLASHCARD");
    }
}
