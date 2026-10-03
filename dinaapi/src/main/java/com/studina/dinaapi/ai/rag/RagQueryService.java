package com.studina.dinaapi.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.google.cloud.modelarmor.v1.SanitizeModelResponseResponse;
import com.google.cloud.modelarmor.v1.SanitizeUserPromptResponse;
import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.ai.llm.LlmModel;
import com.studina.dinaapi.ai.llm.LlmRequest;
import com.studina.dinaapi.ai.llm.LlmResponse;
import com.studina.dinaapi.ai.llm.LlmUseCase;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.chat.memory.ConversationContext;
import com.studina.dinaapi.learning.Topic;

@Service
public class RagQueryService {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(RagQueryService.class);

    /** Tope de la respuesta cruda que se loguea: alcanza para ver qué devolvió el modelo. */
    private static final int LOGGED_RESPONSE_LENGTH = 1500;

    private final LlmClient llmClient;
    private final VectorStore vectorStore;
    private final ModelArmorService modelArmorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagQueryService(
            ModelArmorService modelArmorService,
            LlmClient llmClient,
            VectorStore vectorStore
    ) {

        this.llmClient = llmClient;
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

        String response = llmClient.generate(LlmRequest.of(
                """
                        Sos Dina, la asistente educativa de Studina. Generás flashcards
                        exclusivamente a partir del contexto recibido.

                        Respondé únicamente JSON válido, sin Markdown ni texto adicional,
                        con esta forma exacta:
                        {"title":"nombre corto del set","cards":[{"term":"...","definition":"...","topic_id":"uuid-del-topic"}]}

                        Reglas:
                        - El title nombra el tema que cubren las tarjetas, en 2 a 5 palabras y menos de 60 caracteres. Sin comillas, sin punto final y sin la palabra "flashcards".
                        - Generá exactamente %d tarjetas útiles, ni una más ni una menos, sin repetir conceptos.
                        - No agregues información externa al contexto.
                        - Usá español claro y respuestas autocontenidas.
                        - Cada tarjeta debe tener exactamente un topic_id perteneciente a los temas válidos.
                        - El formato pedido y la dificultad pedida no son sugerencias: todas las tarjetas los cumplen.

                        %s

                        %s
                        """.formatted(requestedCardCount, formatInstruction, difficultyInstruction),
                """
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
                )
        ).forUseCase(LlmUseCase.GENERATION)).content();

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

        GeneratedFlashcards generated = parseFlashcards(response, requestedCardCount,
                availableTopics.stream().map(topic -> String.valueOf(topic.id())).collect(Collectors.toSet()));
        return new FlashcardGenerationResponse(
                generated.cards(),
                generated.title(),
                relevantChunks.size()
        );
    }

    private int resolveCardCount(Integer cardCount) {
        if (cardCount == null) {
            return DEFAULT_CARD_COUNT;
        }
        return Math.max(MIN_CARD_COUNT, Math.min(MAX_CARD_COUNT, cardCount));
    }

    /**
     * El formato elegido por el estudiante, explicado campo por campo.
     *
     * <p>Decirle solamente "formato pregunta/respuesta" no alcanza: el modelo devolvía términos
     * sueltos igual. Por eso cada caso define qué va en {@code term}, qué va en {@code definition}
     * y cuál es el error que no se acepta, con un ejemplo de cada uno.
     */
    private String resolveFormatInstruction(String cardFormat) {
        if ("question_answer".equals(cardFormat)) {
            return """
                    Formato de tarjeta pedido: Pregunta / Respuesta. Significa que:
                    - "term" es una pregunta completa dirigida al estudiante, entre signos de interrogación. Ejemplo: "¿Qué garantiza la tercera forma normal?".
                    - "definition" es la respuesta a esa pregunta puntual, no la definición general del concepto.
                    - Una tarjeta con un concepto, un término suelto o un título en "term" está mal: si no es una pregunta, reescribila.""";
        }
        return """
                Formato de tarjeta pedido: Término / Definición. Significa que:
                - "term" es el nombre del concepto, en 1 a 5 palabras, sin preguntas y sin signos de interrogación. Ejemplo: "Tercera forma normal".
                - "definition" es qué es ese concepto y para qué sirve.
                - Una tarjeta con una pregunta en "term" está mal: si termina en "?", reescribila como concepto.""";
    }

    /**
     * La dificultad elegida por el estudiante, traducida a decisiones concretas.
     *
     * <p>La usan flashcards y cuestionarios, así que habla de qué contenido elegir y cuánto
     * conocimiento previo asumir, sin nombrar tarjetas ni preguntas.
     */
    private String resolveDifficultyInstruction(String difficulty) {
        return switch (difficulty == null ? "" : difficulty) {
            case "intermediate" -> """
                    Dificultad pedida: Intermedia. Significa que:
                    - El estudiante ya maneja el vocabulario básico del tema: no vuelvas a definirlo.
                    - Trabajá relaciones entre conceptos, diferencias entre casos parecidos y condiciones de aplicación, no definiciones aisladas.
                    - Los textos pueden llegar a 3 oraciones cuando el matiz lo justifica.""";
            case "advanced" -> """
                    Dificultad pedida: Avanzada. Significa que:
                    - El estudiante ya domina las definiciones: no ocupes lugar en conceptos introductorios.
                    - Apuntá a detalles técnicos, excepciones, casos límite, consecuencias y comparaciones finas entre conceptos del contexto.
                    - No simplifiques ni sacrifiques precisión técnica, pero no pases de 3 oraciones por texto.""";
            default -> """
                    Dificultad pedida: Básica. Significa que:
                    - Cubrí los conceptos centrales del contexto y dejá afuera los detalles secundarios.
                    - No asumas conocimientos previos: si aparece un término técnico, explicalo ahí mismo.
                    - Enfocate en qué es cada cosa y para qué sirve; evitá casos límite y excepciones.
                    - Usá oraciones simples, de 1 a 2 por texto.""";
        };
    }

    private static String truncate(String response) {
        if (response == null) {
            return "(vacía)";
        }
        return response.length() <= LOGGED_RESPONSE_LENGTH
                ? response
                : response.substring(0, LOGGED_RESPONSE_LENGTH) + "... (truncada)";
    }

    /**
     * Lo que devuelve la IA al generar flashcards, antes de validarlo.
     *
     * <p>Tolera claves de más en el nivel de arriba —un resumen, una nota— porque no afectan
     * a las tarjetas. Adentro de cada tarjeta, en cambio, el rechazo de campos desconocidos
     * es deliberado: es lo que detecta el payload viejo con {@code topic_ids} y evita guardar
     * tarjetas sin tema.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record GeneratedFlashcards(String title, List<Flashcard> cards) {
    }

    GeneratedFlashcards parseFlashcards(
            String response,
            int requestedCardCount,
            java.util.Set<String> validTopicIds) {
        try {
            GeneratedFlashcards parsed = objectMapper.readValue(response, GeneratedFlashcards.class);
            logger.info("La IA devolvió {} tarjetas (se pidieron {}), título propuesto: {}",
                    parsed.cards() == null ? 0 : parsed.cards().size(), requestedCardCount, parsed.title());
            return new GeneratedFlashcards(
                    GeneratedResourcePolicy.cleanTitle(parsed.title()),
                    GeneratedResourcePolicy.identifyCards(
                            parsed.cards(), requestedCardCount, validTopicIds));
        } catch (IllegalArgumentException exception) {
            // La validación rechazó el lote. Sin la respuesta cruda no hay forma de saber
            // si el modelo devolvió de menos, inventó un topic o mandó un campo vacío.
            logger.warn("La IA devolvió flashcards que no pasaron la validación: {}. Respuesta cruda: {}",
                    exception.getMessage(), truncate(response));
            throw exception;
        } catch (JsonProcessingException exception) {
            logger.warn("No se pudo parsear la respuesta de flashcards. Respuesta cruda: {}", truncate(response));
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

        String response = llmClient.generate(LlmRequest.of(
                """
                        Sos Dina, la asistente educativa de Studina. Generás cuestionarios
                        exclusivamente a partir del contexto recibido.

                        Respondé únicamente JSON válido, sin Markdown ni texto adicional,
                        con esta forma exacta:
                        {"title":"nombre corto del cuestionario","questions":[{"question":"...","options":[{"id":"a","text":"..."}],"correctOptionId":"a","explanation":"...","topic_id":"uuid-del-topic"}]}

                        Reglas:
                        - El title nombra el tema que cubren las preguntas, en 2 a 5 palabras y menos de 60 caracteres. Sin comillas, sin punto final y sin la palabra "cuestionario".
                        - Generá exactamente %d preguntas, ni una más ni una menos.
                        - Cada pregunta debe tener exactamente %d opciones.
                        - Debe existir una sola opción correcta por pregunta.
                        - No repitas preguntas ni agregues información externa.
                        - Asigná a cada pregunta exactamente un topic_id de la lista permitida: el concepto principal que evalúa.
                        - Incluí una explicación breve.
                        - La dificultad pedida no es una sugerencia: todas las preguntas la cumplen.

                        %s
                        """.formatted(
                        requestedQuestionCount,
                        requestedAnswerCount,
                        resolveDifficultyInstruction(difficulty)
                ),
                """
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
                )
        ).forUseCase(LlmUseCase.GENERATION)).content();

