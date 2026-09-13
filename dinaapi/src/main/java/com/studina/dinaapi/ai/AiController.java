package com.studina.dinaapi.ai;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.google.cloud.modelarmor.v1.SanitizeModelResponseResponse;
import com.google.cloud.modelarmor.v1.SanitizeUserPromptResponse;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.chat.ChatConversationService;
import com.studina.dinaapi.chat.ChatMessageService;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final ChatClient chatClient;
    private final ModelArmorService modelArmorService;

    private final DirectusAuthorizationService authorizationService;
    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;

    public AiController(
            ChatClient.Builder builder,
            ModelArmorService modelArmorService,
            DirectusAuthorizationService authorizationService,
            ChatConversationService conversationService,
            ChatMessageService messageService
    ) {
        this.chatClient = builder.build();
        this.modelArmorService = modelArmorService;
        this.authorizationService = authorizationService;
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    @Value("${GEMINI_MODEL}")
    private String configuredModel;

    @Value("${GEMINI_TEMPERATURE:0.2}")
    private Double configuredTemperature;

    // ── GET /ai/suggest-tags: sugerir tags según el nombre del archivo ────
    @GetMapping("/suggest-tags")
    public List<String> suggestTags(@RequestParam("filename") String filename) {
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
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .filter(tag -> !tag.isBlank())
                .distinct()
                .toList();
    }

    // ── POST /ai/chat: enviar un mensaje al modelo ─────────────────────────────
    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @RequestBody Map<String, String> request,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorizationHeader
    ) {

        String message = request.get("message");
        String subjectId = request.get("subjectId");
        String conversationId = request.get("conversationId");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "El mensaje es obligatorio"));
        }

        boolean persistHistory
                = (subjectId != null && !subjectId.isBlank())
                || (conversationId != null && !conversationId.isBlank());

        String userId = null;
        boolean newConversation = false;

        if (persistHistory) {

            userId = authorizationService.requireUserId(
                    authorizationHeader
            );

            if (conversationId != null
                    && !conversationId.isBlank()) {

                conversationService.requireConversationOwnership(
                        userId,
                        conversationId
                );

            } else {

                if (subjectId == null
                        || subjectId.isBlank()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of(
                                    "error",
                                    "subjectId es obligatorio para iniciar una conversación"
                            ));
                }

                authorizationService.requireSubjectOwnership(
                        userId,
                        subjectId
                );

                newConversation = true;
            }
        }

        try {

            if (modelArmorService.isEnabled()) {
                SanitizeUserPromptResponse sanitizedPrompt = modelArmorService.sanitizeUserPrompt(message);

                if (!modelArmorService.isPromptSafe(sanitizedPrompt)) {
                    return ResponseEntity.ok(modelArmorService.sanitizationError());
                }
            }

            if (persistHistory && newConversation) {

                Map<String, Object> conversation
                        = conversationService.createConversation(
                                userId,
                                subjectId,
                                buildConversationTitle(message)
                        );

                conversationId
                        = conversation.get("id").toString();
            }

            if (persistHistory) {
                messageService.saveMessage(
                        conversationId,
                        "user",
                        message,
                        null,
                        null,
                        null
                );
            }

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

            if (modelArmorService.isEnabled()) {
                SanitizeModelResponseResponse sanitizedPrompt = modelArmorService.sanitizeModelResponse(answer);

                if (!modelArmorService.isAnswerSafe(sanitizedPrompt)) {
                    return ResponseEntity.ok(modelArmorService.sanitizationError());
                }
            }

            if (persistHistory) {
                messageService.saveMessage(
                        conversationId,
                        "assistant",
                        answer != null ? answer : "",
                        null,
                        "gemini",
                        configuredModel
                );
            }

            if (!persistHistory) {
                return ResponseEntity.ok(
                        Map.of(
                                "message", message,
                                "answer", answer != null ? answer : ""
                        )
                );
            }

            return ResponseEntity.ok(
                    Map.of(
                            "message", message,
                            "answer", answer != null ? answer : "",
                            "conversationId", conversationId,
                            "newConversation", newConversation
                    )
            );

        } catch (IOException e) {

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error", "No se pudo obtener una respuesta de la IA",
                            "message", e.getMessage() != null
                            ? e.getMessage()
                            : "Error desconocido"
                    ));
        }
    }

    private String buildConversationTitle(String message) {

        String title = message
                .trim()
                .replaceAll("\\s+", " ");

        if (title.length() <= 60) {
            return title;
        }

        return title.substring(0, 57) + "...";
    }
}
