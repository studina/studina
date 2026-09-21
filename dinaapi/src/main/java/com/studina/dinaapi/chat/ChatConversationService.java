package com.studina.dinaapi.chat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class ChatConversationService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE
            = new ParameterizedTypeReference<>() {
    };

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    public ChatConversationService(
            DirectusAuthorizationService authorizationService
    ) {
        this.authorizationService = authorizationService;
    }

    public Map<String, Object> createConversation(
            String userId,
            String subjectId,
            String title
    ) {
        String adminToken
                = authorizationService.getAdminToken();

        Map<String, Object> payload
                = new HashMap<>();

        payload.put("user", userId);
        payload.put("subject", subjectId);
        payload.put(
                "title",
                title == null || title.isBlank()
                ? "Nuevo chat"
                : title.trim()
        );
        payload.put("status", "active");

        Map<String, Object> response
                = http.post()
                        .uri(
                                directusUrl
                                + "/items/chat_conversations"
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + adminToken
                        )
                        .body(payload)
                        .retrieve()
                        .body(MAP_TYPE);

        return dataMap(response);
    }

    public List<Map<String, Object>>
            listConversations(
                    String userId,
                    String subjectId
            ) {

        String adminToken
                = authorizationService.getAdminToken();

        String url
                = directusUrl
                + "/items/chat_conversations"
                + "?filter[user][_eq]="
                + userId
                + "&filter[subject][_eq]="
                + subjectId
                + "&fields="
                + "id,title,status,"
                + "date_created,date_updated,subject"
                + "&sort=-date_created";

        Map<String, Object> response
                = http.get()
                        .uri(url)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + adminToken
                        )
                        .retrieve()
                        .body(MAP_TYPE);

        return dataList(response);
    }

    public Map<String, Object>
            requireConversationOwnership(
                    String userId,
                    String conversationId
            ) {

        try {
            String adminToken
                    = authorizationService.getAdminToken();

            Map<String, Object> response
                    = http.get()
                            .uri(
                                    directusUrl
                                    + "/items/chat_conversations/"
                                    + conversationId
                                    + "?fields="
                                    + "id,user,subject,"
                                    + "title,status,"
                                    + "summary,summary_until,"
                                    + "date_created,date_updated"
                            )
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + adminToken
                            )
                            .retrieve()
                            .body(MAP_TYPE);

            Map<String, Object> conversation
                    = dataMap(response);

            String conversationUserId
                    = relationId(
                            conversation.get("user")
                    );

            if (!userId.equals(
                    conversationUserId
            )) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "No tenés permiso para acceder a este chat"
                );
            }

            return conversation;

        } catch (RestClientResponseException exception) {
            if (exception
                    .getStatusCode()
                    .value() == 404) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El chat no existe"
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "No se pudo validar el chat",
                    exception
            );
        }
    }

    public Map<String, Object> updateSummary(
            String conversationId,
            String summary,
            String summaryUntil
    ) {

        String adminToken
                = authorizationService.getAdminToken();

        Map<String, Object> payload
                = new HashMap<>();

        payload.put(
                "summary",
                summary
        );

        payload.put(
                "summary_until",
                summaryUntil
        );

        Map<String, Object> response
                = http.patch()
                        .uri(
                                directusUrl
                                + "/items/chat_conversations/"
                                + conversationId
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + adminToken
                        )
                        .body(payload)
                        .retrieve()
                        .body(MAP_TYPE);

        return dataMap(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataMap(
            Map<String, Object> response
    ) {
        if (response == null
                || !(response.get("data") instanceof Map<?, ?>)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Directus devolvió una respuesta inválida"
            );
        }

        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataList(
            Map<String, Object> response
    ) {
        if (response == null) {
            return List.of();
        }

        Object data = response.get("data");

        if (!(data instanceof List<?>)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Directus devolvió una respuesta inválida"
            );
        }

        return (List<Map<String, Object>>) data;
    }

    private String relationId(
            Object relation
    ) {
        if (relation == null) {
            return null;
        }

        if (relation instanceof Map<?, ?> map) {
            Object id = map.get("id");
            return id != null
                    ? id.toString()
                    : null;
        }

        return relation.toString();
    }
}
