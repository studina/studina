package com.studina.dinaapi.ai;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.studina.dinaapi.directus.FileService;

@Service
public class RagIndexService {

    private final FileService fileService;
    private final VectorStore vectorStore;

    public RagIndexService(
            FileService fileService,
            VectorStore vectorStore) {

        this.fileService = fileService;
        this.vectorStore = vectorStore;
    }

    public int indexPdf(
            String fileId,
            String filename,
            String userId,
            String subjectId) throws IOException {

        // 1. Descargar el PDF desde Directus
        byte[] pdfBytes = fileService.downloadFile(fileId);

        // 2. Extraer el texto completo
        String pdfText = extractPdfText(pdfBytes);

        if (pdfText == null || pdfText.isBlank()) {
            throw new IllegalStateException(
                    "No se pudo extraer texto del PDF"
            );
        }

        // 3. Eliminar una indexación anterior del mismo archivo
        vectorStore.delete(
                "fileId == '" + escapeFilterValue(fileId) + "'"
        );

        // 4. Crear el documento inicial
        Map<String, Object> sourceMetadata = new HashMap<>();

        sourceMetadata.put("fileId", fileId);
        sourceMetadata.put("filename", filename);
        sourceMetadata.put("userId", userId);
        sourceMetadata.put("subjectId", subjectId);
        sourceMetadata.put("sourceType", "pdf");

        Document sourceDocument = new Document(
                pdfText,
                sourceMetadata
        );

        // 5. Dividir el documento
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
            throw new IllegalStateException(
                    "El PDF no produjo fragmentos indexables"
            );
        }

        // 6. Agregar número y total de fragmentos
        List<Document> indexedChunks = IntStream
                .range(0, chunks.size())
                .mapToObj(index -> {
                    Document chunk = chunks.get(index);

                    Map<String, Object> metadata =
                            new HashMap<>(chunk.getMetadata());

                    metadata.put("chunkIndex", index);
                    metadata.put("totalChunks", chunks.size());

                    return new Document(
                            formatDocumentForRetrieval(
                                    filename,
                                    chunk.getText()
                            ),
                            metadata
                    );
                })
                .toList();

        // 7. Generar embeddings y guardar en pgvector
        vectorStore.add(indexedChunks);

        return indexedChunks.size();
    }

    private String extractPdfText(byte[] pdfBytes)
            throws IOException {

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper textStripper =
                    new PDFTextStripper();

            return textStripper.getText(document);
        }
    }

    private String formatDocumentForRetrieval(
            String filename,
            String text) {

        String title = filename == null || filename.isBlank()
                ? "none"
                : filename;

        return "title: " + title + " | text: " + text;
    }

    private String escapeFilterValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'");
    }
}
