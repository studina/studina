package com.studina.dinaapi.directus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocxPreviewServiceTests {

    private final DocxPreviewService service =
            new DocxPreviewService("libreoffice");

    @Test
    void convertsDocxToPdf() throws Exception {
        byte[] docxBytes;

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph()
                    .createRun()
                    .setText("Vista previa de Studina");
            document.write(output);
            docxBytes = output.toByteArray();
        }

        byte[] pdfBytes = service.convertToPdf(docxBytes);

        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(
                pdfBytes,
                0,
                Math.min(pdfBytes.length, 5),
                StandardCharsets.US_ASCII
        )).isEqualTo("%PDF-");
    }
}
