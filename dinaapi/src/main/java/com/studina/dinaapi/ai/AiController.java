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
    // ── POST /ai/files/chat: consultar el contenido de uno o varios PDFs ────────
        @PostMapping("/files/chat")
        public ResponseEntity<Map<String, String>> chatWithFiles(
                @RequestBody FileChatRequest request) {

        String message = request.message();
        List<String> fileIds = request.fileIds();

        if (message == null || message.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El mensaje es obligatorio"));
        }

        if (fileIds == null || fileIds.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Tenés que seleccionar al menos un archivo"));
        }

        try {
                StringBuilder documentsContext = new StringBuilder();

                for (String fileId : fileIds) {
                byte[] pdfBytes = fileService.downloadFile(fileId);
                String pdfText = extractPdfText(pdfBytes);

                if (pdfText == null || pdfText.isBlank()) {
                        continue;
                }

                // Límite temporal por archivo
                String limitedText = pdfText.length() > 15_000
                        ? pdfText.substring(0, 15_000)
                        : pdfText;

                documentsContext
                        .append("\n\n--- DOCUMENTO ")
                        .append(fileId)
                        .append(" ---\n")
                        .append(limitedText);
                }

                if (documentsContext.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "No se pudo extraer texto de los archivos seleccionados"
                        ));
                }

                String answer = chatClient.prompt()
                        .system("""
                                Sos Dina, la asistente educativa de Studina.

                                Respondé utilizando únicamente la información
                                contenida en los documentos proporcionados.

                                Podés comparar o relacionar información entre documentos.

                                Si la respuesta no se encuentra en ellos, indicá que
                                no encontraste información suficiente.

                                Respondé con Markdown, párrafos cortos y listas
                                cuando ayuden a organizar la explicación.
                                """)
                        .user("""
                                Documentos:

                                %s

                                Pregunta:

                                %s
                                """.formatted(documentsContext, message))
                        .options(
                                GoogleGenAiChatOptions.builder()
                                        .model(configuredModel)
                                        .temperature(configuredTemperature)
                        )
                        .call()
                        .content();

                return ResponseEntity.ok(
                        Map.of("answer", answer != null ? answer : "")
                );

        } catch (IOException e) {
                e.printStackTrace();

                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "Uno de los archivos no es un PDF válido"
                        ));

        } catch (Exception e) {
                e.printStackTrace();

                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "error", "No se pudieron analizar los documentos",
                                "message", e.getMessage() != null
                                        ? e.getMessage()
                                        : "Error desconocido"
                        ));
        }
        }
}