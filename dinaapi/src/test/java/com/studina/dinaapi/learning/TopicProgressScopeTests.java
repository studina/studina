package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.studina.dinaapi.ai.rag.Flashcard;
import com.studina.dinaapi.ai.rag.TrueFalseStatement;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

/**
 * Cuánto trabajo genera calificar una tarjeta, y cuánto leer el progreso.
 *
 * <p>Calificar cambia el mastery de un recurso, y un recurso pertenece a un único topic, así
 * que reconstruir los agregados de toda la materia sería reescribir filas con el valor que ya
 * tenían. Leer el progreso, del otro lado, no necesita contar los recursos de cada topic salvo
 * que alguien pida la cobertura. Los dos son costos que sólo se ven contando las requests que
 * llegan a Directus, así que el stub las registra.
 */
class TopicProgressScopeTests {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            String method = exchange.getRequestMethod();
            exchange.getRequestBody().readAllBytes();
            requests.add(method + " " + path);

            String body;
            if (path.endsWith("/auth/login")) {
                body = "{\"data\":{\"access_token\":\"admin-token\"}}";
            } else if ("GET".equals(method) && path.endsWith("/items/flashcard_attempts") && query.contains("attempt_id")) {
                // Búsqueda de idempotencia: el intento todavía no existe.
                body = "{\"data\":[]}";
            } else if ("GET".equals(method) && path.endsWith("/items/flashcard_attempts")) {
                // Historial para el replay del EMA.
                body = """
                        {"data":[{"id":"row-1","created_at":"2026-01-01T10:00:00Z","rating":4}]}
                        """;
            } else if ("GET".equals(method) && path.endsWith("/items/true_false_attempts") && query.contains("attempt_id")) {
                body = "{\"data\":[]}";
            } else if ("GET".equals(method) && path.endsWith("/items/true_false_attempts")) {
                body = """
                        {"data":[{"id":"row-1","created_at":"2026-01-01T10:00:00Z","is_correct":true}]}
                        """;
            } else if ("GET".equals(method) && path.endsWith("/items/flashcard_cards")) {
                body = "{\"data\":[{\"id\":\"card-a\",\"topic_id\":\"topic-1\"}]}";
            } else if ("GET".equals(method) && path.endsWith("/items/user_resource_progress")) {
                body = "{\"data\":[{\"resource_id\":\"card-a\",\"mastery_score\":0.7,"
                        + "\"times_seen\":1,\"last_seen_at\":\"2026-01-01T10:00:00Z\"}]}";
            } else if ("GET".equals(method) && path.endsWith("/items/topics")) {
                body = "{\"data\":[{\"id\":\"topic-1\",\"name\":\"Consistencia\"}]}";
            } else if ("GET".equals(method) && path.endsWith("/items/user_topic_progress")) {
                body = "{\"data\":[{\"id\":\"agg-1\",\"topic_id\":\"topic-1\","
                        + "\"mastery_score\":0.7,\"unique_resources_seen\":1,\"total_attempts\":1}]}";
            } else if ("POST".equals(method) || "PATCH".equals(method)) {
                body = "{\"data\":{\"id\":\"saved\"}}";
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

    private String directusUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private DirectusAuthorizationService authorization() {
        DirectusAuthorizationService authorizationService = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(authorizationService, "directusUrl", directusUrl());
        ReflectionTestUtils.setField(authorizationService, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(authorizationService, "adminPassword", "secret");
        return authorizationService;
    }

    private LearningAnalyticsService analytics(DirectusAuthorizationService authorizationService) {
        LearningAnalyticsService analytics = new LearningAnalyticsService(
                authorizationService,
                new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30, 0.70, 0.40),
                null);
        ReflectionTestUtils.setField(analytics, "directusUrl", directusUrl());
        return analytics;
    }

    @Test
    void ratingACardWritesOnlyTheAggregateOfItsOwnTopic() {
        DirectusAuthorizationService authorizationService = authorization();
        LearningProgressPolicy policy = new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30, 0.70, 0.40);
        ResourceProgressService resourceProgressService = new ResourceProgressService(policy);
        ReflectionTestUtils.setField(resourceProgressService, "directusUrl", directusUrl());

        FlashcardProgressService service = new FlashcardProgressService(
                authorizationService, policy, resourceProgressService, analytics(authorizationService));
        ReflectionTestUtils.setField(service, "directusUrl", directusUrl());

        service.recordAttempt(
                "user-1", "subject-1", "set-1",
                new Flashcard("card-a", "término", "definición", "topic-1"),
                new FlashcardAttemptRequest("card-a", 4, "11111111-1111-1111-1111-111111111111"));

        long topicWrites = requests.stream()
                .filter(request -> request.endsWith("/items/user_topic_progress")
                        || request.contains("/items/user_topic_progress/"))
                .filter(request -> request.startsWith("POST") || request.startsWith("PATCH"))
                .count();

        assertThat(topicWrites)
                .as("una calificación afecta a un solo topic: el de su tarjeta")
                .isEqualTo(1);

        assertThat(requests)
                .as("no hace falta el catálogo de topics de la materia para recalcular uno solo")
                .noneMatch(request -> request.equals("GET " + "/items/topics"));
    }

