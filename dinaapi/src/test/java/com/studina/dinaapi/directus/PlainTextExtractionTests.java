package com.studina.dinaapi.directus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PlainTextExtractionTests {

    @Test
    void decodesUtf8Text() {
        byte[] bytes = "Semáforos y señales".getBytes(StandardCharsets.UTF_8);

        assertThat(PlainTextExtraction.extract(bytes))
                .isEqualTo("Semáforos y señales");
    }

    @Test
    void dropsTheUtf8ByteOrderMark() {
        byte[] text = "Exclusión mutua".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(text, 0, bytes, 3, text.length);

        assertThat(PlainTextExtraction.extract(bytes))
                .isEqualTo("Exclusión mutua");
    }

    @Test
    void fallsBackToWindows1252WhenTheBytesAreNotUtf8() {
        byte[] bytes = "Planificación del año"
                .getBytes(Charset.forName("windows-1252"));

        assertThat(PlainTextExtraction.extract(bytes))
                .isEqualTo("Planificación del año");
    }

    @Test
    void recognizesPlainTextByExtensionOrContentType() {
        assertThat(PlainTextExtraction.isPlainText("Notas.TXT", null)).isTrue();
        assertThat(PlainTextExtraction.isPlainText("notas", "text/plain; charset=UTF-8")).isTrue();
        assertThat(PlainTextExtraction.isPlainText("Apunte.pdf", "application/pdf")).isFalse();
    }
}
