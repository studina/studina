package com.studina.dinaapi.ai.rag;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.cloud.modelarmor.v1.SanitizeModelResponseResponse;
import com.google.cloud.modelarmor.v1.SanitizeUserPromptResponse;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;

@Service
public class RagQueryService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ModelArmorService modelArmorService;
        private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${GEMINI_MODEL}")
    private String configuredModel;

    @Value("${GEMINI_TEMPERATURE:0.2}")
    private Double configuredTemperature;

    public RagQueryService(
            ModelArmorService modelArmorService,
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore
        ) {

        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.modelArmorService = modelArmorService;
    }

    private static final int DEFAULT_CARD_COUNT = 10;
    private static final int MIN_CARD_COUNT = 5;
    private static final int MAX_CARD_COUNT = 20;

    public FlashcardGenerationResponse generateFlashcards(
            String instructions,
            List<String> fileIds,
            Integer cardCount,
            String cardFormat,
            String difficulty) {

        validateRequest("Generar flashcards", fileIds);

        int requestedCardCount = resolveCardCount(cardCount);
        String formatInstruction = resolveFormatInstruction(cardFormat);
        String difficultyInstruction = resolveDifficultyInstruction(difficulty);

                try {
                        if (modelArmorService.isEnabled()) {
                                SanitizeUserPromptResponse sanitizedPrompt =
                                                modelArmorService.sanitizeUserPrompt(instructions == null ? "" : instructions);
                                if (!modelArmorService.isPromptSafe(sanitizedPrompt)) {
                                        throw new IllegalArgumentException(
                                                        modelArmorService.sanitizationErrorMessage()
                                        );
                                }
                        }
                } catch (IllegalArgumentException exception) {
                        throw exception;
                } catch (Exception exception) {
                        throw new IllegalArgumentException(
                                        exception.getMessage() != null ? exception.getMessage() : "Error desconocido",
                                        exception
                        );
                }

        List<Document> relevantChunks = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(instructions == null || instructions.isBlank()
                                ? "conceptos principales, definiciones y relaciones"
                                : instructions)
                        .topK(12)
                        .filterExpression(buildFileIdsFilter(fileIds))
                        .build()
        );

        if (relevantChunks == null || relevantChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "No encontré información indexada en los documentos seleccionados"
            );
        }

        String response = chatClient.prompt()
                .system("""
                        Sos Dina, la asistente educativa de Studina. Generás flashcards
                        exclusivamente a partir del contexto recibido.

                        Respondé únicamente JSON válido, sin Markdown ni texto adicional,
                        con esta forma exacta:
                        {"cards":[{"term":"concepto o pregunta","definition":"respuesta breve y precisa"}]}

                        Reglas:
                        - Generá exactamente %d tarjetas útiles, ni una más ni una menos, sin repetir conceptos.
                        - %s
                        - %s
                        - No agregues información externa al contexto.
                        - Usá español claro y respuestas autocontenidas.
                        """.formatted(requestedCardCount, formatInstruction, difficultyInstruction))
                .user("""
                        Contexto recuperado:

                        %s

                        Indicaciones adicionales del estudiante:

                        %s
                        """.formatted(
                        buildContext(relevantChunks),
                        instructions == null || instructions.isBlank()
                                ? "Ninguna. Priorizá los conceptos centrales."
                                : instructions
                ))
                .options(
                        GoogleGenAiChatOptions.builder()
                                .model(configuredModel)
                                .temperature(configuredTemperature)
                )
                .call()
                .content();

                try {
                        if (modelArmorService.isEnabled()) {
                                SanitizeModelResponseResponse sanitizedResponse =
                                                modelArmorService.sanitizeModelResponse(response);
                                if (!modelArmorService.isAnswerSafe(sanitizedResponse)) {
                                        throw new IllegalArgumentException(
                                                        modelArmorService.sanitizationErrorMessage()
                                        );
                                }
                        }
                } catch (IllegalArgumentException exception) {
                        throw exception;
                } catch (Exception exception) {
                        throw new IllegalArgumentException(
                                        exception.getMessage() != null ? exception.getMessage() : "Error desconocido",
                                        exception
                        );
                }

        return new FlashcardGenerationResponse(
                parseFlashcards(response, requestedCardCount),
                relevantChunks.size()
        );
    }

    private int resolveCardCount(Integer cardCount) {
        if (cardCount == null) {
            return DEFAULT_CARD_COUNT;
        }
        return Math.max(MIN_CARD_COUNT, Math.min(MAX_CARD_COUNT, cardCount));
    }

    private String resolveFormatInstruction(String cardFormat) {
        if ("question_answer".equals(cardFormat)) {
            return "Formato: poné en \"term\" una pregunta sobre el contenido y en \"definition\" su respuesta.";
        }
        return "Formato: poné en \"term\" un concepto o término clave y en \"definition\" su definición.";
    }

    private String resolveDifficultyInstruction(String difficulty) {
        return switch (difficulty == null ? "" : difficulty) {
            case "intermediate" -> "Dificultad intermedia: asumí conocimientos básicos previos y profundizá un poco más en matices y relaciones entre conceptos.";
            case "advanced" -> "Dificultad avanzada: profundizá en detalles técnicos, matices y relaciones complejas entre conceptos, sin simplificar de más.";
            default -> "Dificultad básica: explicá de forma simple, sin asumir conocimientos previos.";
        };
    }

    private List<Flashcard> parseFlashcards(String response, int requestedCardCount) {
        try {
            Map<String, List<Flashcard>> parsed = objectMapper.readValue(
                    response,
                    new TypeReference<>() {}
            );
            List<Flashcard> cards = parsed.get("cards");

            if (cards == null || cards.isEmpty()
                    || cards.stream().anyMatch(card -> card == null
                    || card.term() == null || card.term().isBlank()
                    || card.definition() == null || card.definition().isBlank())) {
                throw new IllegalArgumentException(
                        "La IA no devolvió flashcards válidas. Intentá generarlas nuevamente"
                );
            }

            return cards.stream().limit(requestedCardCount).toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "La IA no devolvió flashcards válidas. Intentá generarlas nuevamente",
                    exception
            );
        }
    }

    public RagChatResponse answer(
            String message,
            List<String> fileIds) {

        validateRequest(message, fileIds);

        String filterExpression =
                buildFileIdsFilter(fileIds);

        List<Document> relevantChunks =
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(message)
                                .topK(5)
                                .filterExpression(filterExpression)
                                .build()
                );

        if (relevantChunks == null || relevantChunks.isEmpty()) {
            return new RagChatResponse(
                    "No encontré información suficiente en los documentos seleccionados.",
                    0,
                    List.of()
            );
        }

        String context = buildContext(relevantChunks);

        String answer = generateAnswer(
                message,
                context
        );

        List<RagChunkInfo> chunkInfo =
                relevantChunks.stream()
                        .map(this::toChunkInfo)
                        .toList();

        return new RagChatResponse(
                answer,
                relevantChunks.size(),
                chunkInfo
        );
    }

    private void validateRequest(
            String message,
            List<String> fileIds) {

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "El mensaje es obligatorio"
            );
        }

        if (fileIds == null || fileIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tenés que seleccionar al menos un archivo"
            );
        }

        boolean containsInvalidId = fileIds.stream()
                .anyMatch(fileId ->
                        fileId == null || fileId.isBlank()
                );

        if (containsInvalidId) {
            throw new IllegalArgumentException(
                    "Hay un identificador de archivo inválido"
            );
        }
    }

    private String buildContext(
            List<Document> documents) {

        return documents.stream()
                .map(document -> {
                    Object fileId =
                            document.getMetadata().get("fileId");

                    Object filename =
                            document.getMetadata().get("filename");

                    Object chunkIndex =
                            getChunkIndex(document);

                    return """
                            Documento: %s
                            File ID: %s
                            Fragmento: %s

                            %s
                            """.formatted(
                                    filename != null
                                            ? filename
                                            : "Documento sin nombre",
                                    fileId,
                                    chunkIndex,
                                    document.getText()
                            );
                })
                .collect(
                        Collectors.joining(
                                "\n\n---\n\n"
                        )
                );
    }

    private String generateAnswer(
            String message,
            String context) {

        try {
            if(modelArmorService.isEnabled()) {
                SanitizeUserPromptResponse sanitizedPrompt = modelArmorService.sanitizeUserPrompt(message);
                
                if(!modelArmorService.isPromptSafe(sanitizedPrompt)) {
                    return modelArmorService.sanitizationErrorMessage();
                }
            }
        } catch(Exception e) {
            return e.getMessage() != null ? e.getMessage() : "Error desconocido";
        }

        String answer = chatClient.prompt()
                .system("""
                        Sos Dina, la asistente educativa de Studina.

                        Respondé utilizando únicamente el contexto recuperado
                        de los documentos seleccionados.

                        Reglas:
                        - No agregues información externa.
                        - Si el contexto no alcanza, indicá que no encontraste
                          información suficiente.
                        - Respondé de forma clara, breve y didáctica.
                        - Usá Markdown cuando ayude a organizar la respuesta.
                        - No menciones embeddings, fragmentos, pgvector ni
                          detalles técnicos internos del sistema.
                        """)
                .user("""
                        Contexto recuperado:

                        %s

                        Pregunta del estudiante:

                        %s
                        """.formatted(context, message))
                .options(
                        GoogleGenAiChatOptions.builder()
                                .model(configuredModel)
                                .temperature(configuredTemperature)
                )
                .call()
                .content();

        try {
            if(modelArmorService.isEnabled()) {
                SanitizeModelResponseResponse sanitizedPrompt = modelArmorService.sanitizeModelResponse(answer);
                
                if(!modelArmorService.isAnswerSafe(sanitizedPrompt)) {
                    return modelArmorService.sanitizationErrorMessage();
                }
            }
        } catch(Exception e) {
            return e.getMessage() != null ? e.getMessage() : "Error desconocido";
        }

        return answer != null ? answer : "";
    }

    private RagChunkInfo toChunkInfo(
            Document document) {

        Object fileId =
                document.getMetadata().get("fileId");

        Integer chunkIndex =
                parseInteger(getChunkIndex(document));

        Double documentScore = document.getScore();
        double score = documentScore != null
                ? documentScore
                : 0.0;

        return new RagChunkInfo(
                Objects.toString(fileId, ""),
                chunkIndex,
                score
        );
    }

    private Object getChunkIndex(
            Document document) {

        Object chunkIndex =
                document.getMetadata().get("chunkIndex");

        // Compatibilidad temporal con fragmentos antiguos
        if (chunkIndex == null) {
            chunkIndex =
                    document.getMetadata().get("chunk_index");
        }

        return chunkIndex;
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.intValue();
        }

        try {
            return Integer.valueOf(value.toString());

        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String buildFileIdsFilter(
            List<String> fileIds) {

        String values = fileIds.stream()
                .map(this::escapeFilterValue)
                .map(fileId -> "'" + fileId + "'")
                .collect(Collectors.joining(", "));

        return "fileId in [" + values + "]";
    }

    private String escapeFilterValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'");
    }
}
