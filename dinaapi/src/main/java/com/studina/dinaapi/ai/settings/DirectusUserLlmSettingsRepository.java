package com.studina.dinaapi.ai.settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Repository
public class DirectusUserLlmSettingsRepository implements UserLlmSettingsRepository {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    public DirectusUserLlmSettingsRepository(
            DirectusAuthorizationService authorizationService
    ) {
        this.authorizationService = authorizationService;
    }

    @Override
    public Optional<StoredUserLlmSettings> findByUserId(String userId) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/items/user_ai_settings"
                        + "?filter[user][_eq]=" + userId
                        + "&fields=id,user,provider,chat_model,generation_model,"
                        + "classification_model,summary_model,encrypted_api_key&limit=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve()
                .body(MAP_TYPE);

        if (response == null || !(response.get("data") instanceof List<?> rows)
                || rows.isEmpty() || !(rows.getFirst() instanceof Map<?, ?> row)) {
            return Optional.empty();
        }
        return Optional.of(fromMap(row));
    }

    @Override
    public StoredUserLlmSettings save(StoredUserLlmSettings settings) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("user", settings.userId());
        payload.put("provider", settings.provider());
        payload.put("chat_model", settings.chatModel());
        payload.put("generation_model", settings.generationModel());
        payload.put("classification_model", settings.classificationModel());
        payload.put("summary_model", settings.summaryModel());
        payload.put("encrypted_api_key", settings.encryptedApiKey());

        RestClient.RequestBodySpec request = settings.id() == null
                ? http.post().uri(directusUrl + "/items/user_ai_settings")
                : http.patch().uri(
                        directusUrl + "/items/user_ai_settings/" + settings.id());

        Map<String, Object> response = request
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .body(payload)
                .retrieve()
                .body(MAP_TYPE);
        if (response == null || !(response.get("data") instanceof Map<?, ?> row)) {
            throw new IllegalStateException("Directus no guardó la configuración de IA");
        }
        return fromMap(row);
    }

    @Override
    public void deleteByUserId(String userId) {
        findByUserId(userId).ifPresent(settings -> http.delete()
                .uri(directusUrl + "/items/user_ai_settings/" + settings.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve()
                .toBodilessEntity());
    }

    private String adminToken() {
        return authorizationService.getAdminToken();
    }

    private static StoredUserLlmSettings fromMap(Map<?, ?> row) {
        return new StoredUserLlmSettings(
                text(row.get("id")),
                relationId(row.get("user")),
                text(row.get("provider")),
                text(row.get("chat_model")),
                text(row.get("generation_model")),
                text(row.get("classification_model")),
                text(row.get("summary_model")),
                text(row.get("encrypted_api_key")));
    }

    private static String relationId(Object value) {
        if (value instanceof Map<?, ?> relation) {
            return text(relation.get("id"));
        }
        return text(value);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
