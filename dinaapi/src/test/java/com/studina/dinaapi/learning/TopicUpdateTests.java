package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

/**
 * Editar un tema: sólo el dueño de la materia, y sin chocar con el nombre de otro tema.
 *
 * <p>La materia {@code subject-de-a} es de {@code user-a} y tiene dos temas, "Replicación"
 * ({@code topic-a}) y "Kafka" ({@code topic-kafka}).
 */
class TopicUpdateTests {

    private HttpServer server;
    /** Los PATCH que llegaron a Directus, como "path body". */
    private final List<String> patches = new ArrayList<>();

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            int status = 200;
            String body;
            if (path.endsWith("/auth/login")) {
                body = "{\"data\":{\"access_token\":\"admin-token\",\"expires\":900000}}";
            } else if (path.endsWith("/users/me")) {
                String token = exchange.getRequestHeaders().getFirst("Authorization");
                String userId = token != null && token.endsWith("token-b") ? "user-b" : "user-a";
                body = "{\"data\":{\"id\":\"" + userId + "\"}}";
            } else if (path.equals("/items/subjects/subject-de-a")) {
                body = "{\"data\":{\"id\":\"subject-de-a\",\"user\":\"user-a\"}}";
            } else if (path.equals("/items/topics/topic-a") && "PATCH".equals(method)) {
                patches.add(path + " " + requestBody);
                body = "{\"data\":{\"id\":\"topic-a\",\"subject_id\":\"subject-de-a\","
                        + "\"name\":\"Replicación de datos\",\"description\":\"Nueva\"}}";
            } else if (path.equals("/items/topics/topic-a")) {
                body = "{\"data\":{\"id\":\"topic-a\",\"subject_id\":\"subject-de-a\","
                        + "\"name\":\"Replicación\",\"description\":\"\"}}";
            } else if (path.equals("/items/topics")) {
                body = "{\"data\":["
                        + "{\"id\":\"topic-a\",\"subject_id\":\"subject-de-a\",\"name\":\"Replicación\",\"description\":\"\"},"
                        + "{\"id\":\"topic-kafka\",\"subject_id\":\"subject-de-a\",\"name\":\"Kafka\",\"description\":\"\"}"
                        + "]}";
            } else {
                // Lo que responde Directus a un item que no existe.
                status = 403;
                body = "{\"errors\":[{\"message\":\"Forbidden\"}]}";
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStubDirectus() {
        server.stop(0);
    }

    private TopicController controller() {
        String directusUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DirectusAuthorizationService authorizationService = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(authorizationService, "directusUrl", directusUrl);
        ReflectionTestUtils.setField(authorizationService, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(authorizationService, "adminPassword", "secret");

        // Editar no clasifica nada, así que el cliente de la IA no se usa.
        TopicCatalogService catalog = new TopicCatalogService(
                authorizationService, Mockito.mock(LlmClient.class));
        ReflectionTestUtils.setField(catalog, "directusUrl", directusUrl);
        return new TopicController(authorizationService, catalog);
    }

    private ResponseEntity<?> update(String token, String topicId, String name, String description) {
        return controller().update(
                topicId, new TopicController.UpdateTopicRequest(name, description), "Bearer " + token);
    }

    @Test
    void elDuenoRenombraElTemaYActualizaElNombreNormalizado() {
        ResponseEntity<?> response = update("token-a", "topic-a", " Replicación de datos ", "Nueva");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of(
                "topic_id", "topic-a",
                "topic_name", "Replicación de datos",
                "description", "Nueva"));
        assertThat(patches).singleElement().asString()
                .contains("\"name\":\"Replicación de datos\"")
                .contains("\"normalized_name\":\"replicacion de datos\"");
    }

    @Test
    void corregirUnAcentoDelPropioNombreNoEsUnConflicto() {
        ResponseEntity<?> response = update("token-a", "topic-a", "Replicacion", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patches).hasSize(1);
    }

    @Test
    void usarElNombreDeOtroTemaDaConflictoYNoEscribeNada() {
        ResponseEntity<?> response = update("token-a", "topic-a", "kafka", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(patches).isEmpty();
    }

    @Test
    void elTemaDeOtroUsuarioDaForbidden() {
        assertThatThrownBy(() -> update("token-b", "topic-a", "Hackeado", ""))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(patches).isEmpty();
    }

    @Test
    void unTemaInexistenteTambienDaForbidden() {
        ResponseEntity<?> response = update("token-a", "topic-fantasma", "Algo", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unNombreVacioDaBadRequestSinConsultarDirectus() {
        ResponseEntity<?> response = update("token-a", "topic-a", "   ", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patches).isEmpty();
    }
}
