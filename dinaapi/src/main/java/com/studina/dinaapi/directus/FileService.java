package com.studina.dinaapi.directus;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class FileService {

    private final RestClient http = RestClient.create();

    @Value("${directus.url}")
    private String directusUrl;

    @Value("${directus.admin-email}")
    private String adminEmail;

    @Value("${directus.admin-password}")
    private String adminPassword;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // ── Obtener token de administrador de Directus ─────────────────────────
    private String getAdminToken() {
        Map<String, Object> response = http.post()
                .uri(directusUrl + "/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "email", adminEmail,
                        "password", adminPassword
                ))
                .retrieve()
                .body(MAP_TYPE);

        if (response == null || response.get("data") == null) {
            throw new IllegalStateException(
                    "Directus no devolvió información de autenticación"
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data =
                (Map<String, Object>) response.get("data");

        return (String) data.get("access_token");
    }

    // ── Descargar archivo desde Directus ──────────────────────────────────
    public byte[] downloadFile(String fileId) {
        String adminToken = getAdminToken();

        byte[] bytes = http.get()
                .uri(directusUrl + "/assets/" + fileId)
                .header("Authorization", "Bearer " + adminToken)
                .retrieve()
                .body(byte[].class);

        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException(
                    "El archivo está vacío o no pudo descargarse"
            );
        }

        return bytes;
    }
}