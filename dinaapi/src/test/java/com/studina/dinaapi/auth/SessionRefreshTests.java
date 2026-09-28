package com.studina.dinaapi.auth;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * La sesión se renueva sin volver a pedir la contraseña.
 *
 * <p>El access token de Directus dura 15 minutos. Antes el frontend recibía sólo ese token,
 * así que una pantalla abierta más de ese rato empezaba a recibir 401: en flashcards, donde
 * las autoevaluaciones se escriben en segundo plano, la ronda terminaba con la mitad de los
 * intentos sin guardar. Para que el navegador pueda renovar necesita dos cosas del login —el
 * refresh token y cuánto dura el de acceso— y un endpoint donde canjearlos.
 */
class SessionRefreshTests {

    private HttpServer server;
    private final List<String> refreshBodies = new ArrayList<>();
    private volatile boolean refreshTokenAccepted = true;

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String requestBody =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            int status = 200;
            String body;
            if (path.endsWith("/auth/refresh")) {
                refreshBodies.add(requestBody);
                if (refreshTokenAccepted) {
                    body = """
                            {"data":{"access_token":"token-nuevo",
                              "refresh_token":"refresh-2","expires":900000}}
                            """;
                } else {
                    // Lo que devuelve Directus con un refresh token vencido o ya usado.
                    status = 401;
                    body = "{\"errors\":[{\"message\":\"Invalid user credentials.\"}]}";
                }
            } else if (path.endsWith("/auth/login")) {
                body = """
                        {"data":{"access_token":"token-1",
                          "refresh_token":"refresh-1","expires":900000}}
                        """;
            } else if (path.endsWith("/users/me")) {
                body = "{\"data\":{\"id\":\"user-1\"}}";
            } else if (path.contains("/users/")) {
                body = """
                        {"data":{"id":"user-1","first_name":"Ana","last_name":"Diaz",
                          "email":"ana@example.com","role":"role-1"}}
                        """;
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

    private AuthController controller() {
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(
                controller, "directusUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(controller, "adminEmail", "admin@example.com");
        ReflectionTestUtils.setField(controller, "adminPassword", "admin");
        ReflectionTestUtils.setField(controller, "userRoleName", "Studina User");
        return controller;
    }

    @Test
    void loginEntregaLoNecesarioParaRenovar() {
        ResponseEntity<?> response =
                controller().login(new LoginRequest("ana@example.com", "clave"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOfSatisfying(AuthResponse.class, body -> {
            assertThat(body.token()).isEqualTo("token-1");
            assertThat(body.refreshToken()).isEqualTo("refresh-1");
            assertThat(body.expiresIn()).isEqualTo(900_000L);
        });
    }

    @Test
    void renuevaLaSesionConElRefreshToken() {
        ResponseEntity<?> response = controller().refresh(new RefreshRequest("refresh-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOfSatisfying(RefreshResponse.class, body -> {
            assertThat(body.token()).isEqualTo("token-nuevo");
            // Directus rota el refresh token: el frontend tiene que quedarse con el nuevo.
            assertThat(body.refreshToken()).isEqualTo("refresh-2");
            assertThat(body.expiresIn()).isEqualTo(900_000L);
        });
        assertThat(refreshBodies).singleElement().asString().contains("refresh-1");
    }

    /**
     * Un refresh token que Directus rechaza es una sesión terminada, no una falla del
     * servidor: el frontend distingue por el código para mandar al login en vez de
     * reintentar.
     */
    @Test
    void unRefreshTokenRechazadoTermina401() {
        refreshTokenAccepted = false;

        ResponseEntity<?> response = controller().refresh(new RefreshRequest("refresh-vencido"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isInstanceOfSatisfying(Map.class, body ->
                assertThat(body.get("error")).asString().contains("sesión"));
    }

    @Test
    void sinRefreshTokenNiSiquieraLlamaADirectus() {
        ResponseEntity<?> response = controller().refresh(new RefreshRequest("  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshBodies).isEmpty();
    }
}
