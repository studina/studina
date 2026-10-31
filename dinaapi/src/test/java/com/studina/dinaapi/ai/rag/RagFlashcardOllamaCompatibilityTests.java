package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.ai.llm.GenerationContextBudget;
import com.studina.dinaapi.ai.llm.LlmModel;
import com.studina.dinaapi.ai.llm.LlmRequest;
import com.studina.dinaapi.ai.llm.LlmResponse;
import com.studina.dinaapi.ai.llm.LlmUseCase;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.learning.Topic;

class RagFlashcardOllamaCompatibilityTests {
    private static final String MATERIAL = "La patente protege una invención; la marca identifica productos. ".repeat(100);
    private static final List<Topic> TOPICS = List.of(new Topic("topic-a", "subject-a", "Propiedad intelectual", ""));

    @ParameterizedTest
    @MethodSource("choices")
    void preservesOriginalPromptsAndStudyChoicesWhileBoundingOnlyLocalContext(int count, String difficulty, String format) {
        Captured google = generate("google-genai", count, difficulty, format);
        Captured ollama = generate("ollama", count, difficulty, format);
        assertThat(ollama.request().systemPrompt()).isEqualTo(google.request().systemPrompt())
                .contains("exactamente " + count + " tarjetas", "No agregues información externa al contexto");
        assertThat(google.request().userPrompt()).contains(MATERIAL, "Documento: Apunte.pdf");
        assertThat(ollama.request().userPrompt()).doesNotContain(MATERIAL)
                .contains(MATERIAL.substring(0, 200), "Priorizá las diferencias", "topic-a");
        assertThat(GenerationContextBudget.utf8Length(ollama.request().systemPrompt())
                + GenerationContextBudget.utf8Length(ollama.request().userPrompt())
                + GenerationContextBudget.TEMPLATE_RESERVE_BYTES)
                .isLessThanOrEqualTo(GenerationContextBudget.MAX_INPUT_BYTES);
        String suffix = "Indicaciones adicionales del estudiante:";
        assertThat(ollama.request().userPrompt().substring(ollama.request().userPrompt().indexOf(suffix)))
                .isEqualTo(google.request().userPrompt().substring(google.request().userPrompt().indexOf(suffix)));
        for (Captured result : List.of(google, ollama)) {
            assertThat(result.response().cards()).hasSize(count).allSatisfy(card -> {
                assertThat(card.topicId()).isEqualTo("topic-a");
                assertThat(card.id()).isNotBlank();
                assertThat(card.definition()).isEqualTo("Según el apunte.");
            });
            JsonNode schema = new ObjectMapper().valueToTree(result.request().jsonSchema());
            assertThat(schema.at("/properties/cards/minItems").asInt()).isEqualTo(count);
            assertThat(schema.at("/properties/cards/maxItems").asInt()).isEqualTo(count);
            assertThat(schema.at("/properties/cards/items/properties/topic_id/enum/0").asText()).isEqualTo("topic-a");
        }
    }

    private Captured generate(String provider, int count, String difficulty, String format) {
        var llm = mock(LlmClient.class);
        when(llm.model(LlmUseCase.GENERATION, "user-a")).thenReturn(new LlmModel(provider, "test-model"));
        AtomicReference<LlmRequest> request = new AtomicReference<>();
        String cards = IntStream.range(0, count).mapToObj(index ->
                "{\"term\":\"Concepto " + index + "\",\"definition\":\"Según el apunte.\",\"topic_id\":\"topic-a\"}")
                .collect(Collectors.joining(","));
        when(llm.generate(any())).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return new LlmResponse("{\"title\":\"Propiedad intelectual\",\"cards\":[" + cards + "]}", provider, "test-model");
        });
        var vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(MATERIAL, Map.of("filename", "Apunte.pdf", "fileId", "file-a"))));
        var service = new RagQueryService(mock(ModelArmorService.class), llm, vectorStore);
        var response = service.generateFlashcards("Priorizá las diferencias", List.of("file-a"), count,
                format, difficulty, TOPICS, "user-a");
        return new Captured(request.get(), response);
    }

    static Stream<Arguments> choices() {
        return Stream.of("term_definition", "question_answer").flatMap(format -> Stream.of(
                Arguments.of(5, "basic", format), Arguments.of(6, "intermediate", format),
                Arguments.of(10, "advanced", format)));
    }

    private record Captured(LlmRequest request, FlashcardGenerationResponse response) {
    }
}
