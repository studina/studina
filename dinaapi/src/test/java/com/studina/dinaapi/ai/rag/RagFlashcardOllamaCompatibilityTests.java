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

import org.junit.jupiter.api.Test;
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

    @Test
    void supportsSevenFilesAndTheirFullTopicCatalogWhileSharingTheLocalMaterialBudget() {
        List<String> fileIds = IntStream.rangeClosed(1, 7)
                .mapToObj(index -> "20000000-0000-4000-8000-%012d".formatted(index)).toList();
        List<String> topicNames = List.of(
                "Marco jurídico de la actividad empresaria", "Personas humanas y jurídicas",
                "Sociedades comerciales", "Contratos y obligaciones", "Responsabilidad civil de la empresa",
                "Protección del consumidor", "Propiedad intelectual", "Patentes de invención",
                "Marcas y designaciones", "Transferencia de tecnología", "Secretos comerciales",
                "Licencias de software", "Derechos de autor", "Comercio internacional",
                "Contratos de importación y exportación", "Incoterms y transporte internacional",
                "Régimen aduanero", "Tributación de la actividad empresarial", "Planificación fiscal",
                "Impuesto a las ganancias", "Convenios para evitar la doble imposición");
        List<Topic> topics = IntStream.range(0, topicNames.size())
                .mapToObj(index -> new Topic("10000000-0000-4000-8000-%012d".formatted(index + 1),
                        "subject-a", topicNames.get(index), "")).toList();
        List<Document> passages = IntStream.range(0, 12)
                .mapToObj(index -> new Document("Fragmento legal %02d: ".formatted(index + 1) + MATERIAL,
                        Map.of("filename", "Clase legal %d.pdf".formatted(index % 7 + 1),
                                "fileId", fileIds.get(index % 7)))).toList();
        String instructions = "Compará patentes, marcas y transferencia de tecnología únicamente con el material seleccionado.";

        Captured result = generate("ollama", 10, "basic", "term_definition", instructions, fileIds,
                topics, passages);

        LlmRequest request = result.request();
        assertThat(request).isNotNull();
        assertThat(result.response().cards()).hasSize(10);
        assertThat(request.userPrompt()).contains(instructions);
        topics.forEach(topic -> assertThat(request.userPrompt()).contains(topic.id(), topic.name()));
        JsonNode topicIds = new ObjectMapper().valueToTree(request.jsonSchema())
                .at("/properties/cards/items/properties/topic_id/enum");
        assertThat(topicIds.size()).isEqualTo(topics.size());
        IntStream.range(0, topics.size()).forEach(index ->
                assertThat(topicIds.get(index).asText()).isEqualTo(topics.get(index).id()));

        String prefix = "Contexto recuperado:\n\n";
        int contextStart = request.userPrompt().indexOf(prefix) + prefix.length();
        int contextEnd = request.userPrompt().indexOf("\n\nIndicaciones adicionales del estudiante:");
        String context = request.userPrompt().substring(contextStart, contextEnd);
        assertThat(context).isNotBlank();
        IntStream.rangeClosed(1, 12).forEach(index ->
                assertThat(context).contains("Fragmento legal %02d: ".formatted(index)));
        passages.forEach(passage -> assertThat(context).doesNotContain(passage.getText()));
        int promptBytes = GenerationContextBudget.utf8Length(request.systemPrompt())
                + GenerationContextBudget.utf8Length(request.userPrompt());
        assertThat(promptBytes + GenerationContextBudget.TEMPLATE_RESERVE_BYTES)
                .isGreaterThan(3072).isLessThanOrEqualTo(GenerationContextBudget.MAX_INPUT_BYTES);
        // Even with no retrieved material, the old 3072-byte budget could not fit this catalog.
        assertThat(promptBytes - GenerationContextBudget.utf8Length(context))
                .isGreaterThan(3072 - GenerationContextBudget.TEMPLATE_RESERVE_BYTES - 256);
    }

    private Captured generate(String provider, int count, String difficulty, String format) {
        return generate(provider, count, difficulty, format, "Priorizá las diferencias", List.of("file-a"),
                TOPICS, List.of(new Document(MATERIAL, Map.of("filename", "Apunte.pdf", "fileId", "file-a"))));
    }

    private Captured generate(String provider, int count, String difficulty, String format, String instructions,
            List<String> fileIds, List<Topic> topics, List<Document> passages) {
        var llm = mock(LlmClient.class);
        when(llm.model(LlmUseCase.GENERATION, "user-a")).thenReturn(new LlmModel(provider, "test-model"));
        AtomicReference<LlmRequest> request = new AtomicReference<>();
        String cards = IntStream.range(0, count).mapToObj(index ->
                "{\"term\":\"Concepto " + index + "\",\"definition\":\"Según el apunte.\",\"topic_id\":\""
                        + topics.get(index % topics.size()).id() + "\"}")
                .collect(Collectors.joining(","));
        when(llm.generate(any())).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return new LlmResponse("{\"title\":\"Propiedad intelectual\",\"cards\":[" + cards + "]}", provider, "test-model");
        });
        var vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(passages);
        var service = new RagQueryService(mock(ModelArmorService.class), llm, vectorStore);
        var response = service.generateFlashcards(instructions, fileIds, count,
                format, difficulty, topics, "user-a");
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
