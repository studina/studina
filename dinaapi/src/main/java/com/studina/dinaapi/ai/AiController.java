package com.studina.dinaapi.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import java.io.IOException;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.studina.dinaapi.directus.FileService;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final ChatClient chatClient;

    private final FileService fileService;

    private final VectorStore vectorStore;

    public AiController(
        ChatClient.Builder builder,
        FileService fileService,
        VectorStore vectorStore) {

    this.chatClient = builder.build();
    this.fileService = fileService;
    this.vectorStore = vectorStore;
}

    @Value("${GEMINI_MODEL}")
    private String configuredModel;

    @Value("${GEMINI_TEMPERATURE:0.2}")
    private Double configuredTemperature;

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'");
        }

        private String buildFileIdsFilter(List<String> fileIds) {
        String values = fileIds.stream()
                .map(this::escapeFilterValue)
                .map(id -> "'" + id + "'")
                .collect(java.util.stream.Collectors.joining(", "));

        return "fileId in [" + values + "]";
        }

    // ── Extraer texto de un PDF recibido como bytes ───────────────────────────
    private String extractPdfText(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
             PDFTextStripper textStripper = new PDFTextStripper();
             return textStripper.getText(document);
        }
     }

    // ── GET /ai/suggest-tags: sugerir tags según el nombre del archivo ────
    @GetMapping("/suggest-tags")
    public List<String> suggestTags(
            
            @RequestParam("filename") String filename) {

        String response = chatClient.prompt()
                .system("""
                        Generá entre 2 y 4 tags para clasificar un documento académico.

                        Reglas:
                        - Respondé únicamente con los tags separados por comas.
                        - Usá minúsculas.
                        - Reemplazá los espacios por guiones.
                        - No agregues explicaciones.
                        """)
                .user("Nombre del archivo: " + filename)
                .options(
                        GoogleGenAiChatOptions.builder()
                                .model(configuredModel)
                                .temperature(configuredTemperature)
                )
                .call()
                .content();
        if (response == null || response.isBlank()) {
            return List.of();
        }

        return Arrays.stream(response.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .toList();
    }

    // ── POST /ai/chat: enviar un mensaje al modelo ─────────────────────────────
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(
            @RequestBody Map<String, String> request) {

        String message = request.get("message");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "El mensaje es obligatorio"));
        }

        try {
            String answer = chatClient.prompt()
                    .system("""
                        Sos Dina, la asistente educativa de Studina.

                        Respondé de manera clara y didáctica.
                        No hace falta que te presentes. Simplemente respondé.
                        Formato:
                        - Usá párrafos cortos.
                        - Usá títulos solamente cuando sean útiles.
                        - Usá listas para enumeraciones.
                        - Resaltá conceptos importantes con negrita.
                        - Evitá escribir bloques de texto demasiado largos.
                        - No saludes nuevamente en cada respuesta.
                        """)
                    .user(message)
                    .options(
                            GoogleGenAiChatOptions.builder()
                                    .model(configuredModel)
                                    .temperature(configuredTemperature)
                    )
                    .call()
                    .content();

            return ResponseEntity.ok(
                    Map.of(
                            "message", message,
                            "answer", answer != null ? answer : ""
                    )
            );

        } catch (Exception e) {
            e.printStackTrace();

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error", "No se pudo obtener una respuesta de la IA",
                            "message", e.getMessage() != null
                                    ? e.getMessage()
                                    : "Error desconocido"
                    ));
        }
    }
        @PostMapping("/files/chat")
        public ResponseEntity<Map<String, Object>> chatWithRag(
                @RequestBody FileChatRequest request) {

        String message = request.message();
        List<String> fileIds = request.fileIds();

        if (message == null || message.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "El mensaje es obligatorio"
                        ));
        }

        if (fileIds == null || fileIds.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "Tenés que seleccionar al menos un archivo"
                        ));
        }

        try {
                // 1. Construir el filtro por archivos seleccionados
                String filterExpression = buildFileIdsFilter(fileIds);

                // 2. Recuperar los fragmentos más relevantes
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
                return ResponseEntity.ok(
                        Map.of(
                                "answer",
                                "No encontré información suficiente en los documentos seleccionados.",
                                "chunksUsed",
                                0
                        )
                );
                }

                // 3. Construir el contexto para Gemini
                String context = relevantChunks.stream()
                        .map(document -> {
                        Object fileId =
                                document.getMetadata().get("fileId");

                        Object chunkIndex =
                                document.getMetadata().get("chunkIndex");

                        return """
                                Archivo: %s
                                Fragmento: %s

                                %s
                                """.formatted(
                                        fileId,
                                        chunkIndex,
                                        document.getText()
                                );
                        })
                        .collect(
                                java.util.stream.Collectors.joining(
                                        "\n\n---\n\n"
                                )
                        );

                // 4. Generar la respuesta usando únicamente los fragmentos
                String answer = chatClient.prompt()
                        .system("""
                                Sos Dina, la asistente educativa de Studina.

                                Respondé utilizando únicamente el contexto recuperado
                                de los documentos seleccionados.

                                Reglas:
                                - No agregues información externa.
                                - Si el contexto no alcanza para responder, indicá que
                                no encontraste información suficiente.
                                - Respondé de forma clara, breve y didáctica.
                                - Usá Markdown cuando ayude a organizar la respuesta.
                                - No menciones detalles técnicos como embeddings,
                                fragmentos, pgvector o búsqueda semántica.
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

                // 5. Devolver respuesta y datos de depuración
                List<Map<String, Object>> retrievedChunks =
                        relevantChunks.stream()
                                .map(document -> {
                                Map<String, Object> item =
                                        new java.util.HashMap<>();

                                item.put(
                                        "fileId",
                                        document.getMetadata().get("fileId")
                                );

                                item.put(
                                        "chunkIndex",
                                        document.getMetadata().get("chunkIndex")
                                );

                                item.put(
                                        "score",
                                        document.getScore() != null
                                                ? document.getScore()
                                                : 0
                                );

                                return item;
                                })
                                .toList();

                return ResponseEntity.ok(
                        Map.of(
                                "answer",
                                answer != null ? answer : "",
                                "chunksUsed",
                                relevantChunks.size(),
                                "retrievedChunks",
                                retrievedChunks
                        )
                );

        } catch (Exception e) {
                e.printStackTrace();

                Throwable rootCause = e;

                while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
                }

                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "error",
                                "No se pudo responder usando los documentos",
                                "message",
                                rootCause.getMessage() != null
                                        ? rootCause.getMessage()
                                        : "Error desconocido"
                        ));
        }
        }

        // ── POST /ai/rag/index/{fileId}: indexar un PDF en pgvector ────────────────
        @PostMapping("/rag/index/{fileId}")
        public ResponseEntity<Map<String, Object>> indexPdf(
                @PathVariable String fileId) {

        if (fileId == null || fileId.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El fileId es obligatorio"));
        }

        try {
                // 1. Descargar el PDF desde Directus
                byte[] pdfBytes = fileService.downloadFile(fileId);

                // 2. Extraer todo el texto
                String pdfText = extractPdfText(pdfBytes);

                if (pdfText == null || pdfText.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "No se pudo extraer texto del PDF"
                        ));
                }

                // 3. Borrar una indexación anterior del mismo archivo
                vectorStore.delete(
                        "fileId == '" + escapeFilterValue(fileId) + "'"
                );

                // 4. Crear un documento inicial con metadata
                Document sourceDocument = new Document(
                        pdfText,
                        Map.of(
                                "fileId", fileId,
                                "sourceType", "pdf"
                        )
                );

                // 5. Dividir el texto en fragmentos
                TokenTextSplitter splitter = TokenTextSplitter.builder()
                        .withChunkSize(800)
                        .withMinChunkSizeChars(350)
                        .withMinChunkLengthToEmbed(20)
                        .withMaxNumChunks(5000)
                        .withKeepSeparator(true)
                        .build();

                List<Document> chunks = splitter.apply(
                        List.of(sourceDocument)
                );

                if (chunks.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "El documento no produjo fragmentos indexables"
                        ));
                }

                // 6. Agregar el número de fragmento a la metadata
                List<Document> indexedChunks =
                        java.util.stream.IntStream
                                .range(0, chunks.size())
                                .mapToObj(index -> {
                                Document chunk = chunks.get(index);

                                Map<String, Object> metadata =
                                        new java.util.HashMap<>(
                                                chunk.getMetadata()
                                        );

                                metadata.put("chunkIndex", index);
                                metadata.put("totalChunks", chunks.size());

                                return new Document(
                                        chunk.getText(),
                                        metadata
                                );
                                })
                                .toList();

                // 7. Generar embeddings y guardar en pgvector
                vectorStore.add(indexedChunks);

                return ResponseEntity.ok(
                        Map.of(
                                "message", "Documento indexado correctamente",
                                "fileId", fileId,
                                "characters", pdfText.length(),
                                "chunks", indexedChunks.size()
                        )
                );

        } catch (IOException e) {
                e.printStackTrace();

                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "El archivo no es un PDF válido o no pudo procesarse"
                        ));

        } catch (Exception e) {
                e.printStackTrace();

                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "error", "No se pudo indexar el documento",
                                "message", e.getMessage() != null
                                        ? e.getMessage()
                                        : "Error desconocido"
                        ));
        }
        }

        @PostMapping("/rag/search")
        public ResponseEntity<Map<String, Object>> searchRag(
                @RequestBody RagSearchRequest request) {

        String query = request.query();
        List<String> fileIds = request.fileIds();

        if (query == null || query.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "La consulta es obligatoria"));
        }

        if (fileIds == null || fileIds.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "Tenés que seleccionar al menos un archivo"
                        ));
        }

        try {
                String filterExpression = buildFileIdsFilter(fileIds);

                List<Document> results = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(5)
                                .similarityThreshold(0.55)
                                .filterExpression(filterExpression)
                                .build()
                );

                List<Map<String, Object>> response = results.stream()
                        .map(document -> {
                        Map<String, Object> item =
                                new java.util.HashMap<>();

                        item.put("text", document.getText());
                        item.put("metadata", document.getMetadata());
                        item.put(
                                "score",
                                document.getScore() != null
                                        ? document.getScore()
                                        : 0
                        );

                        return item;
                        })
                        .toList();

                return ResponseEntity.ok(
                        Map.of(
                                "query", query,
                                "fileIds", fileIds,
                                "filter", filterExpression,
                                "results", response
                        )
                );

        } catch (Exception e) {
                e.printStackTrace();

                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "error",
                                "No se pudo realizar la búsqueda semántica",
                                "message",
                                e.getMessage() != null
                                        ? e.getMessage()
                                        : "Error desconocido"
                        ));
        }
        }
}