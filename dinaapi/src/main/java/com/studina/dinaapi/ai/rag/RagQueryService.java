package com.studina.dinaapi.ai.rag;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import com.studina.dinaapi.learning.Topic;

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
            String difficulty,
            List<Topic> availableTopics) {

        validateRequest("Generar flashcards", fileIds);

        int requestedCardCount = resolveCardCount(cardCount);
        String formatInstruction = resolveFormatInstruction(cardFormat);
        String difficultyInstruction = resolveDifficultyInstruction(difficulty);
        if (availableTopics == null || availableTopics.isEmpty()) {
            throw new IllegalArgumentException(
                    "No hay temas canónicos disponibles para esta materia. Subí e indexá un documento primero"
            );
        }
        String validTopics = availableTopics.stream()
                .map(topic -> "{\"id\":\"" + topic.id() + "\",\"name\":\""
                        + topic.name().replace("\"", "\\\"") + "\"}")
                .collect(Collectors.joining(","));

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
                        {"cards":[{"term":"concepto o pregunta","definition":"respuesta breve y precisa","topic_id":"uuid-del-topic"}]}

                        Reglas:
                        - Generá exactamente %d tarjetas útiles, ni una más ni una menos, sin repetir conceptos.
                        - %s
                        - %s
                        - No agregues información externa al contexto.
                        - Usá español claro y respuestas autocontenidas.
                        - Cada tarjeta debe tener exactamente un topic_id perteneciente a los temas válidos.
                        """.formatted(requestedCardCount, formatInstruction, difficultyInstruction))
                .user("""
                        Contexto recuperado:

                        %s

                        Indicaciones adicionales del estudiante:

                        %s

                        Temas válidos de la materia (usá solamente sus IDs):
                        [%s]
                        """.formatted(
                        buildContext(relevantChunks),
                        instructions == null || instructions.isBlank()
                                ? "Ninguna. Priorizá los conceptos centrales."
                                : instructions,
                        validTopics
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
                parseFlashcards(response, requestedCardCount,
                        availableTopics.stream().map(topic -> String.valueOf(topic.id())).collect(Collectors.toSet())),
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

    private List<Flashcard> parseFlashcards(
            String response,
            int requestedCardCount,
            java.util.Set<String> validTopicIds) {
        try {
            Map<String, List<Flashcard>> parsed = objectMapper.readValue(
                    response,
                    new TypeReference<>() {}
            );
            return GeneratedResourcePolicy.identifyCards(
                    parsed.get("cards"), requestedCardCount, validTopicIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "La IA no devolvió flashcards válidas. Intentá generarlas nuevamente",
                    exception
            );
        }
    }

    private static final int DEFAULT_QUESTION_COUNT = 10;
    private static final int DEFAULT_ANSWER_COUNT = 4;

    public QuizGenerationResponse generateQuiz(
            String instructions,
            List<String> fileIds,
            Integer questionCount,
            Integer answerCount,
            String difficulty,
            List<Topic> availableTopics) {

        validateRequest("Generar cuestionario", fileIds);
        int requestedQuestionCount = Math.max(5, Math.min(20,
                questionCount == null ? DEFAULT_QUESTION_COUNT : questionCount));
        int requestedAnswerCount = Math.max(2, Math.min(4,
                answerCount == null ? DEFAULT_ANSWER_COUNT : answerCount));
        if (availableTopics == null || availableTopics.isEmpty()) {
            throw new IllegalArgumentException("No hay topics disponibles para clasificar las preguntas");
        }
        Set<String> validTopicIds = availableTopics.stream()
                .map(topic -> String.valueOf(topic.id()))
                .collect(Collectors.toSet());
        String topicsJson = availableTopics.stream()
                .map(topic -> "{\"id\":\"" + topic.id() + "\",\"name\":\""
                        + topic.name().replace("\"", "\\\"") + "\"}")
                .collect(Collectors.joining(","));

        List<Document> relevantChunks = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(instructions == null || instructions.isBlank()
                                ? "conceptos principales y preguntas de evaluación"
                                : instructions)
                        .topK(12)
                        .filterExpression(buildFileIdsFilter(fileIds))
                        .build()
        );

        if (relevantChunks == null || relevantChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "No encontré información indexada en los documentos seleccionados");
        }

        String response = chatClient.prompt()
                .system("""
                        Sos Dina, la asistente educativa de Studina. Generás cuestionarios
                        exclusivamente a partir del contexto recibido.

                        Respondé únicamente JSON válido, sin Markdown ni texto adicional,
                        con esta forma exacta:
                        {"questions":[{"question":"...","options":[{"id":"a","text":"..."}],"correctOptionId":"a","explanation":"...","topic_id":"uuid-del-topic"}]}

                        Reglas:
                        - Generá exactamente %d preguntas, ni una más ni una menos.
                        - Cada pregunta debe tener exactamente %d opciones.
                        - Debe existir una sola opción correcta por pregunta.
                        - No repitas preguntas ni agregues información externa.
                        - Asigná a cada pregunta exactamente un topic_id de la lista permitida: el concepto principal que evalúa.
                        - Dificultad: %s.
                        - Incluí una explicación breve.
                        """.formatted(
                        requestedQuestionCount,
                        requestedAnswerCount,
                        resolveDifficultyInstruction(difficulty)
                ))
                .user("""
                        Contexto recuperado:

                        %s

                        Topics permitidos (UUID y nombre):
                        [%s]

                        Indicaciones adicionales del estudiante:

                        %s
                        """.formatted(
                        buildContext(relevantChunks),
                        topicsJson,
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

        return new QuizGenerationResponse(
                parseQuizQuestions(response, requestedQuestionCount, requestedAnswerCount, validTopicIds),
                relevantChunks.size()
        );
    }

    private List<QuizQuestion> parseQuizQuestions(
            String response,
            int requestedQuestionCount,
            int requestedAnswerCount,
            Set<String> validTopicIds) {
        try {
            Map<String, List<QuizQuestion>> parsed = objectMapper.readValue(
                    response, new TypeReference<>() {});
            return GeneratedResourcePolicy.identifyQuestions(
                    parsed.get("questions"), requestedQuestionCount, requestedAnswerCount, validTopicIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "La IA no devolvió un cuestionario válido. Intentá generarlo nuevamente",
                    exception);
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
