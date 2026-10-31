package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

class LlmCallOptionsTests {

    @Test
    void sendsTheSchemaOnlyToLocalOllamaWithBoundedResourceOptions() {
        Map<String, Object> schema = Map.of("type", "object", "required", java.util.List.of("cards"));
        LlmRequest request = LlmRequest.of("Original system", "Original user")
                .withJsonSchema(schema).forUser("user-a").withTemperature(0.2)
                .forUseCase(LlmUseCase.GENERATION);
        assertThat(request.jsonSchema()).isEqualTo(schema);
        var ollama = (OllamaChatOptions) LlmCallOptions.create(
                "ollama", "test-model", 0.2, request.jsonResponse(), request.jsonSchema());
        assertThat(ollama.getFormat()).isEqualTo(schema);
        assertThat(ollama.getNumCtx()).isEqualTo(4096);
        assertThat(ollama.getNumBatch()).isEqualTo(128);
        assertThat(ollama.getKeepAlive()).isEqualTo("0s");
        var google = (GoogleGenAiChatOptions) LlmCallOptions.create("google-genai", "gemini-test", 0.2, true, schema);
        assertThat(google.getResponseMimeType()).isEqualTo("application/json");
        assertThat(google.getResponseSchema()).isNull();
        var openAi = (OpenAiChatOptions) LlmCallOptions.create("openai", "gpt-5", 0.2, true, schema);
        assertThat(openAi.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        assertThat(openAi.getTemperature()).isNull();
    }

    @Test
    void requestsNativeJsonFromEveryProvider() {
        var google = (GoogleGenAiChatOptions) LlmCallOptions.create("google-genai", "gemini-test", 0.2, true);
        var openAi = (OpenAiChatOptions) LlmCallOptions.create("openai", "gpt-4.1-mini", 0.2, true);

        assertThat(google.getResponseMimeType()).isEqualTo("application/json");
        assertThat(openAi.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        for (String provider : new String[]{"ollama", "ollama-cloud"}) {
            var ollama = (OllamaChatOptions) LlmCallOptions.create(provider, "test-model", 0.2, true);
            assertThat(ollama.getFormat()).isEqualTo("json");
        }
    }

    @Test
    void leavesConversationalAnswersInTextMode() {
        assertThat(((GoogleGenAiChatOptions) LlmCallOptions.create("google-genai", "gemini-test", 0.2, false))
                .getResponseMimeType()).isNull();
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