        GeneratedQuiz generated =
                parseQuizQuestions(response, requestedQuestionCount, requestedAnswerCount, validTopicIds);
        return new QuizGenerationResponse(
                generated.questions(),
                generated.title(),
                relevantChunks.size()
        );
    }

    /** Lo que devuelve la IA al generar un cuestionario. Misma tolerancia que el de flashcards. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record GeneratedQuiz(String title, List<QuizQuestion> questions) {
    }

    GeneratedQuiz parseQuizQuestions(
            String response,
            int requestedQuestionCount,
            int requestedAnswerCount,
            Set<String> validTopicIds) {
        try {
            GeneratedQuiz parsed = objectMapper.readValue(response, GeneratedQuiz.class);
            logger.info("La IA devolvió {} preguntas (se pidieron {}), título propuesto: {}",
                    parsed.questions() == null ? 0 : parsed.questions().size(),
                    requestedQuestionCount, parsed.title());
            return new GeneratedQuiz(
                    GeneratedResourcePolicy.cleanTitle(parsed.title()),
                    GeneratedResourcePolicy.identifyQuestions(
                            parsed.questions(), requestedQuestionCount, requestedAnswerCount, validTopicIds));
        } catch (IllegalArgumentException exception) {
            logger.warn("La IA devolvió un cuestionario que no pasó la validación: {}. Respuesta cruda: {}",
                    exception.getMessage(), truncate(response));
            throw exception;
        } catch (JsonProcessingException exception) {
            logger.warn("No se pudo parsear la respuesta del cuestionario. Respuesta cruda: {}", truncate(response));
            throw new IllegalArgumentException(
                    "La IA no devolvió un cuestionario válido. Intentá generarlo nuevamente",
                    exception);
        }
    }

    public RagChatResponse answer(
            String message,
            List<String> fileIds
    ) {
        return answer(
                message,
                fileIds,
                ConversationContext.empty()
        );
    }

    public RagChatResponse answer(
            String message,
            List<String> fileIds,
            ConversationContext conversationContext
    ) {

        validateRequest(
                message,
                fileIds
        );

        String filterExpression
                = buildFileIdsFilter(
                        fileIds
                );

        List<Document> relevantChunks
                = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(message)
                                .topK(5)
                                .filterExpression(
                                        filterExpression
                                )
                                .build()
                );

        if (relevantChunks == null
                || relevantChunks.isEmpty()) {
            LlmModel model = llmClient.model();
            return new RagChatResponse(
                    "No encontré información suficiente en los documentos seleccionados.",
                    0,
                    List.of(),
                    model.provider(),
                    model.model()
            );
        }

        String context
                = buildContext(
                        relevantChunks
                );

        LlmResponse llmResponse
                = generateAnswer(
                        message,
                        context,
                        conversationContext
                );

        List<RagChunkInfo> chunkInfo
                = relevantChunks.stream()
                        .map(this::toChunkInfo)
                        .toList();

        return new RagChatResponse(
                llmResponse.content(),
                relevantChunks.size(),
                chunkInfo,
                llmResponse.provider(),
                llmResponse.model()
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
                .anyMatch(fileId
                        -> fileId == null || fileId.isBlank()
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

    private LlmResponse generateAnswer(
            String message,
            String context,
            ConversationContext conversationContext) {

        try {
            if (modelArmorService.isEnabled()) {
                SanitizeUserPromptResponse sanitizedPrompt = modelArmorService.sanitizeUserPrompt(message);

                if (!modelArmorService.isPromptSafe(sanitizedPrompt)) {
                    return localResponse(modelArmorService.sanitizationErrorMessage());
                }
            }
        } catch (Exception e) {
            return localResponse(e.getMessage() != null ? e.getMessage() : "Error desconocido");
        }

        List<Message> modelContext
                = new ArrayList<>();

        if (conversationContext != null) {
            modelContext.addAll(
                    conversationContext.recentMessages()
            );
        }

        String systemPrompt = """
        Sos Dina, la asistente educativa de Studina.
        No hace falta que saludes siempre.

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
        """;

        if (conversationContext != null
                && conversationContext.summary() != null
                && !conversationContext.summary().isBlank()) {

            systemPrompt += """

            Memoria resumida de la conversación anterior:

            %s

            Usá esta memoria únicamente para mantener continuidad
            con la conversación.

            Para responder sobre el contenido académico de los
            documentos, seguí usando únicamente el contexto
            recuperado de los documentos seleccionados en esta
            consulta.
            """.formatted(
                    conversationContext.summary()
            );
        }
        LlmResponse response = llmClient.generate(LlmRequest.of(
                systemPrompt,
                modelContext,
                """
                Contexto recuperado:

                %s

                Pregunta del estudiante:

                %s
                """.formatted(context, message)
        ));
        String answer = response.content();

        try {
            if(modelArmorService.isEnabled()) {
                SanitizeModelResponseResponse sanitizedPrompt = modelArmorService.sanitizeModelResponse(answer);
                
                if(!modelArmorService.isAnswerSafe(sanitizedPrompt)) {
                    return response.withContent(modelArmorService.sanitizationErrorMessage());
                }
            }
        } catch(Exception e) {
            return response.withContent(
                    e.getMessage() != null ? e.getMessage() : "Error desconocido");
        }

        return response.withContent(answer != null ? answer : "");
    }

    private LlmResponse localResponse(String content) {
        LlmModel model = llmClient.model();
        return new LlmResponse(content, model.provider(), model.model());
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
