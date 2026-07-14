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

@RestController
@RequestMapping("/ai")
public class AiController {

    private final ChatClient chatClient;

    private final FileService fileService;

    public AiController(
        ChatClient.Builder builder,
        FileService fileService) {

    this.chatClient = builder.build();
    this.fileService = fileService;
}

    @Value("${GEMINI_MODEL}")
    private String configuredModel;

    @Value("${GEMINI_TEMPERATURE:0.2}")
    private Double configuredTemperature;

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
    // ── POST /ai/files/{fileId}/chat: consultar el contenido de un PDF ────────
        @PostMapping("/files/{fileId}/chat")
        public ResponseEntity<Map<String, String>> chatWithFile(
                @PathVariable String fileId,
                @RequestBody Map<String, String> request) {

        String message = request.get("message");

        if (message == null || message.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El mensaje es obligatorio"));
        }

        try {
                // Descargar el archivo desde Directus
                byte[] pdfBytes = fileService.downloadFile(fileId);

                // Extraer el texto del PDF
                String pdfText = extractPdfText(pdfBytes);

                if (pdfText == null || pdfText.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "No se pudo extraer texto del PDF"
                        ));
                }

                // Limitar el contenido para no enviar un PDF enorme al modelo
                String limitedText = pdfText.length() > 20_000
                        ? pdfText.substring(0, 20_000)
                        : pdfText;

                String answer = chatClient.prompt()
                        .system("""
                                Sos Dina, la asistente educativa de Studina.

                                Respondé utilizando únicamente el contenido
                                del documento proporcionado.

                                Si la respuesta no se encuentra en el documento,
                                indicá que no encontraste información suficiente.

                                Respondé de manera clara y didáctica.
                                Utilizá Markdown, párrafos cortos y listas cuando
                                ayuden a organizar la explicación.
                                """)
                        .user("""
                                Documento:

                                %s

                                Pregunta:

                                %s
                                """.formatted(limitedText, message))
                        .options(
                                GoogleGenAiChatOptions.builder()
                                        .model(configuredModel)
                                        .temperature(configuredTemperature)
                        )
                        .call()
                        .content();

                return ResponseEntity.ok(
                        Map.of(
                                "answer",
                                answer != null ? answer : ""
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
                                "error", "No se pudo analizar el documento",
                                "message", e.getMessage() != null
                                        ? e.getMessage()
                                        : "Error desconocido"
                        ));
        }
        }
}