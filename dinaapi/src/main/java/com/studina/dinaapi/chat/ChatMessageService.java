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
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class ChatMessageService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE
            = new ParameterizedTypeReference<>() {
    };

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    public ChatMessageService(
            DirectusAuthorizationService authorizationService
    ) {
        this.authorizationService
                = authorizationService;
    }

    public Map<String, Object> saveMessage(
            String conversationId,
            String role,
            String content,
            List<String> sourceFileIds,
            String provider,
            String model
    ) {

        String adminToken
                = authorizationService.getAdminToken();

        Map<String, Object> payload
                = new HashMap<>();

        payload.put(
                "conversation",
                conversationId
        );

        payload.put("role", role);
        payload.put("content", content);

        if (sourceFileIds != null
                && !sourceFileIds.isEmpty()) {
            payload.put(
                    "source_file_ids",
                    sourceFileIds
            );
        }

        if (provider != null
                && !provider.isBlank()) {
            payload.put(
                    "provider",
                    provider
            );
        }

        if (model != null
                && !model.isBlank()) {
            payload.put(
                    "model",
                    model
            );
        }

        Map<String, Object> response
                = http.post()
                        .uri(
                                directusUrl
                                + "/items/chat_messages"
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
            listMessages(
                    String conversationId
            ) {

        String adminToken
                = authorizationService.getAdminToken();

        String url
                = directusUrl
                + "/items/chat_messages"
                + "?filter[conversation][_eq]="
                + conversationId
                + "&fields="
                + "id,role,content,"
                + "source_file_ids,"
                + "provider,model,date_created"
                + "&sort=date_created"
                + "&limit=-1";

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
}
