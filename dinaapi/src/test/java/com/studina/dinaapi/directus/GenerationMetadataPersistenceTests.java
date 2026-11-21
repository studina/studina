package com.studina.dinaapi.directus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studina.dinaapi.ai.rag.GenerationMetadata;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/** The reloaded services can recover provenance using only the rows saved in Directus. */
class GenerationMetadataPersistenceTests {

    private static final String SUBJECT_ID = "subject-1";
    private static final GenerationMetadata METADATA =
            new GenerationMetadata("ollama", "llama3.2:latest", 127_500);
    private static final Map<String, Object> EXPECTED_METADATA = Map.of(
            "provider", "ollama", "model", "llama3.2:latest", "durationMs", 127_500);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
    private final List<String> listFields = new ArrayList<>();
    private DirectusAuthorizationService authorization;
    private HttpServer server;

    @BeforeEach
    void startDirectus() throws IOException {
        authorization = mock(DirectusAuthorizationService.class);
        when(authorization.getAdminToken()).thenReturn("admin-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/items/", this::handleRequest);
        server.start();
    }

    @AfterEach
    void stopDirectus() {
        server.stop(0);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void persistsGenerationDetailsAndReloadsThemInANewService(Resource resource) {
        Map<String, Object> created = create(resource, METADATA);

        assertThat(created.get("generation_metadata")).isEqualTo(EXPECTED_METADATA);
        assertThat(rows.get(resource.collection).getFirst().get("generation_metadata"))
                .isEqualTo(EXPECTED_METADATA);

        // Construct a new service: none of the state used by creation survives this read.
        List<Map<String, Object>> reloaded = list(resource);

        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.getFirst().get("generation_metadata")).isEqualTo(EXPECTED_METADATA);
        assertThat(reloaded.getFirst().get("id")).isEqualTo(created.get("id"));
        assertThat(listFields).singleElement().asString().contains("generation_metadata");
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void keepsLegacySetsWithoutInventingHistoricalGenerationDetails(Resource resource) {
        Map<String, Object> legacy = new LinkedHashMap<>(Map.of(
                "id", "legacy-set", "subject", SUBJECT_ID, "title", "Set anterior"));
        legacy.put("generation_metadata", null);
        rows.put(resource.collection, new ArrayList<>(List.of(legacy)));

        List<Map<String, Object>> reloaded = list(resource);

        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.getFirst()).containsEntry("generation_metadata", null);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void supportsCreationWithoutGenerationDetails(Resource resource) {
        create(resource, null);

        assertThat(rows.get(resource.collection).getFirst()).doesNotContainKey("generation_metadata");
        assertThat(list(resource).getFirst()).containsEntry("generation_metadata", null);
    }

    private Map<String, Object> create(Resource resource, GenerationMetadata metadata) {
        return switch (resource) {
            case FLASHCARDS -> configured(new FlashcardSetService(authorization)).create(
                    SUBJECT_ID, "Conceptos", "", "basic", List.of("file-1"), List.of(), metadata);
            case QUIZ -> configured(new QuizSetService(authorization)).create(
                    SUBJECT_ID, "Conceptos", "", "basic", List.of("file-1"), List.of(), metadata);
            case TRUE_FALSE -> configured(new TrueFalseSetService(authorization)).create(
                    SUBJECT_ID, "Conceptos", "", "basic", List.of("file-1"), List.of(), metadata);
            case OPEN_QUESTIONS -> configured(new OpenQuestionSetService(authorization)).create(
                    SUBJECT_ID, "Conceptos", "", "basic", List.of("file-1"), List.of(), metadata);
        };
    }

    private List<Map<String, Object>> list(Resource resource) {
        return switch (resource) {
            case FLASHCARDS -> configured(new FlashcardSetService(authorization)).listForSubject(SUBJECT_ID);
            case QUIZ -> configured(new QuizSetService(authorization)).listForSubject(SUBJECT_ID);
            case TRUE_FALSE -> configured(new TrueFalseSetService(authorization)).listForSubject(SUBJECT_ID);
            case OPEN_QUESTIONS -> configured(new OpenQuestionSetService(authorization)).listForSubject(SUBJECT_ID);
        };
    }

    private <T> T configured(T service) {
        ReflectionTestUtils.setField(service, "directusUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
        return service;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String collection = exchange.getRequestURI().getPath().substring("/items/".length());
        Object data;
        if ("POST".equals(exchange.getRequestMethod())) {
            Map<String, Object> row = objectMapper.readValue(
                    exchange.getRequestBody(), new TypeReference<>() {});
            row.put("id", UUID.randomUUID().toString());
            rows.computeIfAbsent(collection, ignored -> new ArrayList<>()).add(row);
            data = row;
        } else if (Arrays.stream(Resource.values()).anyMatch(resource -> resource.collection.equals(collection))) {
            String fields = Arrays.stream(exchange.getRequestURI().getQuery().split("&"))
                    .filter(parameter -> parameter.startsWith("fields="))
                    .map(parameter -> parameter.substring("fields=".length()))
                    .findFirst().orElseThrow();
            listFields.add(fields);
            data = rows.getOrDefault(collection, List.of()).stream().map(row -> {
                Map<String, Object> projected = new LinkedHashMap<>();
                for (String field : fields.split(",")) {
                    projected.put(field, row.get(field));
                }
                return projected;
            }).toList();
        } else {
            data = List.of();
        }
        byte[] body = objectMapper.writeValueAsString(Map.of("data", data)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private enum Resource {
        FLASHCARDS("flashcards"), QUIZ("quizzes"), TRUE_FALSE("true_false_sets"),
        OPEN_QUESTIONS("open_question_sets");

        private final String collection;

        Resource(String collection) {
            this.collection = collection;
        }
    }
}
