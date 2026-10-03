package com.studina.dinaapi.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Asociar documentos a un tema exige que sean de esa materia, no sólo del usuario.
 *
 * <p>El usuario {@code user-a} tiene dos materias; {@code file-de-redes} está en la carpeta de
 * {@code subject-redes} y {@code file-de-so} en la de {@code subject-so}. Los dos pasan
 * {@code requireFileOwnership}, así que lo que se prueba acá es el chequeo extra.
 */
class FilesInSubjectTests {

    private HttpServer server;

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();

            int status = 200;
            String body;
            if (path.endsWith("/auth/login")) {
                body = "{\"data\":{\"access_token\":\"admin-token\",\"expires\":900000}}";
            } else if (path.equals("/files/file-de-redes")) {
                body = "{\"data\":{\"id\":\"file-de-redes\",\"folder\":\"folder-redes\"}}";
            } else if (path.equals("/files/file-de-so")) {
                body = "{\"data\":{\"id\":\"file-de-so\",\"folder\":\"folder-so\"}}";
            } else if (path.equals("/folders/folder-redes")) {
                body = "{\"data\":{\"id\":\"folder-redes\",\"name\":\"subject-redes\",\"parent\":\"folder-user-a\"}}";
            } else if (path.equals("/folders/folder-so")) {
                body = "{\"data\":{\"id\":\"folder-so\",\"name\":\"subject-so\",\"parent\":\"folder-user-a\"}}";
            } else if (path.equals("/folders/folder-user-a")) {
                body = "{\"data\":{\"id\":\"folder-user-a\",\"name\":\"user-a\",\"parent\":null}}";
            } else {
                status = 404;
                body = "{\"errors\":[{\"message\":\"Not found\"}]}";
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

    private DirectusAuthorizationService service() {
        DirectusAuthorizationService service = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(service, "directusUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(service, "adminPassword", "secret");
        return service;
    }

    @Test
    void losDocumentosDeLaMateriaPasan() {
        assertThatCode(() -> service().requireFilesInSubject(
                "user-a", "subject-redes", List.of("file-de-redes")))
                .doesNotThrowAnyException();
    }

    @Test
    void unDocumentoPropioDeOtraMateriaDaForbidden() {
        assertThatThrownBy(() -> service().requireFilesInSubject(
                "user-a", "subject-redes", List.of("file-de-redes", "file-de-so")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void unDocumentoDeOtroUsuarioDaForbidden() {
        assertThatThrownBy(() -> service().requireFilesInSubject(
                "user-b", "subject-redes", List.of("file-de-redes")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void sinDocumentosDaBadRequest() {
        assertThatThrownBy(() -> service().requireFilesInSubject("user-a", "subject-redes", List.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
