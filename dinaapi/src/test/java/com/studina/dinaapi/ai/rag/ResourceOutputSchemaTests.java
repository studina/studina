package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studina.dinaapi.learning.Topic;

class ResourceOutputSchemaTests {

    private static final List<Topic> TOPICS = List.of(
            new Topic("topic-a", "subject-a", "Tema A", ""),
            new Topic("topic-b", "subject-a", "Tema B", ""));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest(name = "{0}: {1} elementos")
    @MethodSource("batches")
    void requiresTheRequestedBatchAndAllowedTopics(String collection, int count, Map<String, Object> schema) {
        JsonNode root = objectMapper.valueToTree(schema);
        assertObject(root, true, List.of(collection), "title", collection);
        assertNullableText(root.at("/properties/title"));
        JsonNode batch = root.path("properties").path(collection);
        assertArray(batch, count, count);
        JsonNode item = batch.path("items");
        assertThat(item.path("additionalProperties").booleanValue()).isFalse();
        assertNullableText(item.at("/properties/id"));
        assertThat(strings(item.path("required"))).doesNotContain("id");
        JsonNode topic = item.at("/properties/topic_id");
        assertThat(topic.path("type").asText()).isEqualTo("string");
        assertThat(strings(topic.path("enum"))).containsExactly("topic-a", "topic-b");
        assertThat(strings(item.path("required"))).contains("topic_id");
    }

    static Stream<Arguments> batches() {
        return Stream.concat(
                Stream.of(5, 10, 20).flatMap(count -> Stream.of(
                        Arguments.of("cards", count, FlashcardOutputSchema.forCards(count, TOPICS)),
                        Arguments.of("questions", count, ResourceOutputSchema.forQuiz(count, 4, TOPICS)),
                        Arguments.of("statements", count, ResourceOutputSchema.forTrueFalse(count, TOPICS)))),
                Stream.of(3, 5, 10).map(count ->
                        Arguments.of("questions", count, ResourceOutputSchema.forOpenQuestions(count, TOPICS))));
    }

    @Test
    void flashcardsRequireTheExistingTermDefinitionContract() {
        JsonNode item = objectMapper.valueToTree(FlashcardOutputSchema.forCards(5, TOPICS))
                .at("/properties/cards/items");
        assertObject(item, false, List.of("term", "definition", "topic_id"), "id", "term", "definition", "topic_id");
        assertText(item.at("/properties/term"));
        assertText(item.at("/properties/definition"));
    }

    @Test
    void quizzesUseTheDtoFieldNamesAndRequestedOptionCount() {
        for (int answerCount : List.of(2, 3, 4)) {
            JsonNode item = objectMapper.valueToTree(ResourceOutputSchema.forQuiz(5, answerCount, TOPICS))
                    .at("/properties/questions/items");
            assertObject(item, false, List.of("question", "options", "correctOptionId", "topic_id"),
                    "id", "question", "options", "correctOptionId", "explanation", "topic_id");
            assertText(item.at("/properties/question"));
            assertString(item.at("/properties/correctOptionId"));
            assertNullableText(item.at("/properties/explanation"));
            JsonNode options = item.at("/properties/options");
            assertArray(options, answerCount, answerCount);
            assertObject(options.path("items"), "id", "text");
            assertString(options.at("/items/properties/id"));
            assertText(options.at("/items/properties/text"));
        }
    }

    @Test
    void trueFalseRequiresABooleanAnswerUsingTheExistingIsTrueName() {
        JsonNode item = objectMapper.valueToTree(ResourceOutputSchema.forTrueFalse(5, TOPICS))
                .at("/properties/statements/items");
        assertObject(item, false, List.of("statement", "isTrue", "topic_id"),
                "id", "statement", "isTrue", "explanation", "topic_id");
        assertText(item.at("/properties/statement"));
        assertNullableText(item.at("/properties/explanation"));
        assertThat(item.at("/properties/isTrue/type").asText()).isEqualTo("boolean");
    }

    @Test
    void openQuestionsRequireTheReferenceAnswerAndAtLeastOneTextKeyPointWithoutAMaximum() {
        JsonNode item = objectMapper.valueToTree(ResourceOutputSchema.forOpenQuestions(3, TOPICS))
                .at("/properties/questions/items");
        assertObject(item, false, List.of("question", "reference_answer", "key_points", "topic_id"),
                "id", "question", "reference_answer", "key_points", "topic_id");
        assertText(item.at("/properties/question"));
        assertText(item.at("/properties/reference_answer"));
        JsonNode points = item.at("/properties/key_points");
        assertThat(points.path("type").asText()).isEqualTo("array");
        assertThat(points.path("minItems").asInt()).isEqualTo(1);
        assertThat(points.has("maxItems")).isFalse();
        assertText(points.path("items"));
    }

