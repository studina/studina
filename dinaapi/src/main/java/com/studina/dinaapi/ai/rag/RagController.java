package com.studina.dinaapi.ai.rag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/rag")
public class RagController {

    private final VectorStore vectorStore;

    public RagController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // ── POST /ai/rag/search ───────────────────────────────────────────────
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestBody RagSearchRequest request) {

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

        try {
            String filterExpression =
                    buildFileIdsFilter(fileIds);

            List<Document> results =
                    vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(query)
                                    .topK(5)
                                    .similarityThreshold(0.55)
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
}