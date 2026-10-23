package com.studina.dinaapi.directus;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodifica el contenido de un .txt. Se prueba primero UTF-8 estricto; si los
 * bytes no son UTF-8 válido, se asume Windows-1252, que es como suele guardar
 * los acentos un .txt creado en Windows.
 */
public final class PlainTextExtraction {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private PlainTextExtraction() {
    }

    public static String extract(byte[] bytes) {
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        ByteBuffer content = ByteBuffer.wrap(bytes, offset, bytes.length - offset);

        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(content)
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, WINDOWS_1252);
        }
    }

    public static boolean isPlainText(String filename, String contentType) {
        return "text/plain".equalsIgnoreCase(baseMediaType(contentType))
                || (filename != null && filename.toLowerCase().endsWith(".txt"));
    }

    private static String baseMediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }
}
