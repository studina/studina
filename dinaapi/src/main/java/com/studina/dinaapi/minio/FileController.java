package com.studina.dinaapi.minio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/files")
public class FileController {

    private final RestClient http = RestClient.create();

    @Value("${directus.url}")
    private String directusUrl;

    @Value("${directus.admin-email}")
    private String adminEmail;

    @Value("${directus.admin-password}")
    private String adminPassword;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // ── Token de admin ─────────────────────────────────────────────────────
    private String getAdminToken() {
        Map<String, Object> resp = http.post()
                .uri(directusUrl + "/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", adminEmail, "password", adminPassword))
                .retrieve()
                .body(MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        return (String) data.get("access_token");
    }

    // ── Buscar carpeta por nombre y padre (null = raíz). Devuelve null si no existe ──
    @SuppressWarnings("unchecked")
    private String findFolderOrNull(String adminToken, String name, String parentId) {
        String url = directusUrl + "/folders?filter[name][_eq]=" + name
                + (parentId == null
                        ? "&filter[parent][_null]=true"
                        : "&filter[parent][_eq]=" + parentId)
                + "&fields=id&limit=1";
        Map<String, Object> resp = http.get()
                .uri(url)
                .header("Authorization", "Bearer " + adminToken)
                .retrieve()
                .body(MAP_TYPE);
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        return (data != null && !data.isEmpty()) ? (String) data.get(0).get("id") : null;
    }

    // ── Buscar carpeta; si no existe, crearla ──────────────────────────────
    @SuppressWarnings("unchecked")
    private String getOrCreateFolder(String adminToken, String name, String parentId) {
        String existing = findFolderOrNull(adminToken, name, parentId);
        if (existing != null) return existing;

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (parentId != null) body.put("parent", parentId);

        Map<String, Object> resp = http.post()
                .uri(directusUrl + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + adminToken)
                .body(body)
                .retrieve()
                .body(MAP_TYPE);
        Map<String, Object> folderData = (Map<String, Object>) resp.get("data");
        return (String) folderData.get("id");
    }

    // ── POST /files/upload ─────────────────────────────────────────────────
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") String userId,
            @RequestParam("subjectId") String subjectId) {
        try {
            String adminToken = getAdminToken();

            // Carpeta del usuario → subcarpeta de la materia
            String userFolderId    = getOrCreateFolder(adminToken, userId, null);
            String subjectFolderId = getOrCreateFolder(adminToken, subjectId, userFolderId);

            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() { return file.getOriginalFilename(); }
            };
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream"));

            // Paso 1: subir el archivo
            LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new HttpEntity<>(fileResource, fileHeaders));
            body.add("title", file.getOriginalFilename());

            Map<String, Object> uploadResp = http.post()
                    .uri(directusUrl + "/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("Authorization", "Bearer " + adminToken)
                    .body(body)
                    .retrieve()
                    .body(MAP_TYPE);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) uploadResp.get("data");
            String fileId = (String) data.get("id");

            // Paso 2: mover a la carpeta correspondiente
            http.patch()
                    .uri(directusUrl + "/files/" + fileId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + adminToken)
                    .body(Map.of("folder", subjectFolderId))
                    .retrieve()
                    .toBodilessEntity();

            return ResponseEntity.ok(Map.of("id", fileId, "filename", file.getOriginalFilename()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── GET /files/list ────────────────────────────────────────────────────
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listFiles(
            @RequestParam("userId") String userId,
            @RequestParam("subjectId") String subjectId) {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, String>> files = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            String adminToken = getAdminToken();

            // Buscar carpetas sin crearlas
            String userFolderId = findFolderOrNull(adminToken, userId, null);
            if (userFolderId != null) {
                String subjectFolderId = findFolderOrNull(adminToken, subjectId, userFolderId);
                if (subjectFolderId != null) {
                    String url = directusUrl + "/files?filter[folder][_eq]=" + subjectFolderId
                            + "&fields=id,filename_download,title&limit=1000";

                    Map<String, Object> listResp = http.get()
                            .uri(url)
                            .header("Authorization", "Bearer " + adminToken)
                            .retrieve()
                            .body(MAP_TYPE);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) listResp.get("data");
                    if (data != null) {
                        for (Map<String, Object> item : data) {
                            String id = (String) item.get("id");
                            String filename = (String) item.get("filename_download");
                            if (filename == null || filename.isEmpty()) {
                                filename = (String) item.get("title");
                            }
                            if (filename != null && !filename.isEmpty()) {
                                files.add(Map.of("id", id, "filename", filename));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            response.put("data", files);
            response.put("errors", errors);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
        response.put("data", files);
        response.put("errors", errors);
        return ResponseEntity.ok(response);
    }

    // ── GET /files/download ────────────────────────────────────────────────
    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadFile(@RequestParam("fileId") String fileId) {
        try {
            String adminToken = getAdminToken();
            ResponseEntity<byte[]> directusResp = http.get()
                    .uri(directusUrl + "/assets/" + fileId)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .toEntity(byte[].class);

            MediaType contentType = directusResp.getHeaders().getContentType();
            if (contentType == null) contentType = MediaType.APPLICATION_OCTET_STREAM;

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(directusResp.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

