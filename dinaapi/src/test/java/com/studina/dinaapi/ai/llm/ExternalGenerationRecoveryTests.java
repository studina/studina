package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.ai.rag.*;
import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.directus.*;
import com.studina.dinaapi.learning.Topic;
import com.studina.dinaapi.learning.TopicCatalogService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Real SDK transports pointed exclusively at an in-process HTTP stub, never at a model. */
@Timeout(20)
@ExtendWith(OutputCaptureExtension.class)
class ExternalGenerationRecoveryTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOPIC = "11111111-1111-1111-1111-111111111111";

    enum Resource { FLASHCARDS, QUIZ, TRUE_FALSE, OPEN_QUESTIONS }

    @Test
    void logsBothUnavailableAttemptsAndTheResourceStageWithoutPrivateBodies(CapturedOutput output) throws Exception {
        try (var fixture = new Fixture("google-genai", Resource.QUIZ, 503)) {
            fixture.errorStatus = "UNAVAILABLE";
            fixture.retryAfter = "0";
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(503);
            assertThat(fixture.httpCalls).hasValue(2);
            fixture.verifyPersistence(0);
            assertThat(output.getAll()).contains("Resource request started: resource=quiz",
                    "LLM attempt failed:", "attempt=1", "attempt=2", "upstreamStatus=503",
                    "providerCode=UNAVAILABLE", "retryAfterMs=0", "LLM retry scheduled:",
                    "Resource generation failed: resource=quiz, stage=generation")
                    .doesNotContain("Resource saved:", "private upstream error response", "synthetic-user-key",
                            "Bearer synthetic", "subject-a", "file-a");
        }
    }

    @Test
    void logsBadRequestMetadataWithoutRetryingOrExposingTheBody(CapturedOutput output) throws Exception {
        try (var fixture = new Fixture("google-genai", Resource.QUIZ, 400)) {
            fixture.errorStatus = "INVALID_ARGUMENT";
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(502);
            assertThat(fixture.httpCalls).hasValue(1);
            assertThat(fixture.waits).isEmpty();
            fixture.verifyPersistence(0);
            assertThat(output.getAll()).contains("upstreamStatus=400", "providerCode=INVALID_ARGUMENT",
                    "Resource generation failed: resource=quiz, stage=generation")
                    .doesNotContain("private upstream error response", "synthetic-user-key", "Resource saved:");
        }
    }

    @Test
    void distinguishesPreparationAndStorageFailuresFromProviderFailures(CapturedOutput output) throws Exception {
        try (var fixture = new Fixture("google-genai", Resource.QUIZ, 200)) {
            when(fixture.topics.listForFilesOrSubjectFallback("subject-a", List.of("file-a")))
                    .thenThrow(new IllegalStateException("private catalog response"));
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(502);
            assertThat(fixture.httpCalls).hasValue(0);
        }
        try (var fixture = new Fixture("google-genai", Resource.QUIZ, 200)) {
            fixture.failPersistence();
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(502);
            assertThat(fixture.httpCalls).hasValue(1);
        }
        assertThat(output.getAll()).contains("Resource generation failed: resource=quiz, stage=preparation",
                "Resource generation failed: resource=quiz, stage=storage", "Resource parsed: resource=quiz")
                .doesNotContain("Resource saved:", "private catalog response");
    }

    @Test
    void correlatesHttpRequestProviderParseAndSavedLogsWithTheResponseHeader() throws Exception {
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start();
        root.addAppender(events);
        try (var fixture = new Fixture("google-genai", Resource.QUIZ, 200)) {
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(fixture.controller)
                    .addFilters(new com.studina.dinaapi.logging.RequestCorrelationFilter()).build();
            var request = new QuizGenerationRequest("subject-a", "Repaso", "", List.of("file-a"), 5, 2, "basic");
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/ai/files/quizzes").header("Authorization", "Bearer synthetic")
                    .contentType("application/json").content(JSON.writeValueAsString(request)))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            String requestId = response.getHeader("X-Request-ID");
            assertThat(java.util.UUID.fromString(requestId).toString()).isEqualTo(requestId);
            var resourceEvents = events.list.stream()
                    .filter(e -> e.getLoggerName().startsWith("com.studina.dinaapi.ai."))
                    .filter(e -> e.getFormattedMessage().startsWith("Resource ")
                            || e.getFormattedMessage().startsWith("LLM "))
                    .toList();
            assertThat(resourceEvents).isNotEmpty().allSatisfy(e ->
                    assertThat(e.getMDCPropertyMap()).containsEntry("requestId", requestId));
            assertThat(resourceEvents.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
                    .anyMatch(m -> m.startsWith("Resource saved: resource=quiz, count=5"));
            assertThat(org.slf4j.MDC.get("requestId")).isNull();
        } finally {
            root.detachAppender(events);
            events.stop();
        }
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void sendsTheResourceSchemaThroughTheActualGeminiSdk(Resource resource) throws Exception {
        try (var fixture = new Fixture("google-genai", resource, 200)) {
            fixture.payload = payload(resource, 5, 4);
            assertThat(fixture.invoke(5, 4).getStatusCode().value()).isEqualTo(200);
            assertThat(fixture.httpCalls).hasValue(1);
            fixture.assertCloudContract(5, 4);
            fixture.verifyPersistence(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20})
    void keepsDefaultAndMaximumQuizzesCompactWithSeveralUuidTopics(int count) throws Exception {
        try (var fixture = new Fixture("google-genai", Resource.QUIZ, 200)) {
            var topics = new ArrayList<Topic>();
            topics.add(new Topic(TOPIC, "subject-a", "Tema sintético", ""));
            for (int i = 1; i < 10; i++) {
                String id = java.util.UUID.nameUUIDFromBytes(
                        ("synthetic-topic-" + i).getBytes(StandardCharsets.UTF_8)).toString();
                topics.add(new Topic(id, "subject-a", "Tema sintético " + i, ""));
            }
            when(fixture.topics.listForFilesOrSubjectFallback("subject-a", List.of("file-a")))
                    .thenReturn(topics);
            fixture.payload = payload(Resource.QUIZ, count, 4);

            assertThat(fixture.invoke(count, 4).getStatusCode().value()).isEqualTo(200);
            assertThat(fixture.httpCalls).hasValue(1);
            var batch = JSON.readTree(fixture.requests.getFirst())
                    .at("/generationConfig/responseJsonSchema/properties/questions");
            assertThat(batch.has("minItems")).isFalse();
            assertThat(batch.has("maxItems")).isFalse();
            assertThat(batch.path("description").asText()).contains("exactly " + count + " items");
            assertThat(batch.at("/items/properties/topic_id/enum").size()).isEqualTo(10);
            assertThat(batch.at("/items/properties/options/minItems").asInt()).isEqualTo(4);
            assertThat(batch.at("/items/properties/options/maxItems").asInt()).isEqualTo(4);
            fixture.verifyPersistence(1);
        }
    }

    static Stream<Arguments> resources() {
        return Stream.of("google-genai", "openai")
                .flatMap(provider -> Stream.of(Resource.values()).map(resource -> Arguments.of(provider, resource)));
    }

    @ParameterizedTest
    @MethodSource("resources")
    void recoversOnceWithoutMultiplyingSdkRetriesAndPersistsExactlyOnce(String provider, Resource resource)
            throws Exception {
        try (var fixture = new Fixture(provider, resource, 503, 200)) {
            fixture.retryAfter = "0"; // Also guards against OkHttp's implicit 503 follow-up.
            var response = fixture.invoke();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(fixture.httpCalls).hasValue(2);
            assertThat(fixture.waits).hasSize(1);
            fixture.verifyPersistence(1);
            assertThat(fixture.requests.get(0)).isEqualTo(fixture.requests.get(1));
            fixture.assertCloudContract();
        }
    }

    @ParameterizedTest
    @MethodSource("resources")
    void stopsAfterTwo503ResponsesWithoutPersisting(String provider, Resource resource) throws Exception {
        try (var fixture = new Fixture(provider, resource, 503)) {
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(503);
            assertThat(fixture.httpCalls).hasValue(2);
            fixture.verifyPersistence(0);
        }
    }

    @ParameterizedTest
    @MethodSource("resources")
    void doesNotRegenerateAnInvalidSuccessfulResponse(String provider, Resource resource) throws Exception {
        try (var fixture = new Fixture(provider, resource, 200)) {
            fixture.payload = "{\"private-field\":";
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(400);
            assertThat(fixture.httpCalls).hasValue(1);
            assertThat(fixture.waits).isEmpty();
            fixture.verifyPersistence(0);
        }
    }

    @ParameterizedTest
    @MethodSource("resources")
    void doesNotRegenerateAValidJsonObjectWithMissingResources(String provider, Resource resource) throws Exception {
        try (var fixture = new Fixture(provider, resource, 200)) {
            fixture.payload = "{}";
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(400);
            assertThat(fixture.httpCalls).hasValue(1);
            assertThat(fixture.waits).isEmpty();
            fixture.verifyPersistence(0);
        }
    }

    @ParameterizedTest
    @MethodSource("resources")
    void neverRetriesPersistenceFailures(String provider, Resource resource) throws Exception {
        try (var fixture = new Fixture(provider, resource, 200)) {
            fixture.failPersistence();
            var response = fixture.invoke();
            assertThat(response.getStatusCode().value()).isEqualTo(502);
            assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
            assertThat(fixture.httpCalls).hasValue(1);
            fixture.verifyPersistence(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"google-genai", "openai"})
    void returnsLongRetryAfterWithoutRetryingEarlier(String provider) throws Exception {
        try (var fixture = new Fixture(provider, Resource.OPEN_QUESTIONS, 503)) {
            fixture.retryAfter = "90";
            var response = fixture.invoke();
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("90");
            assertThat(fixture.httpCalls).hasValue(1);
            assertThat(fixture.waits).isEmpty();
            fixture.verifyPersistence(0);
        }
    }

    @Test
    void preservesOpenAiMillisecondRetryHintsWithoutWaitingEarlier() throws Exception {
        try (var fixture = new Fixture("openai", Resource.OPEN_QUESTIONS, 503)) {
            fixture.retryAfterMs = "90001";
            var response = fixture.invoke();
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("91");
            assertThat(fixture.httpCalls).hasValue(1);
            assertThat(fixture.waits).isEmpty();
            fixture.verifyPersistence(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"google-genai", "openai"})
    void returnsQuotaErrorsWithoutAnySdkOrApplicationRetry(String provider) throws Exception {
        try (var fixture = new Fixture(provider, Resource.OPEN_QUESTIONS, 429)) {
            fixture.errorCode = "insufficient_quota";
            var response = fixture.invoke();
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getBody().toString()).contains("cuota");
            assertThat(fixture.httpCalls).hasValue(1);
            assertThat(fixture.waits).isEmpty();
            fixture.verifyPersistence(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"google-genai", "openai"})
    void returnsRateLimitsWithoutRetryingAndPreservesTheServerHint(String provider) throws Exception {
        try (var fixture = new Fixture(provider, Resource.OPEN_QUESTIONS, 429)) {
            fixture.errorCode = "rate_limit_exceeded";
            fixture.retryAfter = "2";
            var response = fixture.invoke();
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
            assertThat(response.getBody().toString()).contains("límite de solicitudes");
            assertThat(fixture.httpCalls).hasValue(1);
            fixture.verifyPersistence(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"google-genai", "openai"})
    void stopsOnATimeoutWithoutRetryingAnAmbiguousRequest(String provider) throws Exception {
        try (var fixture = new Fixture(provider, Resource.OPEN_QUESTIONS, 200)) {
            fixture.responseDelayMs = 300;
            ReflectionTestUtils.setField(fixture.client, "generationTimeoutMs", 150L);
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(504);
            assertThat(fixture.httpCalls.get()).isLessThanOrEqualTo(1);
            assertThat(fixture.waits).isEmpty();
            fixture.verifyPersistence(0);
        }
    }

    @Test
    void theServerDefaultGeminiUsesTheSameControlledPathAndItsOwnCredential() throws Exception {
        try (var fixture = new Fixture("google-genai", Resource.OPEN_QUESTIONS, 503, 200)) {
            when(fixture.settings.runtimeFor("user-a")).thenReturn(Optional.empty());
            assertThat(fixture.invoke().getStatusCode().value()).isEqualTo(200);
            assertThat(fixture.httpCalls).hasValue(2);
            assertThat(fixture.googleKeys).containsOnly("server-google-key");
            fixture.verifyPersistence(1);
        }
    }

    private static String payload(Resource resource) {
        return payload(resource, resource == Resource.OPEN_QUESTIONS ? 3 : 5, 2);
    }

    private static String payload(Resource resource, int count, int answerCount) {
        var root = JSON.createObjectNode().put("title", "Repaso sintético");
        String key = resource == Resource.FLASHCARDS ? "cards"
                : resource == Resource.TRUE_FALSE ? "statements" : "questions";
        var items = root.putArray(key);
        for (int i = 0; i < count; i++) {
            var item = items.addObject().put("topic_id", TOPIC);
            switch (resource) {
                case FLASHCARDS -> item.put("term", "Concepto " + i).put("definition", "Definición sintética");
                case TRUE_FALSE -> item.put("statement", "Enunciado " + i).put("isTrue", false)
                        .put("explanation", "Explicación sintética");
                case OPEN_QUESTIONS -> {
                    item.put("question", "¿Por qué " + i + "?").put("reference_answer", "Respuesta sintética");
                    item.putArray("key_points").add("Primera idea").add("Segunda idea");
                }
                case QUIZ -> {
                    item.put("question", "¿Qué concepto " + i + "?").put("correctOptionId", "a")
                            .put("explanation", "Explicación sintética");
                    var options = item.putArray("options");
                    for (int option = 0; option < answerCount; option++) {
                        options.addObject().put("id", Character.toString('a' + option))
                                .put("text", "Opción sintética " + option);
                    }
                }
            }
        }
        return root.toString();
    }

    private static final class Fixture implements AutoCloseable {
        final String provider;
        final Resource resource;
        final AtomicInteger httpCalls = new AtomicInteger();
        final List<Long> waits = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        final List<String> googleKeys = new ArrayList<>();
        final HttpServer server;
        final java.util.concurrent.ExecutorService handlers = Executors.newSingleThreadExecutor();
        final UserLlmSettingsService settings = mock(UserLlmSettingsService.class);
        final FlashcardSetService flashcards = mock(FlashcardSetService.class);
        final QuizSetService quizzes = mock(QuizSetService.class);
        final TrueFalseSetService trueFalse = mock(TrueFalseSetService.class);
        final OpenQuestionSetService openQuestions = mock(OpenQuestionSetService.class);
        final TopicCatalogService topics = mock(TopicCatalogService.class);
        final SpringAiLlmClient client;
        final RagController controller;
        volatile String payload;
        volatile String retryAfter;
        volatile String retryAfterMs;
        volatile String errorCode = "server_error";
        volatile String errorStatus;
        volatile int responseDelayMs;

        Fixture(String provider, Resource resource, int... statuses) throws Exception {
            this.provider = provider;
            this.resource = resource;
            this.payload = payload(resource);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                int call = httpCalls.getAndIncrement();
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                paths.add(exchange.getRequestURI().getPath());
                googleKeys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
                try {
                    if (responseDelayMs > 0) Thread.sleep(responseDelayMs);
                    int status = statuses[Math.min(call, statuses.length - 1)];
                    ObjectNode response = JSON.createObjectNode();
                    if (status != 200) {
                        response.putObject("error").put("code", errorCode)
                                .put("message", "private upstream error response");
                        if (errorStatus != null) ((ObjectNode) response.get("error")).put("status", errorStatus);
                    } else if (provider.equals("google-genai")) {
                        response.put("modelVersion", "generation-model");
                        var candidate = response.putArray("candidates").addObject().put("finishReason", "STOP");
                        candidate.putObject("content").put("role", "model").putArray("parts")
                                .addObject().put("text", payload);
                    } else {
                        response.put("id", "simulated").put("object", "chat.completion").put("created", 1)
                                .put("model", "generation-model");
                        var choice = response.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop");
                        choice.putObject("message").put("role", "assistant").put("content", payload);
                    }
                    byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
                    if (retryAfterMs != null) exchange.getResponseHeaders().set("Retry-After-Ms", retryAfterMs);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException disconnected) {
                    // A deadline test intentionally closes its connection before the stub responds.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var factory = new LlmProviderClientFactory("http://127.0.0.1:1", base + "/v1", 1,
                    "server-google-key", base);
            when(settings.runtimeFor("user-a")).thenReturn(Optional.of(new UserLlmRuntimeSettings(
                    provider, "unused-chat", "generation-model", null, null, "synthetic-user-key")));
            var properties = new LlmProperties();
            properties.setGenerationModel("generation-model");
            client = new SpringAiLlmClient(mock(ChatClient.class), properties, settings, factory, waits::add);
            ReflectionTestUtils.setField(client, "generationRetryDelayMs", 0L);
            ReflectionTestUtils.setField(client, "generationTimeoutMs", 5000L);
            var vectors = mock(VectorStore.class);
            when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("Material sintético.")));
            var query = new RagQueryService(mock(ModelArmorService.class), client, vectors);
            var auth = mock(DirectusAuthorizationService.class);
            when(auth.requireUserId("Bearer synthetic")).thenReturn("user-a");
            when(topics.listForFilesOrSubjectFallback("subject-a", List.of("file-a")))
                    .thenReturn(List.of(new Topic(TOPIC, "subject-a", "Tema sintético", "")));
            Map<String, Object> saved = Map.of("id", "saved-once");
            when(flashcards.create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class))).thenReturn(saved);
            when(quizzes.create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class))).thenReturn(saved);
            when(trueFalse.create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class))).thenReturn(saved);
            when(openQuestions.create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class))).thenReturn(saved);
            controller = new RagController(vectors, null, query, auth, null, null,
                    flashcards, quizzes, trueFalse, openQuestions, topics, null, null, null);
        }

        ResponseEntity<?> invoke() {
            return invoke(resource == Resource.OPEN_QUESTIONS ? 3 : 5, 2);
        }

        ResponseEntity<?> invoke(int count, int answerCount) {
            var files = List.of("file-a");
            return switch (resource) {
                case FLASHCARDS -> controller.generateFlashcards(new FlashcardGenerationRequest(
                        "subject-a", "Repaso", "", files, count, "term_definition", "basic"), "Bearer synthetic");
                case QUIZ -> controller.generateQuiz(new QuizGenerationRequest(
                        "subject-a", "Repaso", "", files, count, answerCount, "basic"), "Bearer synthetic");
                case TRUE_FALSE -> controller.generateTrueFalse(new TrueFalseGenerationRequest(
                        "subject-a", "Repaso", "", files, count, "basic"), "Bearer synthetic");
                case OPEN_QUESTIONS -> controller.generateOpenQuestions(new OpenQuestionGenerationRequest(
                        "subject-a", "Repaso", "", files, count, "basic"), "Bearer synthetic");
            };
        }

        void failPersistence() {
            var failure = new AiProviderErrorMapper.HttpFailure(503, 90_000L, false);
            switch (resource) {
                case FLASHCARDS -> when(flashcards.create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class))).thenThrow(failure);
                case QUIZ -> when(quizzes.create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class))).thenThrow(failure);
                case TRUE_FALSE -> when(trueFalse.create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class))).thenThrow(failure);
                case OPEN_QUESTIONS -> when(openQuestions.create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class))).thenThrow(failure);
            }
        }

        void verifyPersistence(int expected) {
            if (expected == 0) {
                verifyNoInteractions(flashcards, quizzes, trueFalse, openQuestions);
                return;
            }
            switch (resource) {
                case FLASHCARDS -> verify(flashcards).create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class));
                case QUIZ -> verify(quizzes).create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class));
                case TRUE_FALSE -> verify(trueFalse).create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class));
                case OPEN_QUESTIONS -> verify(openQuestions).create(any(), any(), any(), any(), any(), any(), any(GenerationMetadata.class));
            }
            verifyNoMoreInteractions(flashcards, quizzes, trueFalse, openQuestions);
        }

        void assertCloudContract() throws Exception {
            assertCloudContract(resource == Resource.OPEN_QUESTIONS ? 3 : 5, 2);
        }

        void assertCloudContract(int count, int answerCount) throws Exception {
            var body = JSON.readTree(requests.get(0));
            if (provider.equals("google-genai")) {
                assertThat(body.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
                assertThat(body.at("/generationConfig/responseSchema").isMissingNode()).isTrue();
                var schema = body.at("/generationConfig/responseJsonSchema");
                assertThat(schema.path("type").asText()).isEqualToIgnoringCase("object");
                assertThat(schema.has("additionalProperties")).isFalse();
                assertThat(schema.at("/properties/title/type").asText()).isEqualTo("string");
                assertThat(schema.at("/properties/title/nullable").asBoolean()).isTrue();
                String collection = resource == Resource.FLASHCARDS ? "cards"
                        : resource == Resource.TRUE_FALSE ? "statements" : "questions";
                assertThat(schema.path("required")).isEqualTo(JSON.valueToTree(List.of(collection)));
                var batch = schema.path("properties").path(collection);
                assertThat(batch.path("type").asText()).isEqualToIgnoringCase("array");
                assertThat(batch.has("minItems")).isFalse();
                assertThat(batch.has("maxItems")).isFalse();
                assertThat(batch.path("description").asText()).contains("exactly " + count + " items");
                var item = batch.path("items");
                assertThat(item.at("/properties/topic_id/enum")).isEqualTo(JSON.valueToTree(List.of(TOPIC)));
                if (resource == Resource.QUIZ) {
                    assertThat(item.at("/properties/options/minItems").asInt()).isEqualTo(answerCount);
                    assertThat(item.at("/properties/options/maxItems").asInt()).isEqualTo(answerCount);
                    assertThat(item.at("/properties/options/items/properties/text/minLength").asInt()).isEqualTo(1);
                }
                assertThat(googleKeys).containsOnly("synthetic-user-key");
                assertThat(paths).allSatisfy(path -> assertThat(path).contains("/models/generation-model:generateContent"));
            } else {
                assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
                assertThat(body.path("model").asText()).isEqualTo("generation-model");
                assertThat(body.path("response_format").has("json_schema")).isFalse();
            }
        }

        @Override public void close() {
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
