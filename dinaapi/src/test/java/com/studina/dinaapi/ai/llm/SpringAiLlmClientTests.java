package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.ai.rag.AiProviderErrorMapper;
import com.studina.dinaapi.ai.rag.OpenAnswerEvaluator;
import com.studina.dinaapi.ai.rag.OpenQuestion;
import com.studina.dinaapi.learning.LearningProgressPolicy;
import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;

@ExtendWith(OutputCaptureExtension.class)
class SpringAiLlmClientTests {

    @Test
    void sendsPortableOptionsAndReturnsProviderMetadata() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("respuesta"))));
        };
        LlmProperties properties = properties("google-genai", "gemini-test", 0.2);
        SpringAiLlmClient client = new SpringAiLlmClient(
                ChatClient.create(chatModel), properties);

        LlmResponse response = client.generate(new LlmRequest(
                "instrucciones",
                List.of(new UserMessage("mensaje anterior")),
                "pregunta",
                0.1));

        assertThat(response).isEqualTo(
                new LlmResponse("respuesta", "google-genai", "gemini-test"));
        assertThat(capturedPrompt.get().getOptions().getModel()).isEqualTo("gemini-test");
        assertThat(capturedPrompt.get().getOptions().getTemperature()).isEqualTo(0.1);
        assertThat(capturedPrompt.get().getInstructions())
                .extracting(message -> message.getText())
                .containsExactly("instrucciones", "mensaje anterior", "pregunta");
    }

    @Test
    void usesConfiguredTemperatureWhenTheRequestDoesNotOverrideIt() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("respuesta"))));
        };
        SpringAiLlmClient client = new SpringAiLlmClient(
                ChatClient.create(chatModel),
                properties("ollama", "llama-test", 0.35));

        client.generate(LlmRequest.of("sistema", "usuario"));

        assertThat(capturedPrompt.get().getOptions().getTemperature()).isEqualTo(0.35);
        assertThat(client.model()).isEqualTo(new LlmModel("ollama", "llama-test"));
    }

    @Test
    void selectsTheConfiguredModelForTheRequestedUseCase() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("respuesta"))));
        };
        LlmProperties properties = properties(
                "google-genai", "chat-model", 0.2);
        properties.setGenerationModel("generation-model");
        SpringAiLlmClient client = new SpringAiLlmClient(
                ChatClient.create(chatModel), properties);

        LlmResponse response = client.generate(
                LlmRequest.of("sistema", "usuario")
                        .forUseCase(LlmUseCase.GENERATION));

        assertThat(capturedPrompt.get().getOptions().getModel())
                .isEqualTo("generation-model");
        assertThat(response.model()).isEqualTo("generation-model");
        assertThat(client.model(LlmUseCase.GENERATION))
                .isEqualTo(new LlmModel("google-genai", "generation-model"));
    }

    @Test
    void routesAuthenticatedRequestsThroughTheUsersProviderAndCredential() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel userChatModel = prompt -> {
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("respuesta de usuario"))));
        };
        ChatClient userChatClient = ChatClient.create(userChatModel);
        UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
        LlmProviderClientFactory factory = mock(LlmProviderClientFactory.class);
        UserLlmRuntimeSettings settings = new UserLlmRuntimeSettings(
                "openai",
                "chat-model",
                "generation-model",
                null,
                null,
                "user-secret");
        when(settingsService.runtimeFor("user-a")).thenReturn(Optional.of(settings));
        when(factory.generation(eq("openai"), eq("user-secret"), eq("generation-model"), eq(0.2), anyLong()))
                .thenReturn(userChatClient);

        SpringAiLlmClient client = new SpringAiLlmClient(
                ChatClient.create(prompt -> new ChatResponse(List.of())),
                properties("google-genai", "server-model", 0.2),
                settingsService,
                factory);

        LlmResponse response = client.generate(
                LlmRequest.of("sistema", "usuario")
                        .forResourceGeneration()
                        .forUser("user-a"));

        assertThat(response).isEqualTo(new LlmResponse(
                "respuesta de usuario", "openai", "generation-model"));
        assertThat(capturedPrompt.get().getOptions().getModel())
                .isEqualTo("generation-model");
        verify(factory).generation(eq("openai"), eq("user-secret"), eq("generation-model"), eq(0.2), anyLong());
    }

    @Test
    void routesTheGlobalOllamaDefaultThroughTheFactoryWithoutUserSettings() {
        ChatClient unusedDefaultClient = mock(ChatClient.class);
        ChatClient localClient = ChatClient.create(prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("respuesta local")))));
        UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
        LlmProviderClientFactory factory = mock(LlmProviderClientFactory.class);
        when(settingsService.runtimeFor("user-without-settings")).thenReturn(Optional.empty());
        UserLlmRuntimeSettings selectedSettings = new UserLlmRuntimeSettings(
                "ollama", "generation-model", null, null, null, null);
        when(factory.create(selectedSettings, "generation-model", 0.1)).thenReturn(localClient);
        LlmProperties properties = properties("ollama", "chat-model", 0.2);
        properties.setGenerationModel("generation-model");
        SpringAiLlmClient client = new SpringAiLlmClient(
                unusedDefaultClient, properties, settingsService, factory);

        LlmResponse response = client.generate(LlmRequest.of("sistema", "usuario")
                .forUseCase(LlmUseCase.GENERATION)
                .forUser("user-without-settings")
                .withTemperature(0.1));

        assertThat(response).isEqualTo(new LlmResponse("respuesta local", "ollama", "generation-model"));
        verify(factory).create(selectedSettings, "generation-model", 0.1);
        verifyNoInteractions(unusedDefaultClient);
    }

    @ParameterizedTest
    @CsvSource({"ollama,length", "openai,length", "google-genai,MAX_TOKENS"})
    void rejectsTruncatedJsonGenerationWithoutRetrying(String provider, String finishReason) {
        AtomicInteger calls = new AtomicInteger();
        ChatModel chatModel = prompt -> {
            calls.incrementAndGet();
            return response("{\"questions\":[", finishReason, ChatResponseMetadata.builder().build());
        };
        var client = new SpringAiLlmClient(ChatClient.create(chatModel), properties(provider, "test-model", 0.2));

        assertThatThrownBy(() -> client.generate(LlmRequest.of("sistema", "documento")
                .forResourceGeneration().withJsonResponse()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La respuesta de IA se interrumpió al alcanzar el límite de generación. "
                        + "No se guardó un recurso incompleto");
        assertThat(calls).hasValue(1);
    }

    @Test
    @Timeout(5)
    void preservesPartialConversationalTextAndReleasesTheLocalSlotAfterTruncation() {
        ChatModel chatModel = prompt -> response("respuesta parcial", "length", ChatResponseMetadata.builder().build());
        var client = new SpringAiLlmClient(ChatClient.create(chatModel), properties("ollama", "test-model", 0.2));

        assertThatThrownBy(() -> client.generate(LlmRequest.of("sistema", "documento")
                .forResourceGeneration().withJsonResponse()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.generate(LlmRequest.of("sistema", "documento")).content()).isEqualTo("respuesta parcial");
    }

    @Test
    void resourceGenerationIsExplicitAndSurvivesRequestCopiesWithoutChangingModelSelection() {
        var schema = java.util.Map.<String, Object>of("type", "object");
        var ordinary = LlmRequest.of("system", "user").forUseCase(LlmUseCase.GENERATION)
                .withJsonSchema(schema);
        assertThat(ordinary.resourceGeneration()).isFalse();
        assertThat(new LlmRequest("system", List.of(), "user", 0.1,
                LlmUseCase.GENERATION, "user-a", true, schema).resourceGeneration()).isFalse();

        var resource = ordinary.forResourceGeneration().forUser("user-a")
                .withTemperature(0.1).withJsonResponse().withJsonSchema(schema)
                .forUseCase(LlmUseCase.GENERATION);
        assertThat(resource.resourceGeneration()).isTrue();
        assertThat(resource.useCase()).isEqualTo(LlmUseCase.GENERATION);
        assertThat(resource.userId()).isEqualTo("user-a");
        assertThat(resource.jsonSchema()).isEqualTo(schema);
        assertThat(resource.forUseCase(LlmUseCase.CHAT).resourceGeneration()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"google-genai,CHAT", "google-genai,CLASSIFICATION", "google-genai,SUMMARY",
            "google-genai,GENERATION", "openai,CHAT", "openai,CLASSIFICATION", "openai,SUMMARY",
            "openai,GENERATION"})
    void unmarkedTasksKeepTheirOriginalClientWithoutTheResourceRecoveryOrDeadline(
            String provider, LlmUseCase useCase) {
        var settings = new UserLlmRuntimeSettings(provider, "chat-model", "generation-model",
                "classification-model", "summary-model", "synthetic-key");
        var settingsService = mock(UserLlmSettingsService.class);
        when(settingsService.runtimeFor("user-a")).thenReturn(Optional.of(settings));
        var factory = mock(LlmProviderClientFactory.class);
        AtomicInteger calls = new AtomicInteger();
        ChatClient legacy = ChatClient.create(prompt -> {
            calls.incrementAndGet();
            throw new AiProviderErrorMapper.HttpFailure(503, null, false);
        });
        when(factory.create(settings, settings.modelFor(useCase), 0.2)).thenReturn(legacy);
        var client = new SpringAiLlmClient(mock(ChatClient.class), properties(provider, "server-model", 0.2),
                settingsService, factory, delay -> { throw new AssertionError("Unexpected resource retry"); });
        // Even JSON/schema requests are insufficient to opt into resource-specific behavior.
        assertThatThrownBy(() -> client.generate(LlmRequest.of("system", "user")
                .forUseCase(useCase).withJsonSchema(java.util.Map.of("type", "object")).forUser("user-a")))
                .isInstanceOf(AiProviderErrorMapper.HttpFailure.class);
        assertThat(calls).hasValue(1);
        verify(factory).create(settings, settings.modelFor(useCase), 0.2);
        verify(factory, never()).generation(anyString(), any(), anyString(), anyDouble(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"google-genai,2", "google-genai,3", "openai,2", "openai,3"})
    void evaluatorKeepsItsOwnRetryLoopAndSelectedModelWithoutTheNewInnerPolicy(
            String provider, int failures) {
        var settings = new UserLlmRuntimeSettings(provider, "chat-model", "evaluation-model", null, null, "synthetic-key");
        var settingsService = mock(UserLlmSettingsService.class);
        when(settingsService.runtimeFor("user-a")).thenReturn(Optional.of(settings));
        var factory = mock(LlmProviderClientFactory.class);
        AtomicInteger calls = new AtomicInteger();
        ChatClient legacy = ChatClient.create(prompt -> {
            int attempt = calls.incrementAndGet();
            assertThat(prompt.getOptions().getModel()).isEqualTo("evaluation-model");
            assertThat(prompt.getOptions().getTemperature()).isEqualTo(0.1);
            if (attempt <= failures) throw new AiProviderErrorMapper.HttpFailure(503, null, false);
            // A length marker on an unmarked task retains the pre-existing evaluator parsing path.
            return response("{\"verdict\":\"perfect\",\"feedback\":\"Correcto\",\"corrections\":[],\"missing_points\":[]}",
                    "length", ChatResponseMetadata.builder().build());
        });
        when(factory.create(settings, "evaluation-model", 0.1)).thenReturn(legacy);
        when(factory.generation(eq(provider), eq("synthetic-key"), eq("evaluation-model"), eq(0.1), anyLong()))
                .thenReturn(legacy);
        var client = new SpringAiLlmClient(mock(ChatClient.class), properties(provider, "server-model", 0.2),
                settingsService, factory, delay -> { throw new AssertionError("Nested resource retry"); });
        var evaluator = new OpenAnswerEvaluator(mock(ModelArmorService.class), client,
                new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30, 0.70, 0.40));
        ReflectionTestUtils.setField(evaluator, "maxAttempts", 3);
        ReflectionTestUtils.setField(evaluator, "retryDelayMs", 0L);
        var question = new OpenQuestion("q", "Pregunta sintética", "Respuesta", List.of("Punto"), "topic");
        if (failures == 3) {
            assertThatThrownBy(() -> evaluator.evaluate(question, "Respuesta", "user-a"))
                    .isInstanceOf(AiProviderErrorMapper.HttpFailure.class);
        } else {
            assertThat(evaluator.evaluate(question, "Respuesta", "user-a").score()).isEqualTo(1.0);
        }
        assertThat(calls).hasValue(3);
        verify(factory, times(3)).create(settings, "evaluation-model", 0.1);
        verify(factory, never()).generation(anyString(), any(), anyString(), anyDouble(), anyLong());
    }

    @Test
    void logsCorrelatedUsageAndTimingsWithoutContentOrCredentials(CapturedOutput output) {
        var metadata = ChatResponseMetadata.builder().model("reported-model")
                .usage(new DefaultUsage(80, 120))
                .keyValue("load-duration", Duration.ofMillis(11))
                .keyValue("prompt-eval-duration", Duration.ofMillis(22))
                .keyValue("eval-duration", Duration.ofMillis(33))
                .keyValue("total-duration", Duration.ofMillis(66))
                .keyValue("thinking", "private model thoughts")
                .id("private provider request identifier")
                .build();
        ChatClient selectedClient = ChatClient.create(prompt -> response("private model response", "stop", metadata));
        var settingsService = mock(UserLlmSettingsService.class);
        var factory = mock(LlmProviderClientFactory.class);
        var settings = new UserLlmRuntimeSettings("openai", "test-model", null, null, null, "private api key");
        when(settingsService.runtimeFor("private user identifier")).thenReturn(Optional.of(settings));
        when(factory.create(settings, "test-model", 0.2)).thenReturn(selectedClient);
        var client = new SpringAiLlmClient(mock(ChatClient.class), properties("openai", "test-model", 0.2),
                settingsService, factory);

        assertThat(client.generate(LlmRequest.of("private system prompt", "private document")
                .withJsonSchema(java.util.Map.of("type", "object"))
                .forUser("private user identifier")).content()).isEqualTo("private model response");

        String logs = output.getAll();
        var callId = Pattern.compile("LLM call started: callId=([a-f0-9-]{36}),").matcher(logs);
        assertThat(callId.find()).isTrue();
        assertThat(logs).contains("LLM call completed: callId=" + callId.group(1),
                "provider=openai", "model=test-model", "reportedModel=reported-model", "gateWaitMs=0",
                "schemaAttached=true", "nativeSchema=false",
                "finishReason=stop", "promptTokens=80", "completionTokens=120",
                "loadMs=11", "promptEvalMs=22", "evalMs=33", "totalMs=66")
                .doesNotContain("private system prompt", "private document", "private model response",
                        "private model thoughts", "private provider request identifier", "private api key",
                        "private user identifier");
    }

    @Test
    void logsOnlyTheClassOfProviderFailures(CapturedOutput output) {
        ChatModel chatModel = prompt -> {
            throw new IllegalStateException("private upstream response and credentials");
        };
        var client = new SpringAiLlmClient(ChatClient.create(chatModel), properties("ollama", "test-model", 0.2));

        assertThatThrownBy(() -> client.generate(LlmRequest.of("private prompt", "private document")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output.getAll()).contains("LLM call failed:", "exceptionClass=IllegalStateException")
                .doesNotContain("private upstream response and credentials", "private prompt", "private document");
    }

    @Test
    void doesNotLogUnrecognizedProviderMetadata(CapturedOutput output) {
        var metadata = ChatResponseMetadata.builder().model("private reported model value")
                .keyValue("load-duration", "private metadata value").build();
        ChatModel chatModel = prompt -> response("private response", "private finish reason", metadata);
        var client = new SpringAiLlmClient(ChatClient.create(chatModel), properties("ollama", "test-model", 0.2));

        client.generate(LlmRequest.of("system", "document"));

        assertThat(output.getAll()).contains("reportedModel=unknown", "finishReason=other", "loadMs=null")
                .doesNotContain("private reported model value", "private metadata value", "private finish reason", "private response");
    }

    private static ChatResponse response(String content, String finishReason, ChatResponseMetadata metadata) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())), metadata);
    }

    private static LlmProperties properties(
            String provider,
            String model,
            double temperature
    ) {
        LlmProperties properties = new LlmProperties();
        properties.setProvider(provider);
        properties.setModel(model);
        properties.setTemperature(temperature);
        return properties;
    }
}
