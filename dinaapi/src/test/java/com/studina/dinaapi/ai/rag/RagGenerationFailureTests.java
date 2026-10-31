package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.JsonParseException;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.directus.FlashcardSetService;
import com.studina.dinaapi.directus.OpenQuestionSetService;
import com.studina.dinaapi.directus.QuizSetService;
import com.studina.dinaapi.directus.TrueFalseSetService;
import com.studina.dinaapi.learning.Topic;
import com.studina.dinaapi.learning.TopicCatalogService;

@ExtendWith(OutputCaptureExtension.class)
class RagGenerationFailureTests {

    private static final String PRIVATE_DETAIL = "private-provider-response-body-sentinel";
    private static final String PRIVATE_KEY = "sk-private-api-key-sentinel";
    private static final String PRIVATE_WRAPPER = "private-wrapper-detail-sentinel";
    private static final String USER = "private-user-sentinel";
    private static final String SUBJECT = "private-subject-sentinel";
    private static final String TITLE = "private-title-sentinel";
    private static final String INSTRUCTIONS = "private-instructions-sentinel";
    private static final String AUTHORIZATION = "Bearer private-auth-token-sentinel";
    private static final List<String> FILES = List.of("private-file-sentinel");
    private static final List<Topic> TOPICS = List.of(new Topic("topic-a", SUBJECT, "private-topic-sentinel", ""));
    private static final List<Flashcard> CARDS = List.of(new Flashcard("card-a", "Concepto", "Definición", "topic-a"));
    private static final List<QuizQuestion> QUESTIONS = List.of(new QuizQuestion("question-a", "¿Pregunta?",
            List.of(new QuizOption("a", "Opción A"), new QuizOption("b", "Opción B")), "a", "Explicación", "topic-a"));
    private static final List<TrueFalseStatement> STATEMENTS = List.of(
            new TrueFalseStatement("statement-a", "Afirmación", false, "Explicación", "topic-a"));
    private static final List<OpenQuestion> OPEN_QUESTIONS = List.of(
            new OpenQuestion("open-a", "¿Por qué?", "Respuesta", List.of("Idea central"), "topic-a"));
    private static final Map<String, Object> SAVED = Map.of("id", "saved-set");

    private RagQueryService query;
    private FlashcardSetService flashcards;
    private QuizSetService quizzes;
    private TrueFalseSetService trueFalse;
    private OpenQuestionSetService openQuestions;
    private RagController controller;