    @ParameterizedTest(name = "{0}: metadata opcional compatible con parser y validación")
    @MethodSource("validPayloads")
    void preservesPayloadsAcceptedWithoutTitlesOrExplanations(String resource, String item) throws Exception {
        String collection = switch (resource) {
            case "flashcards" -> "cards";
            case "true-false" -> "statements";
            default -> "questions";
        };
        var service = new RagQueryService(null, null, null);
        for (String title : List.of("", "\"title\":null,", "\"title\":\"\",")) {
            String payload = "{" + title + "\"extra_metadata\":true,\"" + collection + "\":[" + item + "]}";
            String generatedTitle;
            String serverId;
            switch (resource) {
                case "flashcards" -> {
                    var parsed = service.parseFlashcards(payload, 1, Set.of("topic-a"));
                    generatedTitle = parsed.title();
                    serverId = parsed.cards().getFirst().id();
                }
                case "quiz" -> {
                    var parsed = service.parseQuizQuestions(payload, 1, 2, Set.of("topic-a"));
                    generatedTitle = parsed.title();
                    serverId = parsed.questions().getFirst().id();
                }
                case "true-false" -> {
                    var parsed = service.parseTrueFalse(payload, 1, Set.of("topic-a"));
                    generatedTitle = parsed.title();
                    serverId = parsed.statements().getFirst().id();
                }
                default -> {
                    var parsed = service.parseOpenQuestions(payload, 1, Set.of("topic-a"));
                    generatedTitle = parsed.title();
                    serverId = parsed.questions().getFirst().id();
                }
            }
            assertThat(generatedTitle).isNull();
            assertThat(serverId).isNotBlank().isNotEqualTo("model-id");
        }
    }

    static Stream<Arguments> validPayloads() {
        String card = "{\"id\":\"model-id\",\"term\":\"Concepto\",\"definition\":\"Definición\",\"topic_id\":\"topic-a\"}";
        String quiz = "{\"id\":null,\"question\":\"¿Pregunta?\",\"options\":[{\"id\":\"\",\"text\":\"A\"},{\"id\":\"b\",\"text\":\"B\"}],"
                + "\"correctOptionId\":\"\",\"topic_id\":\"topic-a\"}";
        String statement = "{\"statement\":\"Afirmación\",\"isTrue\":false,\"topic_id\":\"topic-a\"}";
        return Stream.concat(Stream.of(
                Arguments.of("flashcards", card), Arguments.of("quiz", quiz), Arguments.of("true-false", statement),
                Arguments.of("quiz", quiz.replace("\"question\":", "\"explanation\":null,\"question\":")),
                Arguments.of("true-false", statement.replace("\"statement\":", "\"explanation\":\"\",\"statement\":"))),
                Stream.of(1, 6).map(count -> {
                    String points = IntStream.range(0, count).mapToObj(index -> "\"Idea " + index + "\"")
                            .collect(java.util.stream.Collectors.joining(","));
                    return Arguments.of("open-questions", "{\"question\":\"¿Por qué?\",\"reference_answer\":\"Respuesta\","
                            + "\"key_points\":[" + points + "],\"topic_id\":\"topic-a\"}");
                }));
    }

    private static void assertObject(JsonNode node, String... fields) {
        assertObject(node, false, List.of(fields), fields);
    }

    private static void assertObject(JsonNode node, boolean additionalProperties, List<String> required, String... fields) {
        assertThat(node.path("type").asText()).isEqualTo("object");
        assertThat(node.path("additionalProperties").isBoolean()).isTrue();
        assertThat(node.path("additionalProperties").booleanValue()).isEqualTo(additionalProperties);
        List<String> properties = new ArrayList<>();
        node.path("properties").fieldNames().forEachRemaining(properties::add);
        assertThat(properties).containsExactlyInAnyOrder(fields);
        assertThat(strings(node.path("required"))).containsExactlyInAnyOrderElementsOf(required);
    }

    private static void assertText(JsonNode node) {
        assertThat(node.path("type").asText()).isEqualTo("string");
        assertThat(node.path("minLength").asInt()).isEqualTo(1);
    }

    private static void assertString(JsonNode node) {
        assertThat(node.path("type").asText()).isEqualTo("string");
        assertThat(node.has("minLength")).isFalse();
    }

    private static void assertNullableText(JsonNode node) {
        assertThat(strings(node.path("type"))).containsExactlyInAnyOrder("string", "null");
        assertThat(node.has("minLength")).isFalse();
    }

    private static void assertArray(JsonNode node, int min, int max) {
        assertThat(node.path("type").asText()).isEqualTo("array");
        assertThat(node.path("minItems").asInt()).isEqualTo(min);
        assertThat(node.path("maxItems").asInt()).isEqualTo(max);
    }

    private static List<String> strings(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
    }
}
