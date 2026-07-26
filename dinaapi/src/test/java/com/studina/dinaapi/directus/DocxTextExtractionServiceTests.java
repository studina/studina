package com.studina.dinaapi.directus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocxTextExtractionServiceTests {

    private final DocxTextExtractionService service =
            new DocxTextExtractionService();

    @Test
    void extractsParagraphTextFromDocx() throws Exception {
        byte[] documentBytes;

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph()
                    .createRun()
                    .setText("Concurrencia y bloqueos");
            document.createParagraph()
                    .createRun()
                    .setText("Dos fases de locking");
            document.write(output);
            documentBytes = output.toByteArray();
        }

        String extracted = service.extract(documentBytes);

        assertThat(extracted)
                .contains("Concurrencia y bloqueos")
                .contains("Dos fases de locking");
    }
}
