package com.studina.dinaapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Exercises the real authentication HTTP flow while replacing Directus with a local stub. This
 * catches request/response contract regressions without needing credentials or network access.
 */
class AuthControllerHttpTests {

    private HttpServer directus;
    private MockMvc mockMvc;
    private boolean roleAssigned;
    private boolean roleExists;
    private boolean duplicateUser;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger userLoginCount = new AtomicInteger();
    private final AtomicInteger roleCreationCount = new AtomicInteger();
    private final AtomicInteger userCreationCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        roleAssigned = true;
        roleExists = true;
        duplicateUser = false;

        directus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        directus.createContext("/", this::handleDirectusRequest);
        directus.start();

        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "directusUrl", directusUrl());
        ReflectionTestUtils.setField(controller, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(controller, "adminPassword", "admin-secret");
        ReflectionTestUtils.setField(controller, "userRoleName", "Studina User");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        directus.stop(0);
    }

    @Test
    void logsInAndReturnsTheProfileStoredInDirectus() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":" ada@example.com ","password":"correct-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.token").value("user-token-1"));

        assertThat(userLoginCount).hasValue(1);
    }

    @Test
    void assignsTheStudentRoleAndRefreshesTheSessionWhenItIsMissing() throws Exception {
        roleAssigned = false;

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","password":"correct-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("user-token-2"));

        assertThat(roleAssigned).isTrue();
        assertThat(userLoginCount).hasValue(2);
        assertThat(roleCreationCount).hasValue(0);
    }

    @Test
    void createsTheStudentRoleAndRegistersANewAccount() throws Exception {
        roleExists = false;

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Ada",
                                  "lastName":"Lovelace",
                                  "email":"ada@example.com",
                                  "password":"correct-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.token").value("user-token-1"));

        assertThat(roleCreationCount).hasValue(1);
        assertThat(userCreationCount).hasValue(1);
    }

    @Test
    void rejectsMalformedLoginDataBeforeCallingDirectus() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Email o contraseña incorrectos"));

        assertThat(requestCount).hasValue(0);
    }

    @Test
    void translatesRejectedCredentialsToUnauthorized() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Email o contraseña incorrectos"));
    }

    @Test
    void rejectsAnInvalidRegistrationEmailBeforeCallingDirectus() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ada","lastName":"Lovelace","email":"bad","password":"secret"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ingresá un email válido"));

        assertThat(requestCount).hasValue(0);
    }

    @Test
    void reportsAnExistingEmailAsAConflict() throws Exception {
        duplicateUser = true;

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Ada",
                                  "lastName":"Lovelace",
                                  "email":"ada@example.com",
                                  "password":"secret"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("El email ya está registrado"));
    }

    private String directusUrl() {
        return "http://127.0.0.1:" + directus.getAddress().getPort();
    }

    private void handleDirectusRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if ("POST".equals(method) && "/auth/login".equals(path)) {
            if (requestBody.contains("wrong-password")) {
                respond(exchange, 401, "{\"errors\":[{\"message\":\"Invalid credentials\"}]}");
                return;
            }
            if (requestBody.contains("admin@studina.test")) {
                respond(exchange, 200, "{\"data\":{\"access_token\":\"admin-token\"}}");
                return;
            }
            int loginNumber = userLoginCount.incrementAndGet();
            respond(exchange, 200,
                    "{\"data\":{\"access_token\":\"user-token-" + loginNumber + "\"}}");
            return;
        }

        if ("GET".equals(method) && "/users/me".equals(path)) {
            respond(exchange, 200, "{\"data\":{\"id\":\"user-1\"}}");
            return;
        }

        if ("GET".equals(method) && "/users/user-1".equals(path)) {
            String role = roleAssigned ? "{\"id\":\"role-1\"}" : "null";
            respond(exchange, 200, """
                    {"data":{"id":"user-1","first_name":"Ada","last_name":"Lovelace",
                    "email":"ada@example.com","role":%s}}
                    """.formatted(role));
            return;
        }

        if ("GET".equals(method) && "/roles".equals(path)) {
            String data = roleExists ? "[{\"id\":\"role-1\"}]" : "[]";
            respond(exchange, 200, "{\"data\":" + data + "}");
            return;
        }

        if ("POST".equals(method) && "/roles".equals(path)) {
            roleExists = true;
            roleCreationCount.incrementAndGet();
            respond(exchange, 200, "{\"data\":{\"id\":\"role-1\"}}");
            return;
        }

        if ("PATCH".equals(method) && "/users/user-1".equals(path)) {
            roleAssigned = true;
            respond(exchange, 204, "");
            return;
        }

        if ("POST".equals(method) && "/users".equals(path)) {
            if (duplicateUser) {
                respond(exchange, 400,
                        "{\"errors\":[{\"extensions\":{\"code\":\"RECORD_NOT_UNIQUE\"}}]}");
                return;
            }
            userCreationCount.incrementAndGet();
            respond(exchange, 200, "{\"data\":{\"id\":\"user-1\"}}");
            return;
        }

        respond(exchange, 404, "{\"error\":\"Unexpected stub request\"}");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        if (response.length > 0) {
            exchange.getResponseBody().write(response);
        }
        exchange.close();
    }
}
