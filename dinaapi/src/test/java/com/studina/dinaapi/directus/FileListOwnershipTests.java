package com.studina.dinaapi.directus;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

/**
 * Listar los documentos de una materia ajena no devuelve nada.
 *
 * <p>Antes la protección era implícita: la carpeta de la materia se busca dentro de la del
 * usuario, así que una ajena simplemente no aparecía. No filtraba nada, pero dependía de cómo
 * estén anidadas las carpetas en Directus y no de una regla de autorización — si esa
 * estructura cambiaba, la protección se caía en silencio y ningún test lo notaba. Estos casos
 * fijan el comportamiento donde corresponde.
 */
class FileListOwnershipTests {

    /** Quién es el dueño de cada materia, según responde el Directus de mentira. */
    private static final Map<String, String> OWNERS = Map.of(
            "subject-de-a", "user-a",
            "subject-de-b", "user-b"
    );

    private HttpServer server;
    private final List<String> directusPaths = new ArrayList<>();

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            exchange.getRequestBody().readAllBytes();
            directusPaths.add(path);

            int status = 200;
            String body;
            if (path.endsWith("/auth/login")) {
                body = "{\"data\":{\"access_token\":\"admin-token\",\"expires\":900000}}";
            } else if (path.endsWith("/users/me")) {
                // El token del header identifica al usuario que hace el pedido.
                String token = exchange.getRequestHeaders().getFirst("Authorization");
                String userId = token != null && token.endsWith("token-b") ? "user-b" : "user-a";
                body = "{\"data\":{\"id\":\"" + userId + "\"}}";
            } else if (path.startsWith("/items/subjects/")) {
                String subjectId = path.substring(path.lastIndexOf('/') + 1);
                String owner = OWNERS.get(subjectId);
                if (owner == null) {
                    status = 404;
                    body = "{\"errors\":[{\"message\":\"Not found\"}]}";
                } else {
                    body = "{\"data\":{\"id\":\"" + subjectId + "\",\"user\":\"" + owner + "\"}}";
                }
            } else if (path.endsWith("/folders")) {
                // La carpeta del usuario y la de la materia; alcanza con devolver una.
                body = query.contains("parent%5D%5B_null%5D") || query.contains("parent][_null]")
                        ? "{\"data\":[{\"id\":\"folder-user\"}]}"
                        : "{\"data\":[{\"id\":\"folder-subject\"}]}";
            } else if (path.endsWith("/files")) {
                body = "{\"data\":[{\"id\":\"file-1\","
                        + "\"filename_download\":\"Apunte.pdf\",\"tags\":[]}]}";
            } else {
                body = "{\"data\":[]}";
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

    private String directusUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private FileController controller() {
        DirectusAuthorizationService authorizationService = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(authorizationService, "directusUrl", directusUrl());
        ReflectionTestUtils.setField(authorizationService, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(authorizationService, "adminPassword", "secret");

        // Listar documentos sólo usa la autorización y el cliente HTTP; el resto de las
        // dependencias no entra en este camino.
        FileController controller = new FileController(
                null, authorizationService, null, null, null);
        ReflectionTestUtils.setField(controller, "directusUrl", directusUrl());
        return controller;
    }

    @Test
    void listarLaMateriaPropiaDevuelveSusDocumentos() {
        ResponseEntity<Map<String, Object>> response =
                controller().listFiles("Bearer token-a", "subject-de-a");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("data")).asList().hasSize(1);
    }

    @Test
    void listarLaMateriaDeOtroUsuarioDaForbidden() {
        // Lo que importa: corta con 403 en vez de devolver una lista vacía, igual que el
        // resto de los endpoints que reciben un subjectId.
        assertThatThrownBy(() -> controller().listFiles("Bearer token-a", "subject-de-b"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void unaMateriaInexistenteTambienDaForbidden() {
        // 403 y no 404: si no, el endpoint serviría para averiguar qué materias existen.
        assertThatThrownBy(() -> controller().listFiles("Bearer token-a", "subject-fantasma"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void laMateriaAjenaNiSiquieraLlegaABuscarArchivos() {
        assertThatThrownBy(() -> controller().listFiles("Bearer token-a", "subject-de-b"))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(directusPaths).noneMatch(path -> path.endsWith("/folders"));
        assertThat(directusPaths).noneMatch(path -> path.endsWith("/files"));
    }
}