    @Test
    void answeringATrueFalseStatementReplaysItsHistoryAndWritesOnlyItsTopic() {
        DirectusAuthorizationService authorizationService = authorization();
        LearningProgressPolicy policy = new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30, 0.70, 0.40);
        ResourceProgressService resourceProgressService = new ResourceProgressService(policy);
        ReflectionTestUtils.setField(resourceProgressService, "directusUrl", directusUrl());

        TrueFalseProgressService service = new TrueFalseProgressService(
                authorizationService, resourceProgressService, analytics(authorizationService));
        ReflectionTestUtils.setField(service, "directusUrl", directusUrl());

        service.recordAnswer(
                "user-1", "subject-1", "set-1",
                new TrueFalseStatement("statement-a", "Afirmación", true, "Porque sí", "topic-1"),
                new TrueFalseAttemptRequest("statement-a", true, "33333333-3333-3333-3333-333333333333"));

        assertThat(requests)
                .as("el intento se guarda en su propia colección")
                .contains("POST /items/true_false_attempts")
                .as("el EMA se reconstruye desde el historial de verdadero o falso")
                .anyMatch(request -> request.equals("GET /items/true_false_attempts"))
                .as("los enunciados cuentan como recursos del topic")
                .contains("GET /items/true_false_statements");

        long topicWrites = requests.stream()
                .filter(request -> request.endsWith("/items/user_topic_progress")
                        || request.contains("/items/user_topic_progress/"))
                .filter(request -> request.startsWith("POST") || request.startsWith("PATCH"))
                .count();
        assertThat(topicWrites).isEqualTo(1);
    }

    @Test
    void readingProgressWithoutCoverageDoesNotCountEveryResourceOfTheSubject() {
        List<Map<String, Object>> progress =
                analytics(authorization()).topicProgress("user-1", "subject-1", false);

        assertThat(progress).hasSize(1);
        assertThat(progress.get(0)).containsKey("mastery_score");
        assertThat(progress.get(0))
                .as("sin cobertura pedida, no se devuelven los derivados que obligan a contarlos")
                .doesNotContainKeys("coverage", "total_resources");

        assertThat(requests)
                .as("contar recursos obliga a listar todas las tarjetas y preguntas de la materia")
                .noneMatch(request -> request.endsWith("/items/flashcard_cards")
                        || request.endsWith("/items/quiz_questions")
                        || request.endsWith("/items/true_false_statements"));
    }

    @Test
    void readingProgressWithCoverageStillComputesIt() {
        List<Map<String, Object>> progress =
                analytics(authorization()).topicProgress("user-1", "subject-1", true);

        assertThat(progress).hasSize(1);
        // Un recurso en el topic, uno practicado: cobertura completa.
        assertThat(progress.get(0).get("total_resources")).isEqualTo(1);
        assertThat((Double) progress.get(0).get("coverage")).isEqualTo(1.0);
    }
}
