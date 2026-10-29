package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

class LlmCallOptionsTests {

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
