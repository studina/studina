package com.studina.dinaapi.ai.rag;

import java.util.List;
import java.util.Map;

import com.studina.dinaapi.learning.Topic;

/** Describe los contratos existentes de evaluación para salida estructurada. */
final class ResourceOutputSchema {

    private static final Map<String, Object> TEXT = Map.of("type", "string", "minLength", 1);
    private static final Map<String, Object> OPTIONAL_TEXT = Map.of("type", List.of("string", "null"));

    private ResourceOutputSchema() {
    }

    static Map<String, Object> forQuiz(int count, int answerCount, List<Topic> topics) {
        Map<String, Object> optionId = Map.of(
                "type", "string", "enum", List.of("a", "b", "c", "d").subList(0, answerCount));
        Map<String, Object> option = object(
                Map.of("id", optionId, "text", TEXT), List.of("id", "text"));
        Map<String, Object> question = object(
                Map.of(
                        "id", OPTIONAL_TEXT,
                        "question", TEXT,
                        "options", array(option, answerCount, answerCount),
                        "correctOptionId", optionId,
                        "explanation", OPTIONAL_TEXT,
                        "topic_id", topicId(topics)),
                List.of("question", "options", "correctOptionId", "topic_id"));
        return batch("questions", count, question);
    }

    static Map<String, Object> forTrueFalse(int count, List<Topic> topics) {
        Map<String, Object> statement = object(
                Map.of(
                        "id", OPTIONAL_TEXT,
                        "statement", TEXT,
                        "isTrue", Map.of("type", "boolean"),
                        "explanation", OPTIONAL_TEXT,
                        "topic_id", topicId(topics)),
                List.of("statement", "isTrue", "topic_id"));
        return batch("statements", count, statement);
    }

    static Map<String, Object> forOpenQuestions(int count, List<Topic> topics) {
        Map<String, Object> question = object(
                Map.of(
                        "id", OPTIONAL_TEXT,
                        "question", TEXT,
                        "reference_answer", TEXT,
                        "key_points", Map.of("type", "array", "items", TEXT, "minItems", 1),
                        "topic_id", topicId(topics)),
                List.of("question", "reference_answer", "key_points", "topic_id"));
        return batch("questions", count, question);
    }

    private static Map<String, Object> batch(String collection, int count, Map<String, Object> item) {
        // Metadata is optional and wrappers ignore unknown properties, just like the parser.
        return Map.of(
                "type", "object",
                "properties", Map.of("title", OPTIONAL_TEXT, collection, array(item, count, count)),
                "required", List.of(collection),
                "additionalProperties", true);
    }

    private static Map<String, Object> topicId(List<Topic> topics) {
        return Map.of("type", "string", "enum", topics.stream().map(Topic::id).toList());
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }

    private static Map<String, Object> array(Map<String, Object> item, int min, int max) {
        return Map.of("type", "array", "items", item, "minItems", min, "maxItems", max);
    }
}
