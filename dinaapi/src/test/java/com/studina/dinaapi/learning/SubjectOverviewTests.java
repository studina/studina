package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

/**
 * La vista de progreso de una materia entera.
 *
 * <p>Lo que la distingue de {@code topicProgress} es que parte del catálogo de topics: un tema
 * que nadie practicó tiene que aparecer, y tiene que aparecer como "sin empezar" y no como 0%,
 * sin arrastrar el promedio hacia abajo. Esa es la regresión que cuidan estos tests.
 */
class SubjectOverviewTests {

    private HttpServer server;

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            exchange.getRequestBody().readAllBytes();

            String body;
            if (path.endsWith("/auth/login")) {
                body = "{\"data\":{\"access_token\":\"admin-token\"}}";
            } else if ("GET".equals(method) && path.endsWith("/items/topics")) {
                body = """
                        {"data":[
                          {"id":"topic-1","name":"Consistencia","description":"Fuerte y eventual"},
                          {"id":"topic-2","name":"Eleccion de lider","description":null},
                          {"id":"topic-3","name":"Relojes logicos","description":null}
                        ]}
                        """;
            } else if ("GET".equals(method) && path.endsWith("/items/user_topic_progress")) {
                // Sólo topic-1 tiene evidencia. topic-2 no tiene fila y topic-3 tiene una
                // en cero, como la que deja el recálculo completo al borrar un set.
                body = """
                        {"data":[
                          {"topic_id":"topic-1","mastery_score":0.8,
                           "unique_resources_seen":1,"total_attempts":3,
                           "last_seen_at":"2026-01-02T10:00:00Z"},
                          {"topic_id":"topic-3","mastery_score":0.0,
                           "unique_resources_seen":0,"total_attempts":0,
                           "last_seen_at":null}
                        ]}
                        """;
            } else if ("GET".equals(method) && path.endsWith("/items/flashcard_cards")) {
                body = """
                        {"data":[
                          {"id":"card-a","topic_id":"topic-1"},
                          {"id":"card-b","topic_id":"topic-1"},
                          {"id":"card-c","topic_id":"topic-2"}
                        ]}
                        """;
            } else if ("GET".equals(method) && path.endsWith("/items/file_topics")) {
                // file-9 quedó huérfano: su archivo ya no existe en Directus.
                body = """
                        {"data":[
                          {"file_id":"file-1","topic_id":"topic-1"},
                          {"file_id":"file-2","topic_id":"topic-2"},
                          {"file_id":"file-9","topic_id":"topic-2"}
                        ]}
                        """;
            } else if ("GET".equals(method) && path.endsWith("/files")) {
                // file-2 no tiene filename_download; el nombre sale del título.
                body = """
                        {"data":[
                          {"id":"file-1","filename_download":"Apunte_redes.pdf","title":"Apunte"},
                          {"id":"file-2","filename_download":"","title":"Practica.docx"}
                        ]}
                        """;
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

    private Map<String, Object> overview() {
        DirectusAuthorizationService authorizationService = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(authorizationService, "directusUrl", directusUrl());
        ReflectionTestUtils.setField(authorizationService, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(authorizationService, "adminPassword", "secret");

        LearningAnalyticsService analytics = new LearningAnalyticsService(
                authorizationService,
                new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30, 0.70, 0.40),
                null);
        ReflectionTestUtils.setField(analytics, "directusUrl", directusUrl());
        return analytics.subjectOverview("user-1", "subject-1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> topic(Map<String, Object> overview, String topicId) {
        return ((List<Map<String, Object>>) overview.get("topics")).stream()
                .filter(row -> topicId.equals(row.get("topic_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Falta el topic " + topicId));
    }

    @Test
    void anUnpracticedTopicIsListedWithoutMasteryInsteadOfBeingLeftOut() {
        Map<String, Object> overview = overview();

        assertThat(overview.get("total_topics")).isEqualTo(3);
        assertThat(overview.get("practiced_topics")).isEqualTo(1);

        Map<String, Object> unpracticed = topic(overview, "topic-2");
        assertThat(unpracticed.get("mastery_score")).isNull();
        assertThat(unpracticed.get("level")).isNull();
        assertThat(unpracticed.get("unique_resources_seen")).isEqualTo(0);
        assertThat(unpracticed.get("last_seen_at")).isNull();
    }

    @Test
    void theAverageIsOverEveryTopicOfTheSubjectCountingUnpracticedOnesAsZero() {
        Map<String, Object> overview = overview();

        // Un solo topic practicado al 0.8 sobre los tres de la materia.
        assertThat((Double) overview.get("average_mastery")).isCloseTo(0.8 / 3, within(1e-9));
        assertThat(overview.get("strong_topics")).isEqualTo(1);
        assertThat(overview.get("total_attempts")).isEqualTo(3);
        assertThat(overview.get("last_seen_at")).isEqualTo("2026-01-02T10:00:00Z");
    }

    @Test
    void masteryIsClassifiedSoTheUiCanGroupTopics() {
        assertThat(topic(overview(), "topic-1").get("level")).isEqualTo("strong");
    }

    @Test
    void coverageComparesWhatWasPracticedAgainstTheResourcesOfEachTopic() {
        Map<String, Object> overview = overview();

        // topic-1 tiene dos tarjetas y se practicó una.
        assertThat(topic(overview, "topic-1").get("coverage")).isEqualTo(0.5);
        assertThat(topic(overview, "topic-1").get("total_resources")).isEqualTo(2);
        // topic-3 no tiene recursos: la cobertura es 0, no una división por cero.
        assertThat(topic(overview, "topic-3").get("coverage")).isEqualTo(0.0);

        // El agregado de la materia trae los dos lados de la cuenta, no sólo el cociente.
        assertThat(overview.get("resources_seen")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachTopicCarriesTheFilesItWasDetectedFrom() {
        Map<String, Object> overview = overview();

        List<Map<String, Object>> files = (List<Map<String, Object>>) topic(overview, "topic-1").get("files");
        assertThat(files).containsExactly(Map.of("id", "file-1", "filename", "Apunte_redes.pdf"));

        // El nombre cae al título cuando no hay filename_download, y la asociación de un
        // archivo ya borrado no inventa una entrada sin nombre.
        List<Map<String, Object>> withTitleOnly = (List<Map<String, Object>>) topic(overview, "topic-2").get("files");
        assertThat(withTitleOnly).containsExactly(Map.of("id", "file-2", "filename", "Practica.docx"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTopicWithoutFilesGetsAnEmptyListAndNotNull() {
        List<Map<String, Object>> files = (List<Map<String, Object>>) topic(overview(), "topic-3").get("files");
        assertThat(files).isEmpty();
    }

    @Test
    void aRowWithoutAttemptsCountsAsUnstartedAndNotAsZeroMastery() {
        // El recálculo completo de la materia crea filas en cero para los topics que nadie
        // practicó. Tomarlas como evidencia mostraba un tema virgen como "necesita repaso"
        // al 0% y lo contaba entre los practicados.
        Map<String, Object> overview = overview();

        Map<String, Object> zeroRow = topic(overview, "topic-3");
        assertThat(zeroRow.get("mastery_score")).isNull();
        assertThat(zeroRow.get("level")).isNull();

        assertThat(overview.get("practiced_topics")).isEqualTo(1);
    }
}
