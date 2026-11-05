package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

class LlmCallOptionsTests {

    @Test
    void sendsNativeSchemasToGeminiAndLocalOllamaWithOnlyLocalResourceBounds() throws Exception {
        Map<String, Object> schema = Map.of("type", "object", "required", java.util.List.of("cards"));
        LlmRequest request = LlmRequest.of("Original system", "Original user")
                .withJsonSchema(schema).forUser("user-a").withTemperature(0.2)
                .forResourceGeneration();
        assertThat(request.jsonSchema()).isEqualTo(schema);
        var ollama = (OllamaChatOptions) LlmCallOptions.create(
                "ollama", "test-model", 0.2, request.jsonResponse(), request.jsonSchema(), request.resourceGeneration());
        assertThat(ollama.getFormat()).isEqualTo(schema);
        assertThat(ollama.getNumCtx()).isEqualTo(GenerationContextBudget.CONTEXT_TOKENS);
        assertThat(ollama.getNumPredict()).isEqualTo(GenerationContextBudget.MAX_OUTPUT_TOKENS);
        assertThat(ollama.getNumBatch()).isEqualTo(128);
        assertThat(ollama.getNumThread()).isEqualTo(4);
        assertThat(ollama.getKeepAlive()).isEqualTo("0s");
        var google = (GoogleGenAiChatOptions) LlmCallOptions.create("google-genai", "gemini-test", 0.2, true, schema, true);
        assertThat(google.getResponseMimeType()).isEqualTo("application/json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(google.getResponseSchema())).isEqualTo(mapper.valueToTree(schema));
        assertThat(google.getMaxOutputTokens()).isNull();
        var openAi = (OpenAiChatOptions) LlmCallOptions.create("openai", "gpt-5", 0.2, true, schema, true);
        assertThat(openAi.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        assertThat(openAi.getTemperature()).isNull();
        assertThat(openAi.getMaxTokens()).isNull();
    }

    @Test
    void preservesLocalSchemasWithoutApplyingTheResourceProfileToOtherUseCases() {
        Map<String, Object> schema = Map.of("type", "object");
        for (LlmUseCase useCase : LlmUseCase.values()) {
            LlmRequest request = LlmRequest.of("System", "User").forUseCase(useCase).withJsonSchema(schema);
            var options = (OllamaChatOptions) LlmCallOptions.create("ollama", "test-model", 0.2,
                    request.jsonResponse(), request.jsonSchema(), request.resourceGeneration());

            assertThat(request.resourceGeneration()).isFalse();
            assertThat(options.getFormat()).isEqualTo(schema);
            assertNoResourceProfile(options);
        }
        assertNoResourceProfile((OllamaChatOptions) LlmCallOptions.create("ollama", "test-model", 0.2, false));
        assertNoResourceProfile((OllamaChatOptions) LlmCallOptions.create("ollama", "test-model", 0.2, true, schema));
    }

    @Test
    void neverAppliesTheLocalResourceProfileOrSchemaToOllamaCloud() {
        Map<String, Object> schema = Map.of("type", "object");
        var options = (OllamaChatOptions) LlmCallOptions.create("ollama-cloud", "test-model", 0.2, true, schema, true);

        assertThat(options.getFormat()).isEqualTo("json");
        assertNoResourceProfile(options);
    }

    private static void assertNoResourceProfile(OllamaChatOptions options) {
        assertThat(options.getNumCtx()).isNull();
        assertThat(options.getNumBatch()).isNull();
        assertThat(options.getNumThread()).isNull();
        assertThat(options.getKeepAlive()).isNull();
        assertThat(options.getNumPredict()).isNull();
    }

    @Test
    void requestsNativeJsonFromEveryProvider() {
        var google = (GoogleGenAiChatOptions) LlmCallOptions.create("google-genai", "gemini-test", 0.2, true);
        var openAi = (OpenAiChatOptions) LlmCallOptions.create("openai", "gpt-4.1-mini", 0.2, true);

        assertThat(google.getResponseMimeType()).isEqualTo("application/json");
        assertThat(google.getResponseSchema()).isNull();
        assertThat(openAi.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        for (String provider : new String[]{"ollama", "ollama-cloud"}) {
            var ollama = (OllamaChatOptions) LlmCallOptions.create(provider, "test-model", 0.2, true);
            assertThat(ollama.getFormat()).isEqualTo("json");
        }
    }

    @Test
    void adaptsNullableFieldsForTheGoogleSdkWithoutMutatingThePortableSchema() throws Exception {
        Map<String, Object> nullableText = Map.of("type", List.of("string", "null"));
        Map<String, Object> schema = Map.of("type", "object", "additionalProperties", true,
                "properties", Map.of("title", nullableText));
        var google = (GoogleGenAiChatOptions) LlmCallOptions.create("google-genai", "gemini-test", 0.2,
                true, schema);
        var wireSchema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(google.getResponseSchema());

        assertThat(wireSchema.has("additionalProperties")).isFalse();
        assertThat(wireSchema.at("/properties/title/type").asText()).isEqualTo("string");
        assertThat(wireSchema.at("/properties/title/nullable").asBoolean()).isTrue();
        assertThat(nullableText).containsEntry("type", List.of("string", "null"));
        assertThat(schema).containsEntry("additionalProperties", true);
    }

    @Test
    void leavesConversationalAnswersInTextMode() {
        var google = (GoogleGenAiChatOptions) LlmCallOptions.create("google-genai", "gemini-test", 0.2,
                false, Map.of("type", "object"));
        assertThat(google.getResponseMimeType()).isNull();
        assertThat(google.getResponseSchema()).isNull();
        assertThat(((OpenAiChatOptions) LlmCallOptions.create("openai", "gpt-4.1-mini", 0.2, false))
                .getResponseFormat()).isNull();
        assertThat(((OllamaChatOptions) LlmCallOptions.create("ollama", "test-model", 0.2, false))
                .getFormat()).isNull();
    }

    @Test
    void preservesJsonModeWhenTheRequestIsCustomized() {
        LlmRequest request = LlmRequest.of("Respondé JSON", "Pregunta")
                .withJsonResponse().forUser("user-a")
                .forUseCase(LlmUseCase.GENERATION).withTemperature(0.1);

        assertThat(request.jsonResponse()).isTrue();
        assertThat(request.userId()).isEqualTo("user-a");
        assertThat(request.temperature()).isEqualTo(0.1);
        assertThat(request.useCase()).isEqualTo(LlmUseCase.GENERATION);
    }
}
