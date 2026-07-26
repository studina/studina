package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RagIndexServiceTests {

    private final RagIndexService service =
            new RagIndexService(null, null);

    @Test
    void removesNullAndBinaryControlCharactersFromPdfText() {
        String dirtyText = "Consulta SQL\u0000\u0002\nSELECT *\tFROM alumnos";

        String sanitized = service.sanitizeExtractedText(dirtyText);

        assertThat(sanitized)
                .doesNotContain("\u0000", "\u0002")
                .contains("Consulta SQL")
                .contains("\nSELECT *\tFROM alumnos");
    }

    @Test
    void preservesNullInput() {
        assertThat(service.sanitizeExtractedText(null)).isNull();
    }
}