    @BeforeEach
    void setUp() {
        query = mock(RagQueryService.class);
        flashcards = mock(FlashcardSetService.class);
        quizzes = mock(QuizSetService.class);
        trueFalse = mock(TrueFalseSetService.class);
        openQuestions = mock(OpenQuestionSetService.class);
        DirectusAuthorizationService authorization = mock(DirectusAuthorizationService.class);
        when(authorization.requireUserId(AUTHORIZATION)).thenReturn(USER);
        TopicCatalogService topics = mock(TopicCatalogService.class);
        when(topics.listForFilesOrSubjectFallback(SUBJECT, FILES)).thenReturn(TOPICS);
        controller = new RagController(null, null, query, authorization, null, null,
                flashcards, quizzes, trueFalse, openQuestions, topics, null, null, null);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("providerFailures")
    void mapsProviderFailuresWithoutLeakingDetailsOrPersistingResources(
            Resource resource, Failure failure, CapturedOutput output) {
        when(queryCall(query, resource)).thenThrow(failure.exception());

        ResponseEntity<?> response = call(resource);

        assertThat(response.getStatusCode().value()).isEqualTo(failure.status);
        assertThat(publicError(response)).isNotBlank();
        if (failure == Failure.BUSY) {
            assertThat(publicError(response)).isEqualTo(
                    "El proveedor de IA está temporalmente ocupado. Intentá nuevamente más tarde");
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        }
        assertNoPersistence();
        queryCall(verify(query), resource);
        verifyNoMoreInteractions(query);
        assertNoPrivateValues(response, output);
    }

    @ParameterizedTest(name = "{0}: JSON incorrecto={1}")
    @MethodSource("invalidPayloads")
    void preservesThePublicValidationErrorAndDoesNotSaveAnInvalidPayload(
            Resource resource, boolean malformedJson, CapturedOutput output) {
        IllegalArgumentException failure = malformedJson
                ? new IllegalArgumentException(resource.invalidMessage, new JsonParseException(null, PRIVATE_DETAIL))
                : new IllegalArgumentException(resource.invalidMessage);
        when(queryCall(query, resource)).thenThrow(failure);

        ResponseEntity<?> response = call(resource);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(publicError(response)).isEqualTo(resource.invalidMessage);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertNoPersistence();
        queryCall(verify(query), resource);
        verifyNoMoreInteractions(query);
        assertNoPrivateValues(response, output);
    }

    @ParameterizedTest(name = "{0}: Retry-After {1} ms")
    @MethodSource("retryAfterBoundaries")
    void sanitizesRetryAfterWithoutOverflowOrNegativeHeaders(
            Resource resource, long millis, String expectedHeader, CapturedOutput output) {
        when(queryCall(query, resource)).thenThrow(wrappedHttpFailure(503, millis, false));

        ResponseEntity<?> response = call(resource);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(expectedHeader);
        assertNoPersistence();
        queryCall(verify(query), resource);
        verifyNoMoreInteractions(query);
        assertNoPrivateValues(response, output);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void savesExactlyOneSetAfterSuccessfulGeneration(Resource resource, CapturedOutput output) {
        Object generated = switch (resource) {
            case FLASHCARDS -> new FlashcardGenerationResponse(CARDS, "Título generado", 1);
            case QUIZ -> new QuizGenerationResponse(QUESTIONS, "Título generado", 1);
            case TRUE_FALSE -> new TrueFalseGenerationResponse(STATEMENTS, "Título generado", 1);
            case OPEN_QUESTIONS -> new OpenQuestionGenerationResponse(OPEN_QUESTIONS, "Título generado", 1);
        };
        when(queryCall(query, resource)).thenReturn(generated);
        when(createCall(resource, false)).thenReturn(SAVED);

        ResponseEntity<?> response = call(resource);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(SAVED);
        createCall(resource, true);
        verifyNoMoreInteractions(flashcards, quizzes, trueFalse, openQuestions);
        queryCall(verify(query), resource);
        verifyNoMoreInteractions(query);
        assertNoPrivateValues(response, output);
    }

    static Stream<Arguments> providerFailures() {
        return Stream.of(Resource.values()).flatMap(resource -> Stream.of(Failure.values())
                .map(failure -> Arguments.of(resource, failure)));
    }

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(Resource.values()).flatMap(resource -> Stream.of(false, true)
                .map(malformed -> Arguments.of(resource, malformed)));
    }

    static Stream<Arguments> retryAfterBoundaries() {
        return Stream.of(Resource.values()).flatMap(resource -> Stream.of(
                Arguments.of(resource, -1L, null),
                Arguments.of(resource, Long.MAX_VALUE, Long.toString(Long.MAX_VALUE / 1000 + 1))));
    }

    private ResponseEntity<?> call(Resource resource) {
        return switch (resource) {
            case FLASHCARDS -> controller.generateFlashcards(new FlashcardGenerationRequest(
                    SUBJECT, TITLE, INSTRUCTIONS, FILES, 5, "term_definition", "basic"), AUTHORIZATION);
            case QUIZ -> controller.generateQuiz(new QuizGenerationRequest(
                    SUBJECT, TITLE, INSTRUCTIONS, FILES, 5, 2, "basic"), AUTHORIZATION);
            case TRUE_FALSE -> controller.generateTrueFalse(new TrueFalseGenerationRequest(
                    SUBJECT, TITLE, INSTRUCTIONS, FILES, 5, "basic"), AUTHORIZATION);
            case OPEN_QUESTIONS -> controller.generateOpenQuestions(new OpenQuestionGenerationRequest(
                    SUBJECT, TITLE, INSTRUCTIONS, FILES, 3, "basic"), AUTHORIZATION);
        };
    }

    private static Object queryCall(RagQueryService service, Resource resource) {
        return switch (resource) {
            case FLASHCARDS -> service.generateFlashcards(INSTRUCTIONS, FILES, 5,
                    "term_definition", "basic", TOPICS, USER);
            case QUIZ -> service.generateQuiz(INSTRUCTIONS, FILES, 5, 2, "basic", TOPICS, USER);
            case TRUE_FALSE -> service.generateTrueFalse(INSTRUCTIONS, FILES, 5, "basic", TOPICS, USER);
            case OPEN_QUESTIONS -> service.generateOpenQuestions(INSTRUCTIONS, FILES, 3, "basic", TOPICS, USER);
        };
    }

    private Map<String, Object> createCall(Resource resource, boolean verification) {
        return switch (resource) {
            case FLASHCARDS -> (verification ? verify(flashcards) : flashcards)
                    .create(SUBJECT, TITLE, INSTRUCTIONS, "basic", FILES, CARDS);
            case QUIZ -> (verification ? verify(quizzes) : quizzes)
                    .create(SUBJECT, TITLE, INSTRUCTIONS, "basic", FILES, QUESTIONS);
            case TRUE_FALSE -> (verification ? verify(trueFalse) : trueFalse)
                    .create(SUBJECT, TITLE, INSTRUCTIONS, "basic", FILES, STATEMENTS);
            case OPEN_QUESTIONS -> (verification ? verify(openQuestions) : openQuestions)
                    .create(SUBJECT, TITLE, INSTRUCTIONS, "basic", FILES, OPEN_QUESTIONS);
        };
    }

    private void assertNoPersistence() {
        verifyNoInteractions(flashcards, quizzes, trueFalse, openQuestions);
    }

    private static String publicError(ResponseEntity<?> response) {
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Object error = ((Map<?, ?>) response.getBody()).get("error");
        assertThat(error).isInstanceOf(String.class);
        return (String) error;
    }

    private static void assertNoPrivateValues(ResponseEntity<?> response, CapturedOutput output) {
        String[] privateValues = {PRIVATE_DETAIL, PRIVATE_KEY, PRIVATE_WRAPPER, USER, SUBJECT, TITLE,
                INSTRUCTIONS, AUTHORIZATION, FILES.getFirst(), TOPICS.getFirst().name()};
        assertThat(String.valueOf(response.getBody())).doesNotContain(privateValues);
        assertThat(response.getHeaders().toString()).doesNotContain(privateValues);
        assertThat(output.getAll()).doesNotContain(privateValues);
    }

    private static RuntimeException wrappedHttpFailure(int status, Long retryAfterMillis, boolean quota) {
        var failure = new AiProviderErrorMapper.HttpFailure(status, retryAfterMillis, quota);
        failure.initCause(new RuntimeException(PRIVATE_DETAIL + " " + PRIVATE_KEY));
        return new IllegalArgumentException(PRIVATE_WRAPPER, failure);
    }

    private enum Failure {
        BUSY(503), RATE_LIMIT(429), QUOTA_EXCEEDED(429), TIMEOUT(504), CONNECTION(502),
        AUTHENTICATION(422), MODEL_NOT_FOUND(422), UNKNOWN(502);

        private final int status;

        Failure(int status) {
            this.status = status;
        }

        RuntimeException exception() {
            return switch (this) {
                case BUSY -> wrappedHttpFailure(503, 2501L, false);
                case RATE_LIMIT -> wrappedHttpFailure(429, null, false);
                case QUOTA_EXCEEDED -> wrappedHttpFailure(429, null, true);
                case TIMEOUT -> new RuntimeException(PRIVATE_WRAPPER, new SocketTimeoutException(PRIVATE_DETAIL));
                case CONNECTION -> new IllegalArgumentException(PRIVATE_WRAPPER, new ConnectException(PRIVATE_DETAIL));
                case AUTHENTICATION -> wrappedHttpFailure(401, null, false);
                case MODEL_NOT_FOUND -> wrappedHttpFailure(404, null, false);
                case UNKNOWN -> new RuntimeException(PRIVATE_DETAIL + " " + PRIVATE_KEY);
            };
        }
    }

    private enum Resource {
        FLASHCARDS("La IA no devolvió flashcards válidas. Intentá generarlas nuevamente"),
        QUIZ("La IA no devolvió un cuestionario válido. Intentá generarlo nuevamente"),
        TRUE_FALSE("La IA no devolvió enunciados de verdadero o falso válidos. Intentá generarlos nuevamente"),
        OPEN_QUESTIONS("La IA no devolvió preguntas abiertas válidas. Intentá generarlas nuevamente");

        private final String invalidMessage;

        Resource(String invalidMessage) {
            this.invalidMessage = invalidMessage;
        }
    }
}
