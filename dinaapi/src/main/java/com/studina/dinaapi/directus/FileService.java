package com.studina.dinaapi.directus;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class FileService {

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    @Value("${directus.admin-email}")
    private String adminEmail;

    @Value("${directus.admin-password}")
    private String adminPassword;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    public FileService(DirectusAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    // ── Descargar archivo desde Directus ──────────────────────────────────
    public byte[] downloadFile(String fileId) {
        String adminToken = authorizationService.getAdminToken();

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

    public String getFilename(String fileId) {
        String adminToken = authorizationService.getAdminToken();
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/files/" + fileId
                        + "?fields=filename_download,title")
                .header("Authorization", "Bearer " + adminToken)
                .retrieve()
                .body(MAP_TYPE);

        if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException(
                    "Directus no devolvió los datos del archivo"
            );
        }

        Object filename = data.get("filename_download");
        if (filename == null || filename.toString().isBlank()) {
            filename = data.get("title");
        }
        if (filename == null || filename.toString().isBlank()) {
            throw new IllegalStateException(
                    "El archivo no tiene un nombre válido"
            );
        }

        return filename.toString();
    }
}
