package com.studina.dinaapi.ai.rag;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.directus.DocxTextExtractionService;
import com.studina.dinaapi.directus.FlashcardSetService;
import com.studina.dinaapi.directus.FileService;
import com.studina.dinaapi.directus.QuizSetService;
import com.studina.dinaapi.learning.TopicCatalogService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/ai")
public class RagController {

    private static final Logger logger = LoggerFactory.getLogger(RagController.class);

    private final VectorStore vectorStore;
    private final RagIndexService ragIndexService;
    private final RagQueryService ragQueryService;
    private final DirectusAuthorizationService authorizationService;
    private final FileService fileService;
    private final DocxTextExtractionService docxTextExtractionService;
        private final FlashcardSetService flashcardSetService;
        private final QuizSetService quizSetService;
        private final TopicCatalogService topicCatalogService;

    public RagController(
            VectorStore vectorStore,
            RagIndexService ragIndexService,
            RagQueryService ragQueryService,
            DirectusAuthorizationService authorizationService,
            FileService fileService,
            DocxTextExtractionService docxTextExtractionService,
            FlashcardSetService flashcardSetService,
            QuizSetService quizSetService,
            TopicCatalogService topicCatalogService) {

        this.vectorStore = vectorStore;
        this.ragIndexService = ragIndexService;
        this.ragQueryService = ragQueryService;
        this.authorizationService = authorizationService;
        this.fileService = fileService;
        this.docxTextExtractionService = docxTextExtractionService;
        this.flashcardSetService = flashcardSetService;
        this.quizSetService = quizSetService;
        this.topicCatalogService = topicCatalogService;
    }

