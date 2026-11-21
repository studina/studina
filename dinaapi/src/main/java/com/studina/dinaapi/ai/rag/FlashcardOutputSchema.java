package com.studina.dinaapi.ai.rag;

import java.util.List;
import java.util.Map;

import com.studina.dinaapi.learning.Topic;

/** Describe el contrato existente para proveedores con salida estructurada. */
final class FlashcardOutputSchema {

    private FlashcardOutputSchema() {
    }

    static Map<String, Object> forCards(int count, List<Topic> topics) {
        Map<String, Object> text = Map.of("type", "string", "minLength", 1);
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", List.of("string", "null")),
                        "cards", Map.of(
                                "type", "array", "minItems", count, "maxItems", count,
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", List.of("string", "null")),
                                                "term", text,
                                                "definition", text,
                                                "topic_id", Map.of("type", "string", "enum",
                                                        topics.stream().map(Topic::id).toList())),
                                        "required", List.of("term", "definition", "topic_id"),
                                        "additionalProperties", false))),
                "required", List.of("cards"),
                "additionalProperties", true);
    }
}
