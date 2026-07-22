package com.studina.dinaapi.ai.rag;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagQueryService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    @Value("${GEMINI_MODEL}")
    private String configuredModel;

    @Value("${GEMINI_TEMPERATURE:0.2}")
    private Double configuredTemperature;

    public RagQueryService(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore) {

        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
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
                                .similarityThreshold(0.55)
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