    // ── POST /rag/search ───────────────────────────────────────────────
    @PostMapping("/rag/search")
        public ResponseEntity<Map<String, Object>> search(
            @RequestBody RagSearchRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {

        String query = request.query();
        List<String> fileIds = request.fileIds();

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            "La consulta es obligatoria"
                    ));
        }

        if (fileIds == null || fileIds.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            "Tenés que seleccionar al menos un archivo"
                    ));
        }

        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireFileOwnership(userId, fileIds);

        try {
            String filterExpression =
                    buildFileIdsFilter(fileIds);

            List<Document> results =
                    vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(query)
                                    .topK(5)
                                    .filterExpression(filterExpression)
                                    .build()
                    );

            List<Map<String, Object>> response =
                    results.stream()
                            .map(this::toResponseItem)
                            .toList();

            return ResponseEntity.ok(
                    Map.of(
                            "query", query,
                            "fileIds", fileIds,
                            "filter", filterExpression,
                            "results", response
                    )
            );

        } catch (Exception exception) {

            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error",
                            "No se pudo realizar la búsqueda semántica",
                            "message",
                            exception.getMessage() != null
                                    ? exception.getMessage()
                                    : "Error desconocido"
                    ));
        }
    }

    private Map<String, Object> toResponseItem(
            Document document) {

        Map<String, Object> item = new HashMap<>();
        Double score = document.getScore();

        item.put("text", document.getText());
        item.put("metadata", document.getMetadata());
        item.put(
                "score",
                score != null
                        ? score
                        : Double.valueOf(0.0)
        );

        return item;
    }

    private String buildFileIdsFilter(
            List<String> fileIds) {

        String values = fileIds.stream()
                .map(this::escapeFilterValue)
                .map(fileId -> "'" + fileId + "'")
                .collect(
                        java.util.stream.Collectors.joining(", ")
                );

        return "fileId in [" + values + "]";
    }

    private String escapeFilterValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'");
    }

    // ── POST /ai/rag/index/{fileId} ───────────────────────────────────────────
    @PostMapping("/rag/index/{fileId}")
    public ResponseEntity<Map<String, Object>> indexDocument(
            @PathVariable String fileId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {

        if (fileId == null || fileId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            "El fileId es obligatorio"
                    ));
        }

        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireFileOwnership(userId, fileId);

        try {
            String filename = fileService.getFilename(fileId);
            String lowerFilename = filename.toLowerCase();
            int chunks;

            if (lowerFilename.endsWith(".docx")) {
                String text = docxTextExtractionService.extract(
                        fileService.downloadFile(fileId)
                );
                chunks = ragIndexService.indexText(
                        fileId,
                        filename,
                        userId,
                        "manual",
                        text,
                        "docx"
                );
            } else if (lowerFilename.endsWith(".pdf")) {
                chunks = ragIndexService.indexPdf(
                        fileId,
                        filename,
                        userId,
                        "manual"
                );
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "Solo se pueden indexar archivos PDF o DOCX"
                        ));
            }

            return ResponseEntity.ok(
                    Map.of(
                            "message",
                            "Documento indexado correctamente",
                            "fileId",
                            fileId,
                            "chunks",
                            chunks
                    )
            );

        } catch (IllegalStateException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            exception.getMessage()
                    ));

        } catch (IOException exception) {

            Throwable rootCause = exception;

            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }

            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error",
                            "No se pudo indexar el documento",
                            "message",
                            rootCause.getMessage() != null
                                    ? rootCause.getMessage()
                                    : "Error desconocido"
                    ));
        } catch (Exception exception) {
            if (AiProviderErrorMapper.isQuotaExceeded(exception)) {
                return ResponseEntity
                        .status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of(
                                "error",
                                "Se alcanzó la cuota de IA para procesar documentos",
                                "retryable",
                                true
                        ));
            }

            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error",
                            "No se pudo indexar el documento"
                    ));
        }
    }

    // ── POST /ai/files/chat ───────────────────────────────────────────────────
    @PostMapping("/files/chat")
    public ResponseEntity<?> chatWithFiles(
            @RequestBody FileChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {

        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireFileOwnership(userId, request.fileIds());

        try {
            RagChatResponse response =
                    ragQueryService.answer(
                            request.message(),
                            request.fileIds()
                    );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            exception.getMessage()
                    ));

        } catch (Exception exception) {

            Throwable rootCause = exception;

            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }

            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
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

        /**
         * El nombre que escribió el estudiante manda; si lo dejó vacío, usamos el que propuso
         * la IA. Si tampoco hay, el servicio cae en su numeración ("Flashcards #3").
         */
        private static String chosenTitle(String requested, String generated) {
                if (requested != null && !requested.isBlank()) {
                        return requested;
                }
                return generated;
        }

        @PostMapping("/files/flashcards")
        public ResponseEntity<?> generateFlashcards(
                        @RequestBody FlashcardGenerationRequest request,
                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                        String authorizationHeader) {

                // Primera línea del método: si esto no aparece en el log, el request nunca
                // llegó a la API y hay que mirar del lado del navegador o del proxy.
                logger.info("Request de generación de flashcards recibido: subjectId={}, selectedFileCount={}",
                                request == null ? null : request.subjectId(),
                                request == null || request.fileIds() == null ? 0 : request.fileIds().size());

                String userId;
                try {
                        userId = authorizationService.requireUserId(authorizationHeader);
                        authorizationService.requireSubjectOwnership(userId, request.subjectId());
                        authorizationService.requireFileOwnership(userId, request.fileIds());
                } catch (RuntimeException exception) {
                        // Estos tres chequeos cortan el request antes del try principal y hasta
                        // ahora lo hacían sin dejar rastro: un 401 o un 403 no se distinguía de
                        // un request que nunca llegó.
                        logger.warn("Generación de flashcards rechazada por autorización: subjectId={}, motivo={}",
                                        request == null ? null : request.subjectId(), exception.toString());
                        throw exception;
                }

                try {
                        List<com.studina.dinaapi.learning.Topic> availableTopics =
                                        topicCatalogService.listForFilesOrSubjectFallback(
                                                        request.subjectId(), request.fileIds());
                        logger.info("Topics disponibles para generación: subjectId={}, topicCount={}",
                                        request.subjectId(), availableTopics.size());
                        logger.info("Generando flashcards: subjectId={}, files={}, cardCount={}, titulo='{}'",
                                        request.subjectId(),
                                        request.fileIds() == null ? 0 : request.fileIds().size(),
                                        request.cardCount(), request.title());
                        FlashcardGenerationResponse generated =
                                        ragQueryService.generateFlashcards(
                                                        request.instructions(), request.fileIds(), request.cardCount(),
                                                        request.cardFormat(), request.difficulty(), availableTopics);
                        logger.info("Flashcards generadas; persistiendo set: subjectId={}, cardCount={}",
                                        request.subjectId(), generated.cards().size());
                        Map<String, Object> savedSet = flashcardSetService.create(
                                        request.subjectId(), chosenTitle(request.title(), generated.title()),
                                        request.instructions(), request.difficulty(),
                                        request.fileIds(), generated.cards());
                        logger.info("Set de flashcards persistido: subjectId={}, setId={}, titulo='{}', tarjetas={}",
                                        request.subjectId(), savedSet.get("id"), savedSet.get("title"),
                                        generated.cards().size());
                        return ResponseEntity.ok(savedSet);
                } catch (IllegalArgumentException exception) {
                        logger.warn("No se pudieron generar flashcards por una solicitud inválida: subjectId={}, reason={}",
                                        request.subjectId(), exception.getMessage());
                        return ResponseEntity.badRequest().body(Map.of(
                                        "error", exception.getMessage()
                        ));
                } catch (Exception exception) {
                        logger.error("Falló la generación de flashcards: subjectId={}, selectedFileCount={}",
                                        request.subjectId(), request.fileIds() == null ? 0 : request.fileIds().size(),
                                        exception);
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                        "error", "No se pudieron generar las flashcards"
                        ));
                }
        }

        @PostMapping("/files/quizzes")
        public ResponseEntity<?> generateQuiz(
                        @RequestBody QuizGenerationRequest request,
                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                        String authorizationHeader) {
                String userId = authorizationService.requireUserId(authorizationHeader);
                authorizationService.requireSubjectOwnership(userId, request.subjectId());
                authorizationService.requireFileOwnership(userId, request.fileIds());

                try {
                        logger.info("Generando cuestionario: subjectId={}, files={}, questionCount={}, titulo='{}'",
                                        request.subjectId(),
                                        request.fileIds() == null ? 0 : request.fileIds().size(),
                                        request.questionCount(), request.title());
                        QuizGenerationResponse generated = ragQueryService.generateQuiz(
                                        request.instructions(), request.fileIds(), request.questionCount(),
                                        request.answerCount(), request.difficulty(),
                                        topicCatalogService.listForFilesOrSubjectFallback(
                                                        request.subjectId(), request.fileIds()));
                        return ResponseEntity.ok(quizSetService.create(
                                        request.subjectId(), chosenTitle(request.title(), generated.title()),
                                        request.instructions(),
                                        request.difficulty(), request.fileIds(), generated.questions()));
                } catch (IllegalArgumentException exception) {
                        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
                } catch (Exception exception) {
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                        "error", "No se pudo generar el cuestionario"));
                }
        }
}
