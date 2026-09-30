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
import org.springframework.http.HttpHeaders;
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
 *
 * <p>El refresh token no viaja en el cuerpo sino en una cookie {@code HttpOnly}: el
 * JavaScript no puede leerla, así que un XSS no tiene de dónde robarla. Eso es lo que
 * permite que recargar la página no cierre la sesión sin dejar una credencial expuesta.
 */
class SessionRefreshTests {

    private HttpServer server;
    private final List<String> refreshBodies = new ArrayList<>();
    private final List<String> logoutBodies = new ArrayList<>();
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
            } else if (path.endsWith("/auth/logout")) {
                logoutBodies.add(requestBody);
                body = "";
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
        ReflectionTestUtils.setField(controller, "cookiePath", "/api/auth");
        ReflectionTestUtils.setField(controller, "cookieSecure", true);
        ReflectionTestUtils.setField(controller, "cookieMaxAgeSeconds", 604800L);
        return controller;
    }

    /** La cookie que setea una respuesta, cruda, para poder mirarle los atributos. */
    private static String setCookie(ResponseEntity<?> response) {
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    }

    @Test
    void loginDejaElRefreshTokenEnUnaCookieInaccesibleParaElJavaScript() {
        ResponseEntity<?> response =
                controller().login(new LoginRequest("ana@example.com", "clave"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setCookie(response))
                .contains("studina_refresh=refresh-1")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax")
                .contains("Path=/api/auth");
    }

    @Test
    void elCuerpoDelLoginNoLlevaElRefreshToken() {
        ResponseEntity<?> response =
                controller().login(new LoginRequest("ana@example.com", "clave"));

        // Si volviera acá, cualquier XSS podría leerlo: es justamente lo que la cookie evita.
        assertThat(response.getBody()).isInstanceOfSatisfying(AuthResponse.class, body -> {
            assertThat(body.token()).isEqualTo("token-1");
            assertThat(body.expiresIn()).isEqualTo(900_000L);
            assertThat(body.name()).isEqualTo("Ana Diaz");
        });
        assertThat(response.getBody().toString()).doesNotContain("refresh-1");
    }

    @Test
    void renuevaLaSesionConLaCookieYRotaLaQueDevuelve() {
        ResponseEntity<?> response = controller().refresh("refresh-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOfSatisfying(RefreshResponse.class, body -> {
            assertThat(body.token()).isEqualTo("token-nuevo");
            assertThat(body.expiresIn()).isEqualTo(900_000L);
        });
        assertThat(refreshBodies).singleElement().asString().contains("refresh-1");
        // Directus rota el refresh token: la cookie tiene que quedarse con el nuevo o la
        // próxima renovación usaría uno ya consumido.
        assertThat(setCookie(response)).contains("studina_refresh=refresh-2");
    }

    /**
     * Un refresh token que Directus rechaza es una sesión terminada, no una falla del
     * servidor: el frontend distingue por el código para mandar al login en vez de
     * reintentar.
     */
    @Test
    void unRefreshTokenRechazadoTermina401YBorraLaCookie() {
        refreshTokenAccepted = false;

        ResponseEntity<?> response = controller().refresh("refresh-vencido");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isInstanceOfSatisfying(Map.class, body ->
                assertThat(body.get("error")).asString().contains("sesión"));
        assertThat(setCookie(response)).contains("Max-Age=0");
    }

    @Test
    void sinCookieNiSiquieraLlamaADirectus() {
        ResponseEntity<?> response = controller().refresh("  ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshBodies).isEmpty();
    }

    @Test
    void cerrarSesionBorraLaCookieEInvalidaElTokenEnDirectus() {
        ResponseEntity<?> response = controller().logout("refresh-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setCookie(response)).contains("Max-Age=0");
        // Sin esto el refresh token seguiría sirviendo hasta vencer, aunque el usuario
        // haya cerrado sesión.
        assertThat(logoutBodies).singleElement().asString().contains("refresh-1");
    }

    @Test
    void cerrarSesionSinCookieNoLlamaADirectusPeroIgualLimpia() {
        ResponseEntity<?> response = controller().logout(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setCookie(response)).contains("Max-Age=0");
        assertThat(logoutBodies).isEmpty();
    }

    @Test
    void meDevuelveElPerfilDelDuenoDelToken() {
        ResponseEntity<?> response = controller().me("Bearer token-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOfSatisfying(UserProfile.class, profile -> {
            assertThat(profile.userId()).isEqualTo("user-1");
            assertThat(profile.name()).isEqualTo("Ana Diaz");
            assertThat(profile.email()).isEqualTo("ana@example.com");
        });
    }

    @Test
    void meSinTokenResponde401() {
        assertThat(controller().me(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller().me("Bearer   ").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
