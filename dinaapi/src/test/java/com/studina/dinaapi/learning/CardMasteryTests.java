package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.directus.FlashcardSetService;

/**
 * {@code GET /flashcards/{setId}/mastery} alimenta la ronda de refuerzo del frontend: la UI
 * filtra por mastery, así que toda tarjeta del set tiene que venir en la respuesta, incluidas
 * las que nunca se practicaron. Si una tarjeta sin historial faltara, la UI la trataría como
 * inexistente en vez de como la más débil.
 */
class CardMasteryTests {

    private HttpServer server;

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.endsWith("/auth/login")) {
                body = "{\"data\":{\"access_token\":\"admin-token\"}}";
            } else if (path.endsWith("/items/flashcard_cards")) {
                body = """
                        {"data":[
                          {"id":"card-a","flashcard_set_id":"set-1","term":"A","definition":"a","topic_id":"topic-1"},
                          {"id":"card-b","flashcard_set_id":"set-1","term":"B","definition":"b","topic_id":"topic-1"}
                        ]}
                        """;
            } else if (path.endsWith("/items/user_resource_progress")) {
                // Sólo card-a tiene historial.
                body = "{\"data\":[{\"resource_id\":\"card-a\",\"mastery_score\":0.42}]}";
            } else {
                body = "{\"data\":[]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStubDirectus() {
        server.stop(0);
    }

    @Test
    void returnsEveryCardOfTheSetAndDefaultsUnpracticedOnesToZero() {
        String directusUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        DirectusAuthorizationService authorizationService = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(authorizationService, "directusUrl", directusUrl);
        ReflectionTestUtils.setField(authorizationService, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(authorizationService, "adminPassword", "secret");
        FlashcardSetService flashcardSetService = new FlashcardSetService(authorizationService);
        ReflectionTestUtils.setField(flashcardSetService, "directusUrl", directusUrl);
        LearningAnalyticsService analytics = new LearningAnalyticsService(
                authorizationService,
                new LearningProgressPolicy(0.70, 0.60, 0.40, 3, 0.01),
                flashcardSetService);
        ReflectionTestUtils.setField(analytics, "directusUrl", directusUrl);

        List<Map<String, Object>> mastery = analytics.cardMastery("user-1", "subject-1", "set-1");

        assertThat(mastery).hasSize(2);
        assertThat(mastery).contains(Map.of("card_id", "card-a", "mastery_score", 0.42));
        assertThat(mastery)
                .as("una tarjeta sin intentos vale 0.0, el mismo default del repaso ponderado")
                .contains(Map.of("card_id", "card-b", "mastery_score", 0.0));
    }
}
