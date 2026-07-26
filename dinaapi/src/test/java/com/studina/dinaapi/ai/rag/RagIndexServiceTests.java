package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

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

    @Test
    void indexesExtractedDocxTextWithItsSourceType() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagIndexService indexService =
                new RagIndexService(null, vectorStore);

        int chunks = indexService.indexText(
                "docx-1",
                "Concurrencia.docx",
                "user-1",
                "subject-1",
                "Un thread es una secuencia de ejecución independiente. "
                        .repeat(30),
                "docx"
        );

        assertThat(chunks).isPositive();
        verify(vectorStore).delete("fileId == 'docx-1'");
        verify(vectorStore).add(anyList());
    }
}
