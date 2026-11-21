package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studina.dinaapi.ai.llm.GenerationContextBudget;
import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.ai.llm.LlmModel;
import com.studina.dinaapi.ai.llm.LlmRequest;
import com.studina.dinaapi.ai.llm.LlmResponse;
import com.studina.dinaapi.ai.llm.LlmUseCase;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.learning.Topic;

/** Exercises the real generation services with a captured provider-neutral request. */
class RagResourceGenerationContractTests {

    private static final String USER_ID = "user-a";
    private static final String INSTRUCTIONS = "Priorizá las diferencias entre patente y marca.";
    private static final String MATERIAL = "La patente protege una invención; la marca identifica productos. ".repeat(150);
    private static final List<Topic> TOPICS = List.of(
            new Topic("topic-a", "subject-a", "Propiedad intelectual", ""));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest(name = "{0}, {1}: esquema y presupuesto")
    @MethodSource("generatorsAndProviders")
    void everyGeneratorPropagatesTheUserSchemaAndStudyChoices(Resource resource, String provider) throws Exception {
        Harness harness = harness(resource, provider);

        GenerationResult generated = generate(resource, harness.service(), INSTRUCTIONS, TOPICS);
        assertThat(generated.count()).isEqualTo(resource.count);
        assertThat(generated.metadata()).isNotNull();
        assertThat(generated.metadata().provider()).isEqualTo(provider);
        assertThat(generated.metadata().model()).isEqualTo("actual-generation-model");
        assertThat(generated.metadata().durationMs()).isGreaterThanOrEqualTo(0);

        LlmRequest request = capturedRequest(harness.llm());
        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.useCase()).isEqualTo(LlmUseCase.GENERATION);
        assertThat(request.resourceGeneration()).isTrue();
        assertThat(request.jsonResponse()).isTrue();
        assertThat(request.systemPrompt()).contains("exactamente " + resource.count, "Dificultad pedida: Básica");
        assertThat(request.userPrompt()).contains(INSTRUCTIONS, "topic-a", "Propiedad intelectual");
        JsonNode schema = objectMapper.valueToTree(request.jsonSchema());
        JsonNode batch = schema.path("properties").path(resource.collection);
        assertThat(batch.path("minItems").asInt()).isEqualTo(resource.count);
        assertThat(batch.path("maxItems").asInt()).isEqualTo(resource.count);
        assertThat(batch.at("/items/properties/topic_id/enum/0").asText()).isEqualTo("topic-a");
        if (resource == Resource.QUIZ) {
            assertThat(batch.at("/items/properties/options/minItems").asInt()).isEqualTo(3);
            assertThat(batch.at("/items/properties/options/maxItems").asInt()).isEqualTo(3);
            assertThat(request.systemPrompt()).contains("exactamente 3 opciones");
        }
        if ("ollama".equals(provider)) {
            int bytes = request.systemPrompt().getBytes(StandardCharsets.UTF_8).length
                    + request.userPrompt().getBytes(StandardCharsets.UTF_8).length
                    + GenerationContextBudget.TEMPLATE_RESERVE_BYTES;
            assertThat(bytes).isLessThanOrEqualTo(GenerationContextBudget.MAX_INPUT_BYTES);
            assertThat(request.userPrompt()).doesNotContain(MATERIAL);
        } else {
            assertThat(request.userPrompt()).contains(MATERIAL, "Documento: Apunte.pdf", "File ID: file-a");
        }
    }

    @ParameterizedTest(name = "{0}, {1}, {2}: indicaciones y catálogo largos")
    @MethodSource("oversizedFixedInputs")
    void oversizedFixedInputsFailBeforeLocalInferenceAndRemainIntactForCloudProviders(
            Resource resource, String provider, String oversizedPart) throws Exception {
        Harness harness = harness(resource, provider);
        String longText = "CONTENIDO_PRIVADO_DE_PRUEBA_".repeat(200);
        String instructions = "instructions".equals(oversizedPart) ? longText : INSTRUCTIONS;
        List<Topic> topics = "topics".equals(oversizedPart)
                ? List.of(new Topic("topic-a", "subject-a", longText, "")) : TOPICS;

        if ("ollama".equals(provider)) {
            assertThatThrownBy(() -> generate(resource, harness.service(), instructions, topics))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("presupuesto de contexto")
                    .hasMessageNotContaining("CONTENIDO_PRIVADO_DE_PRUEBA_");
            verify(harness.llm(), never()).generate(any(LlmRequest.class));
        } else {
            assertThat(generate(resource, harness.service(), instructions, topics).count()).isEqualTo(resource.count);
            LlmRequest request = capturedRequest(harness.llm());
            assertThat(request.userPrompt()).contains(MATERIAL, instructions, topics.getFirst().name(), longText);
            assertThat(request.userId()).isEqualTo(USER_ID);
        }
    }

    static Stream<Arguments> generatorsAndProviders() {
        return Stream.of(Resource.values()).flatMap(resource -> Stream.of("google-genai", "openai", "ollama")
                .map(provider -> Arguments.of(resource, provider)));
    }

    static Stream<Arguments> oversizedFixedInputs() {
        return Stream.of(Resource.values()).flatMap(resource -> Stream.of("google-genai", "openai", "ollama")
                .flatMap(provider -> Stream.of("instructions", "topics")
                        .map(part -> Arguments.of(resource, provider, part))));
    }

    private Harness harness(Resource resource, String provider) throws Exception {
        LlmClient llm = mock(LlmClient.class);
        when(llm.model(LlmUseCase.GENERATION, USER_ID)).thenReturn(new LlmModel(provider, "user-generation-model"));
        List<Map<String, Object>> items = IntStream.range(0, resource.count).mapToObj(index -> item(resource)).toList();
        String response = objectMapper.writeValueAsString(Map.of("title", "Propiedad intelectual", resource.collection, items));
        when(llm.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(response, provider, "actual-generation-model"));
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document(MATERIAL, Map.of("filename", "Apunte.pdf", "fileId", "file-a", "chunkIndex", 0))));
        return new Harness(new RagQueryService(mock(ModelArmorService.class), llm, vectorStore), llm);
    }

    private static Map<String, Object> item(Resource resource) {
        return switch (resource) {
            case FLASHCARDS -> Map.of("term", "Patente", "definition", "Protege una invención.", "topic_id", "topic-a");
            case QUIZ -> Map.of(
                    "question", "¿Qué protege una patente?",
                    "options", List.of(Map.of("id", "a", "text", "Una invención"),
                            Map.of("id", "b", "text", "Un nombre"), Map.of("id", "c", "text", "Un producto")),
                    "correctOptionId", "a", "explanation", "Protege la invención.", "topic_id", "topic-a");
            case TRUE_FALSE -> Map.of("statement", "La patente protege una invención.", "isTrue", true,
                    "explanation", "Según el apunte.", "topic_id", "topic-a");
            case OPEN_QUESTIONS -> Map.of("question", "¿Cómo difieren una patente y una marca?",
                    "reference_answer", "La patente protege una invención y la marca identifica productos.",
                    "key_points", List.of("invención", "identificación"), "topic_id", "topic-a");
        };
    }

    private static GenerationResult generate(Resource resource, RagQueryService service, String instructions, List<Topic> topics) {
        return switch (resource) {
            case FLASHCARDS -> {
                FlashcardGenerationResponse response = service.generateFlashcards(instructions, List.of("file-a"), resource.count,
                        "term_definition", "basic", topics, USER_ID);
                yield new GenerationResult(response.cards().size(), response.generationMetadata());
            }
            case QUIZ -> {
                QuizGenerationResponse response = service.generateQuiz(instructions, List.of("file-a"), resource.count,
                        3, "basic", topics, USER_ID);
                yield new GenerationResult(response.questions().size(), response.generationMetadata());
            }
            case TRUE_FALSE -> {
                TrueFalseGenerationResponse response = service.generateTrueFalse(instructions, List.of("file-a"), resource.count,
                        "basic", topics, USER_ID);
                yield new GenerationResult(response.statements().size(), response.generationMetadata());
            }
            case OPEN_QUESTIONS -> {
                OpenQuestionGenerationResponse response = service.generateOpenQuestions(instructions, List.of("file-a"), resource.count,
                        "basic", topics, USER_ID);
                yield new GenerationResult(response.questions().size(), response.generationMetadata());
            }
        };
    }

    private static LlmRequest capturedRequest(LlmClient llm) {
        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).generate(request.capture());
        return request.getValue();
    }

    private enum Resource {
        FLASHCARDS("cards", 5), QUIZ("questions", 5), TRUE_FALSE("statements", 5), OPEN_QUESTIONS("questions", 3);

        private final String collection;
        private final int count;

        Resource(String collection, int count) {
            this.collection = collection;
            this.count = count;
        }
    }

    private record Harness(RagQueryService service, LlmClient llm) {
    }

    private record GenerationResult(int count, GenerationMetadata metadata) {
    }
}
