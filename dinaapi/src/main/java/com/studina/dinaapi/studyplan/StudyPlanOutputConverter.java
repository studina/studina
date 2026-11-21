package com.studina.dinaapi.studyplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.converter.BeanOutputConverter;

/** Native Gemini schema with nullable indexes for review and mock-exam sessions. */
final class StudyPlanOutputConverter extends BeanOutputConverter<GeminiStudyPlanResponse> {

    StudyPlanOutputConverter() {
        super(GeminiStudyPlanResponse.class);
    }

    @Override
    protected String generateSchema() {
        try {
            ObjectNode schema = (ObjectNode) new ObjectMapper().readTree(super.generateSchema());
            for (String field : new String[] {"topicIndex", "partNumber"}) {
                ObjectNode property = (ObjectNode) schema.at("/properties/sessions/items/properties/" + field);
                // The GenAI SDK expects a scalar type and OpenAPI nullable, not a type union.
                property.put("nullable", true);
            }
            return schema.toString();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo preparar el esquema del plan de estudio.", exception);
        }
    }
}
