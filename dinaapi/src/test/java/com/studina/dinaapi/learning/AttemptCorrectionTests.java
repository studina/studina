package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.studina.dinaapi.ai.rag.Flashcard;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

/**
 * Corregir una calificación edita el intento en su lugar, en vez de agregar uno nuevo.
 *
 * <p>La diferencia no es cosmética. El mastery se reconstruye reproduciendo el historial, así
 * que calificar de nuevo aplica el EMA dos veces: un {@code 0} seguido de un {@code 4} deja
 * {@code 0.40}, mientras que corregir ese {@code 0} a {@code 4} deja {@code 0.70}, que es lo
 * que la persona realmente sabía. El stub aplica el PATCH sobre su estado para que el replay
 * posterior lea la nota corregida, igual que haría Directus.
 */
class AttemptCorrectionTests {

    private static final String ATTEMPT_ID = "11111111-1111-1111-1111-111111111111";

    private HttpServer server;
    private final AtomicInteger storedRating = new AtomicInteger(0);
    private final List<String> patchBodies = new ArrayList<>();

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            String body;
            if (path.endsWith("/auth/login")) {
                body = "{\"data\":{\"access_token\":\"admin-token\"}}";
            } else if (path.contains("/items/flashcard_attempts/") && "PATCH".equals(method)) {
                // El stub se comporta como Directus: guarda la nota nueva para que el
                // replay posterior la lea.
                patchBodies.add(requestBody);
                storedRating.set(Integer.parseInt(requestBody.replaceAll("\\D+", "")));
                body = "{\"data\":{\"id\":\"row-1\"}}";
            } else if (path.endsWith("/items/flashcard_attempts")) {
                body = """
                        {"data":[{"id":"row-1","attempt_id":"%s","card_id":"card-a",
                          "subject_id":"subject-1","flashcard_set_id":"set-1",
                          "created_at":"2026-01-01T10:00:00Z","rating":%d}]}
                        """.formatted(ATTEMPT_ID, storedRating.get());
            } else if (path.endsWith("/items/user_resource_progress") && "GET".equals(method)) {
                body = "{\"data\":[]}";
            } else if (path.endsWith("/items/topics")) {
                body = "{\"data\":[]}";
            } else if ("POST".equals(method) || "PATCH".equals(method)) {
                // Directus devuelve el item guardado, no un eco vacío: el servicio usa
                // esa respuesta como el agregado que entrega al controller.
                body = "{\"data\":" + requestBody + "}";
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

    private FlashcardProgressService service() {
        String directusUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        LearningProgressPolicy policy = new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30, 0.70, 0.40);

        DirectusAuthorizationService authorizationService = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(authorizationService, "directusUrl", directusUrl);
        ReflectionTestUtils.setField(authorizationService, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(authorizationService, "adminPassword", "secret");

        ResourceProgressService resourceProgressService = new ResourceProgressService(policy);
        ReflectionTestUtils.setField(resourceProgressService, "directusUrl", directusUrl);

        LearningAnalyticsService analytics = new LearningAnalyticsService(authorizationService, policy, null);
        ReflectionTestUtils.setField(analytics, "directusUrl", directusUrl);

        FlashcardProgressService service = new FlashcardProgressService(
                authorizationService, policy, resourceProgressService, analytics);
        ReflectionTestUtils.setField(service, "directusUrl", directusUrl);
        return service;
    }

    private static Flashcard card() {
        return new Flashcard("card-a", "término", "definición", "topic-1");
    }

    @Test
    void theCorrectedRatingReplacesTheOriginalInsteadOfStackingOnIt() {
        Map<String, Object> progress = service().updateAttempt(
                "user-1", "subject-1", "set-1", card(), ATTEMPT_ID, 4);

        // Un único intento con rating 4: 1.0 × 0.70. Si se hubiera agregado un intento
        // nuevo en vez de corregir, el replay de [0, 4] daría 0.40.
        assertThat((Double) progress.get("mastery_score")).isEqualTo(0.70);
        assertThat((Integer) progress.get("times_seen")).isEqualTo(1);
    }

    @Test
    void onlyTheRatingIsSentSoTheHistoryKeepsItsOrder() {
        service().updateAttempt("user-1", "subject-1", "set-1", card(), ATTEMPT_ID, 3);

        assertThat(patchBodies).hasSize(1);
        assertThat(patchBodies.get(0)).contains("rating");
        assertThat(patchBodies.get(0))
                .as("created_at ordena el replay: moverlo cambiaría el mastery por un motivo ajeno")
                .doesNotContain("created_at");
    }

    @Test
    void rejectsAnAttemptThatBelongsToAnotherCard() {
        Flashcard otherCard = new Flashcard("card-z", "otro", "otro", "topic-1");

        assertThatThrownBy(() -> service().updateAttempt(
                "user-1", "subject-1", "set-1", otherCard, ATTEMPT_ID, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no corresponde");
    }

    @Test
    void rejectsARatingOutOfRange() {
        assertThatThrownBy(() -> service().updateAttempt(
                "user-1", "subject-1", "set-1", card(), ATTEMPT_ID, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
