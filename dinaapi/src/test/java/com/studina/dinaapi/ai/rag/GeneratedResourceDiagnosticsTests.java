package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class GeneratedResourceDiagnosticsTests {

    private static final Set<String> TOPICS = Set.of("topic-a");
    private static final String PRIVATE_TITLE = "private-title-sentinel";
    private static final String PRIVATE_CONTENT = "private-document-content-sentinel";
    private static final String PRIVATE_TOPIC = "private-topic-sentinel";
    private static final String PRIVATE_FIELD = "private-field-sentinel";

    enum Resource {
        FLASHCARDS("cards", "flashcards válidas", """
                {"term":"Concepto","definition":"Definición","topic_id":"topic-a"}
                """),
        QUIZ("questions", "un cuestionario válido", """
                {"question":"¿Pregunta?","options":[{"id":"a","text":"A"},{"id":"b","text":"B"}],
                 "correctOptionId":"a","topic_id":"topic-a"}
                """),
        TRUE_FALSE("statements", "enunciados de verdadero o falso válidos", """
                {"statement":"Afirmación","isTrue":false,"topic_id":"topic-a"}
                """),
        OPEN_QUESTIONS("questions", "preguntas abiertas válidas", """
                {"question":"¿Por qué?","reference_answer":"Respuesta","key_points":["Idea"],"topic_id":"topic-a"}
                """);

        final String key;
        final String publicMessageFragment;
        final String item;

        Resource(String key, String publicMessageFragment, String item) {
            this.key = key;
            this.publicMessageFragment = publicMessageFragment;
            this.item = item;
        }

        void parse(String response) {
            var service = new RagQueryService(null, null, null);
            switch (this) {
                case FLASHCARDS -> service.parseFlashcards(response, 1, TOPICS);
                case QUIZ -> service.parseQuizQuestions(response, 1, 2, TOPICS);
                case TRUE_FALSE -> service.parseTrueFalse(response, 1, TOPICS);
                case OPEN_QUESTIONS -> service.parseOpenQuestions(response, 1, TOPICS);
            }
        }

        String batch(String itemJson) {
            return "{\"" + key + "\":[" + itemJson + "]}";
        }

        String privateItem() {
            return item.replace("Concepto", PRIVATE_CONTENT).replace("¿Pregunta?", PRIVATE_CONTENT)
                    .replace("Afirmación", PRIVATE_CONTENT).replace("¿Por qué?", PRIVATE_CONTENT);
        }

        String titledBatch(String itemJson) {
            return "{\"title\":\"" + PRIVATE_TITLE + "\"," + batch(itemJson).substring(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void preservesTheJacksonCauseForTruncatedJson(Resource resource, CapturedOutput output) {
        String completeResponse = resource.titledBatch(resource.privateItem());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resource.parse(completeResponse.substring(0, completeResponse.length() - 2)));

        assertThat(error).hasMessageContaining(resource.publicMessageFragment)
                .hasCauseInstanceOf(JsonProcessingException.class)
                .hasRootCauseInstanceOf(JsonEOFException.class);
        assertThat(error).isNotInstanceOf(GeneratedResourcePolicy.ValidationException.class);
        assertThat(output.getAll()).contains("stage=parse", "exceptionClass=", "line=", "column=", "responseChars=");
        assertNoPrivateValues(output);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void doesNotLogPrivateTitlesOrResourceContentOnSuccess(Resource resource, CapturedOutput output) {
        resource.parse(resource.titledBatch(resource.privateItem()));

        assertThat(output.getAll()).contains("Resource parsed:", "received=1", "requested=1");
        assertNoPrivateValues(output);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void logsOnlyTheSafeReasonForARejectedPrivateTopic(Resource resource, CapturedOutput output) {
        var error = assertThrows(GeneratedResourcePolicy.ValidationException.class,
                () -> resource.parse(resource.titledBatch(resource.privateItem().replace("topic-a", PRIVATE_TOPIC))));

        assertThat(error.reason()).isEqualTo(GeneratedResourcePolicy.Reason.TOPIC_NOT_ALLOWED);
        assertThat(output.getAll()).contains("stage=validation", "reason=TOPIC_NOT_ALLOWED", "field=topic_id",
                "requested=1", "received=1", "index=0", "responseChars=");
        assertNoPrivateValues(output);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void doesNotLogAnUnknownPrivateFieldOrTheJacksonMessage(Resource resource, CapturedOutput output) {
        String itemWithPrivateField = "{\"" + PRIVATE_FIELD + "\":\"" + PRIVATE_CONTENT + "\","
                + resource.privateItem().substring(1);
        var error = assertThrows(IllegalArgumentException.class,
                () -> resource.parse(resource.titledBatch(itemWithPrivateField)));

        assertThat(error).hasCauseInstanceOf(JsonProcessingException.class);
        assertThat(output.getAll()).contains("stage=parse", "exceptionClass=UnrecognizedPropertyException")
                .doesNotContain("Unrecognized field", "through reference chain");
        assertNoPrivateValues(output);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void treatsAJsonNullRootAsAnAbsentBatch(Resource resource, CapturedOutput output) {
        var error = assertThrows(GeneratedResourcePolicy.ValidationException.class,
                () -> resource.parse("null"));

        assertThat(error.reason()).isEqualTo(GeneratedResourcePolicy.Reason.BATCH_MISSING);
        assertThat(error.field()).isEqualTo("batch");
        assertThat(error.requestedCount()).isEqualTo(1);
        assertThat(error.receivedCount()).isZero();
        assertThat(error.resourceIndex()).isEqualTo(-1);
        assertThat(error).hasMessageContaining(resource.publicMessageFragment).hasNoCause();
        assertThat(output.getAll()).contains("stage=validation", "reason=BATCH_MISSING", "responseChars=4");
    }

    private static void assertNoPrivateValues(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(PRIVATE_TITLE, PRIVATE_CONTENT, PRIVATE_TOPIC, PRIVATE_FIELD);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void distinguishesAnIncompatibleShapeFromAValidButIncompleteBatch(Resource resource) {
        IllegalArgumentException wrongShape = assertThrows(IllegalArgumentException.class,
                () -> resource.parse("{\"" + resource.key + "\":{}}"));
        assertThat(wrongShape).hasCauseInstanceOf(JsonProcessingException.class);

        var shortBatch = assertThrows(GeneratedResourcePolicy.ValidationException.class,
                () -> resource.parse(resource.batch("")));
        assertThat(shortBatch.reason()).isEqualTo(GeneratedResourcePolicy.Reason.COUNT_SHORT);
        assertThat(shortBatch.requestedCount()).isEqualTo(1);
        assertThat(shortBatch.receivedCount()).isZero();
        assertThat(shortBatch.resourceIndex()).isEqualTo(-1);
        assertThat(shortBatch).hasMessageContaining(resource.publicMessageFragment).hasNoCause();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void reportsAnAbsentBatchWithoutAParserCause(Resource resource) {
        var error = assertThrows(GeneratedResourcePolicy.ValidationException.class,
                () -> resource.parse("{}"));

        assertThat(error.reason()).isEqualTo(GeneratedResourcePolicy.Reason.BATCH_MISSING);
        assertThat(error.field()).isEqualTo("batch");
        assertThat(error.requestedCount()).isEqualTo(1);
        assertThat(error.receivedCount()).isZero();
        assertThat(error.resourceIndex()).isEqualTo(-1);
        assertThat(error).hasMessageContaining(resource.publicMessageFragment).hasNoCause();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void identifiesAMissingRequiredTopicAfterSuccessfulDeserialization(Resource resource) {
        var error = assertThrows(GeneratedResourcePolicy.ValidationException.class,
                () -> resource.parse(resource.batch(resource.item.replace(",\"topic_id\":\"topic-a\"", ""))));

        assertThat(error.reason()).isEqualTo(GeneratedResourcePolicy.Reason.TOPIC_MISSING);
        assertThat(error.field()).isEqualTo("topic_id");
        assertThat(error.resourceIndex()).isZero();
        assertThat(error).hasNoCause();
    }

    @Test
    void diagnosticsDoNotCarryTheRejectedTopicOrPrivateContent() {
        String privateText = "private-document-text-must-not-be-logged";
        String privateTopic = "private-topic-id";
        var error = assertThrows(GeneratedResourcePolicy.ValidationException.class,
                () -> GeneratedResourcePolicy.identifyCards(List.of(
                        new Flashcard(null, privateText, privateText, "topic-a"),
                        new Flashcard(null, privateText, privateText, privateTopic)), 2, TOPICS));

        assertThat(error.reason()).isEqualTo(GeneratedResourcePolicy.Reason.TOPIC_NOT_ALLOWED);
        assertThat(error.field()).isEqualTo("topic_id");
        assertThat(error.requestedCount()).isEqualTo(2);
        assertThat(error.receivedCount()).isEqualTo(2);
        assertThat(error.resourceIndex()).isEqualTo(1);
        assertThat(error).hasMessage("La IA no devolvió flashcards válidas. Intentá generarlas nuevamente");
        assertThat(error.toString()).doesNotContain(privateText, privateTopic);
    }

    @ParameterizedTest
    @MethodSource("rejectedContent")
    void reportsAFixedFieldAndReasonWithoutChangingThePublicError(
            Runnable call, GeneratedResourcePolicy.Reason reason, String field) {
        var error = assertThrows(GeneratedResourcePolicy.ValidationException.class, call::run);

        assertThat(error.reason()).isEqualTo(reason);
        assertThat(error.field()).isEqualTo(field);
        assertThat(error.resourceIndex()).isZero();
        assertThat(error.requestedCount()).isEqualTo(1);
        assertThat(error.receivedCount()).isEqualTo(1);
        assertThat(error.getMessage()).startsWith("La IA no devolvió");
    }

    static Stream<Arguments> rejectedContent() {
        return Stream.of(
                Arguments.of((Runnable) () -> GeneratedResourcePolicy.identifyCards(
                        Arrays.asList((Flashcard) null), 1, TOPICS),
                        GeneratedResourcePolicy.Reason.NULL_RESOURCE, "resource"),
                Arguments.of((Runnable) () -> GeneratedResourcePolicy.identifyCards(
                        List.of(new Flashcard(null, " ", "Definición", "topic-a")), 1, TOPICS),
                        GeneratedResourcePolicy.Reason.FIELD_MISSING, "term"),
                Arguments.of((Runnable) () -> GeneratedResourcePolicy.identifyQuestions(
                        List.of(new QuizQuestion(null, "¿Pregunta?", List.of(new QuizOption("a", "A")),
                                "a", null, "topic-a")), 1, 2, TOPICS),
                        GeneratedResourcePolicy.Reason.OPTION_COUNT, "options"),
                Arguments.of((Runnable) () -> GeneratedResourcePolicy.identifyQuestions(
                        List.of(new QuizQuestion(null, "¿Pregunta?", List.of(new QuizOption("a", "A"),
                                new QuizOption("b", " ")), "a", null, "topic-a")), 1, 2, TOPICS),
                        GeneratedResourcePolicy.Reason.OPTION_INVALID, "options[1]"),
                Arguments.of((Runnable) () -> GeneratedResourcePolicy.identifyQuestions(
                        List.of(new QuizQuestion(null, "¿Pregunta?", List.of(new QuizOption("a", "A")),
                                "absent", null, "topic-a")), 1, 1, TOPICS),
                        GeneratedResourcePolicy.Reason.CORRECT_OPTION_INVALID, "correctOptionId"),
                Arguments.of((Runnable) () -> GeneratedResourcePolicy.identifyStatements(
                        List.of(new TrueFalseStatement(null, "Afirmación", null, null, "topic-a")), 1, TOPICS),
                        GeneratedResourcePolicy.Reason.FIELD_MISSING, "isTrue"),
                Arguments.of((Runnable) () -> GeneratedResourcePolicy.identifyOpenQuestions(
                        List.of(new OpenQuestion(null, "¿Por qué?", null, List.of("Idea"), "topic-a")), 1, TOPICS),
                        GeneratedResourcePolicy.Reason.FIELD_MISSING, "reference_answer"),
                Arguments.of((Runnable) () -> GeneratedResourcePolicy.identifyOpenQuestions(
                        List.of(new OpenQuestion(null, "¿Por qué?", "Respuesta", List.of(" "), "topic-a")), 1, TOPICS),
                        GeneratedResourcePolicy.Reason.KEY_POINTS_INVALID, "key_points"));
    }
}
