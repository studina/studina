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
import com.studina.dinaapi.ai.rag.Flashcard;
import com.studina.dinaapi.directus.FlashcardSetService;

/**
 * {@code GET /flashcards/{setId}/review-round} devuelve la ronda entera en una sola respuesta,
 * que es lo que le permite al frontend estudiarla sin pedir nada entre tarjeta y tarjeta.
 * Este test cubre el armado completo contra Directus: que las tarjetas practicadas y flojas se
 * repitan y que las que no tienen historial entren una sola vez.
 */
class ReviewRoundTests {

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
    @SuppressWarnings("unchecked")
    void buildsTheWholeRoundRepeatingOnlyTheCardsWithEvidenceOfBeingWeak() {
        String directusUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        DirectusAuthorizationService authorizationService = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(authorizationService, "directusUrl", directusUrl);
        ReflectionTestUtils.setField(authorizationService, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(authorizationService, "adminPassword", "secret");
        FlashcardSetService flashcardSetService = new FlashcardSetService(authorizationService);
        ReflectionTestUtils.setField(flashcardSetService, "directusUrl", directusUrl);
        LearningAnalyticsService analytics = new LearningAnalyticsService(
                authorizationService,
                new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30),
                flashcardSetService);
        ReflectionTestUtils.setField(analytics, "directusUrl", directusUrl);

        Map<String, Object> round = analytics.reviewRound("user-1", "subject-1", "set-1");
        List<String> ids = ((List<Flashcard>) round.get("cards")).stream()
                .map(Flashcard::id)
                .toList();

        // card-b no tiene intentos: va adelante como material nuevo y entra una sola vez.
        // card-a tiene mastery 0.42, evidencia de que está floja, así que se repite.
        assertThat(ids).containsExactly("card-b", "card-a", "card-a");
    }
}
