package com.studina.dinaapi.studyplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;

class StudyPlanPersistenceServiceTests {

    private record SavedPlan(String id, String user, String subject) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<SavedPlan> plans = new ArrayList<>();
    private final List<Map<String, String>> queries = new ArrayList<>();
    private final List<String> deletedIds = new ArrayList<>();
    private HttpServer server;
    private StudyPlanPersistenceService service;
    private boolean missingId;
    private boolean failDelete;

    @BeforeEach
    void startDirectusStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/items/study_plans", this::handleRequest);
        server.start();

        DirectusAuthorizationService authorization = mock(DirectusAuthorizationService.class);
        when(authorization.getAdminToken()).thenReturn("admin-token");
        service = new StudyPlanPersistenceService(authorization);
        ReflectionTestUtils.setField(service, "directusUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopDirectusStub() {
        server.stop(0);
    }

    @Test
    void deletesEveryPageWithoutSkippingRowsOrDeletingOtherUsersAndSubjects() {
        List<String> targetIds = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            targetIds.add(addPlan("user-1", "subject-1").id());
        }
        SavedPlan otherUser = addPlan("user-2", "subject-1");
        SavedPlan otherSubject = addPlan("user-1", "subject-2");

        service.deleteAllForSubject("user-1", "subject-1");

        assertThat(deletedIds).containsExactlyInAnyOrderElementsOf(targetIds);
        assertThat(plans).containsExactly(otherUser, otherSubject);
        assertThat(queries).hasSizeGreaterThan(1).allSatisfy(query -> {
            assertThat(query).containsEntry("filter[user][_eq]", "user-1")
                    .containsEntry("filter[subject][_eq]", "subject-1")
                    .doesNotContainKeys("offset", "page");
        });
    }

    @Test
    void deletingAnEmptySubjectDoesNotSendDeleteRequests() {
        service.deleteAllForSubject("user-1", "subject-1");

        assertThat(queries).hasSize(1);
        assertThat(deletedIds).isEmpty();
    }

    @Test
    void failsInsteadOfLoopingWhenDirectusReturnsAPlanWithoutAnId() {
        missingId = true;

        assertThatThrownBy(() -> service.deleteAllForSubject("user-1", "subject-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sin id");
        assertThat(queries).hasSize(1);
        assertThat(deletedIds).isEmpty();
    }

    @Test
    void propagatesDeletionFailureInsteadOfReportingSuccessOrRepeatingThePage() {
        SavedPlan plan = addPlan("user-1", "subject-1");
        failDelete = true;

        assertThatThrownBy(() -> service.deleteAllForSubject("user-1", "subject-1"))
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(plans).containsExactly(plan);
        assertThat(queries).hasSize(1);
    }

    private SavedPlan addPlan(String user, String subject) {
        SavedPlan plan = new SavedPlan(UUID.randomUUID().toString(), user, subject);
        plans.add(plan);
        return plan;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        if ("GET".equals(exchange.getRequestMethod())) {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            queries.add(query);
            // Directus can impose a page size smaller than the requested limit.
            int requestedLimit = Integer.parseInt(query.getOrDefault("limit", "100"));
            List<Map<String, String>> rows = missingId ? List.of(Map.of()) : plans.stream()
                    .filter(plan -> plan.user().equals(query.get("filter[user][_eq]")))
                    .filter(plan -> plan.subject().equals(query.get("filter[subject][_eq]")))
                    .limit(Math.min(17, requestedLimit))
                    .map(plan -> Map.of("id", plan.id()))
                    .toList();
            sendJson(exchange, 200, Map.of("data", rows));
        } else if ("DELETE".equals(exchange.getRequestMethod())) {
            if (failDelete) {
                sendJson(exchange, 500, Map.of("errors", List.of(Map.of("message", "Delete failed"))));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            plans.removeIf(plan -> plan.id().equals(id));
            deletedIds.add(id);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        } else {
            sendJson(exchange, 405, Map.of());
        }
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String parameter : rawQuery.split("&")) {
            String[] parts = parameter.split("=", 2);
            query.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return query;
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
