package com.studina.dinaapi.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.ai.rag.FileChatRequest;
import com.studina.dinaapi.ai.rag.RagChatResponse;
import com.studina.dinaapi.ai.rag.RagQueryService;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final ChatClient chatClient;

    private final RagQueryService ragQueryService;

    public AiController(
        ChatClient.Builder builder,
        RagQueryService ragQueryService) {

    this.chatClient = builder.build();
    this.ragQueryService = ragQueryService;
}

    @Value("${GEMINI_MODEL}")
    private String configuredModel;

    @Value("${GEMINI_TEMPERATURE:0.2}")
    private Double configuredTemperature;

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

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error", "No se pudo obtener una respuesta de la IA",
                            "message", e.getMessage() != null
                                    ? e.getMessage()
                                    : "Error desconocido"
                    ));
        }
    }
        @PostMapping("/files/chat")
                public ResponseEntity<?> chatWithFiles(
                @RequestBody FileChatRequest request) {

                try {
                RagChatResponse response =
                        ragQueryService.answer(
                                request.message(),
                                request.fileIds()
                        );

                return ResponseEntity.ok(response);

                } catch (IllegalArgumentException exception) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                exception.getMessage()
                        ));

                } catch (Exception exception) {

                Throwable rootCause = exception;

                while (rootCause.getCause() != null) {
                        rootCause = rootCause.getCause();
                }

                return ResponseEntity
                        .status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "error",
                                "No se pudo responder usando los documentos",
                                "message",
                                rootCause.getMessage() != null
                                        ? rootCause.getMessage()
                                        : "Error desconocido"
                        ));
                }
                }

}
