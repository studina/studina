package com.studina.dinaapi.ai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;
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
import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.ai.llm.LlmRequest;
import com.studina.dinaapi.ai.llm.LlmResponse;
import com.studina.dinaapi.ai.llm.LlmUseCase;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.chat.ChatConversationService;
import com.studina.dinaapi.chat.ChatMessageService;
import com.studina.dinaapi.chat.memory.ConversationContext;
import com.studina.dinaapi.chat.memory.ConversationContextService;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final LlmClient llmClient;
    private final ModelArmorService modelArmorService;

    private final DirectusAuthorizationService authorizationService;
    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;
    private final ConversationContextService contextService;

    public AiController(
            LlmClient llmClient,
            ModelArmorService modelArmorService,
            DirectusAuthorizationService authorizationService,
            ChatConversationService conversationService,
            ChatMessageService messageService,
            ConversationContextService contextService
    ) {
        this.llmClient = llmClient;
        this.modelArmorService = modelArmorService;
        this.authorizationService = authorizationService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.contextService = contextService;
    }

    // ── GET /ai/suggest-tags: sugerir tags según el nombre del archivo ────
    @GetMapping("/suggest-tags")
    public List<String> suggestTags(@RequestParam("filename") String filename) {
        String response = llmClient.generate(LlmRequest.of(
                """
                        Generá entre 2 y 4 tags para clasificar un documento académico.

                        Reglas:
                        - Respondé únicamente con los tags separados por comas.
                        - Usá minúsculas.
                        - Reemplazá los espacios por guiones.
                        - No agregues explicaciones.
                        """,
                "Nombre del archivo: " + filename
        ).forUseCase(LlmUseCase.CLASSIFICATION)).content();
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

        Map<String, Object> existingConversation = null;

        if (persistHistory) {

            userId = authorizationService.requireUserId(
                    authorizationHeader
            );

            if (conversationId != null
                    && !conversationId.isBlank()) {

                existingConversation
                        = conversationService.requireConversationOwnership(
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

            ConversationContext conversationContext
                    = ConversationContext.empty();

            if (persistHistory
                    && !newConversation
                    && existingConversation != null) {
                conversationContext
                        = contextService.prepareContext(
                                existingConversation
                        );
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
            List<Message> modelContext
                    = new ArrayList<>(
                            conversationContext.recentMessages()
                    );
            String systemPrompt = """
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
                """;

            /*
                * Si existe un resumen de la parte vieja
                * de la conversación, lo agregamos como
                * memoria interna.
             */
            if (conversationContext.summary() != null
                    && !conversationContext.summary().isBlank()) {

                systemPrompt += """

            Memoria resumida de la conversación anterior:

            %s

            Usá esta memoria únicamente para mantener continuidad
            con la conversación.
            No la trates como una nueva pregunta del estudiante.
            """.formatted(
                        conversationContext.summary()
                );
            }

            LlmResponse llmResponse = llmClient.generate(
                    LlmRequest.of(systemPrompt, modelContext, message));
            String answer = llmResponse.content();

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
                        llmResponse.provider(),
                        llmResponse.model()
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
