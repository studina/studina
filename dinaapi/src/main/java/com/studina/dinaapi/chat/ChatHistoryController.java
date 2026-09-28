package com.studina.dinaapi.chat;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/ai/chats")
public class ChatHistoryController {

    private final DirectusAuthorizationService authorizationService;

    private final ChatConversationService conversationService;

    private final ChatMessageService messageService;

    public ChatHistoryController(
            DirectusAuthorizationService authorizationService,
            ChatConversationService conversationService,
            ChatMessageService messageService
    ) {
        this.authorizationService
                = authorizationService;

        this.conversationService
                = conversationService;

        this.messageService
                = messageService;
    }

    @PostMapping
    public ResponseEntity<?> createConversation(
            @RequestBody Map<String, String> request,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorizationHeader
    ) {

        String subjectId
                = request.get("subjectId");

        String title
                = request.get("title");

        String userId
                = authorizationService.requireUserId(
                        authorizationHeader
                );

        authorizationService
                .requireSubjectOwnership(
                        userId,
                        subjectId
                );

        Map<String, Object> conversation
                = conversationService
                        .createConversation(
                                userId,
                                subjectId,
                                title
                        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(conversation);
    }

    @GetMapping
    public ResponseEntity<
            List<Map<String, Object>>> listConversations(
            @RequestParam String subjectId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorizationHeader
    ) {

        String userId
                = authorizationService.requireUserId(
                        authorizationHeader
                );

        authorizationService
                .requireSubjectOwnership(
                        userId,
                        subjectId
                );

        return ResponseEntity.ok(
                conversationService
                        .listConversations(
                                userId,
                                subjectId
                        )
        );
    }

    @GetMapping(
            "/{conversationId}/messages"
    )
    public ResponseEntity<
            List<Map<String, Object>>> listMessages(
            @PathVariable String conversationId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorizationHeader
    ) {

        String userId
                = authorizationService.requireUserId(
                        authorizationHeader
                );

        conversationService
                .requireConversationOwnership(
                        userId,
                        conversationId
                );

        return ResponseEntity.ok(
                messageService
                        .listMessages(
                                conversationId
                        )
        );
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<?> createMessage(
            @PathVariable String conversationId,
            @RequestBody Map<String, Object> request,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorizationHeader
    ) {

        String userId
                = authorizationService.requireUserId(
                        authorizationHeader
                );

        // Primero verificamos que el chat realmente
        // pertenezca al usuario autenticado.
        conversationService
                .requireConversationOwnership(
                        userId,
                        conversationId
                );

        String role
                = request.get("role") != null
                ? request.get("role").toString()
                : null;

        String content
                = request.get("content") != null
                ? request.get("content").toString()
                : null;

        if (role == null
                || role.isBlank()
                || content == null
                || content.isBlank()) {
            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "role y content son obligatorios"
                            )
                    );
        }

        if (!role.equals("user")
                && !role.equals("assistant")) {
            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "role debe ser user o assistant"
                            )
                    );
        }

        List<String> sourceFileIds
                = extractStringList(
                        request.get(
                                "sourceFileIds"
                        )
                );

        String provider
                = request.get("provider") != null
                ? request
                        .get("provider")
                        .toString()
                : null;

        String model
                = request.get("model") != null
                ? request
                        .get("model")
                        .toString()
                : null;

        Map<String, Object> message
                = messageService.saveMessage(
                        conversationId,
                        role,
                        content,
                        sourceFileIds,
                        provider,
                        model
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(message);
    }

    private List<String> extractStringList(
            Object value
    ) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        return list.stream()
                .filter(item -> item != null)
                .map(Object::toString)
                .toList();
    }
}
