package com.studina.dinaapi.directus;

import java.io.IOException;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.studina.dinaapi.ai.rag.RagIndexService;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/files")
public class FileController {

    private final RestClient http = RestClient.create();
    private final RagIndexService ragIndexService;
    private final DirectusAuthorizationService authorizationService;

    public FileController(
            RagIndexService ragIndexService,
            DirectusAuthorizationService authorizationService) {
        this.ragIndexService = ragIndexService;
        this.authorizationService = authorizationService;
    }

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
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader,
            @RequestParam("subjectId") String subjectId) {
        String userId = authorizationService.requireUserId(authorizationHeader);

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

            String filename = file.getOriginalFilename();

            boolean isPdf =
                    "application/pdf".equalsIgnoreCase(
                            file.getContentType()
                    )
                    || (
                            filename != null
                            && filename.toLowerCase().endsWith(".pdf")
                    );

            boolean indexed = false;
            int chunks = 0;
            String indexingError = null;

            if (isPdf) {
                try {
                    chunks = ragIndexService.indexPdf(
                            fileId,
                            filename,
                            userId,
                            subjectId
                    );

                    indexed = true;

                } catch (IOException indexingException) {

                    indexingError =
                            indexingException.getMessage() != null
                                    ? indexingException.getMessage()
                                    : "Error desconocido al indexar";
                }
            }

            Map<String, Object> response = new HashMap<>();

            response.put("id", fileId);
            response.put("filename", filename);
            response.put("indexed", indexed);
            response.put("chunks", chunks);

            if (!isPdf) {
                response.put(
                        "indexingMessage",
                        "El archivo no es PDF y no se indexó"
                );
            }

            if (indexingError != null) {
                response.put("indexingError", indexingError);
            }

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── GET /files/list ────────────────────────────────────────────────────
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listFiles(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader,
            @RequestParam("subjectId") String subjectId) {
        String userId = authorizationService.requireUserId(authorizationHeader);

        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            String adminToken = getAdminToken();

            // Buscar carpetas sin crearlas
            String userFolderId = findFolderOrNull(adminToken, userId, null);
            if (userFolderId != null) {
                String subjectFolderId = findFolderOrNull(adminToken, subjectId, userFolderId);
                if (subjectFolderId != null) {
                    String url = directusUrl + "/files?filter[folder][_eq]=" + subjectFolderId
                            + "&fields=id,filename_download,title,tags&limit=1000";

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
                                Object rawTags = item.get("tags");

                                    List<String> tags = new ArrayList<>();

                                    if (rawTags instanceof List<?> tagList) {
                                        for (Object tag : tagList) {
                                            if (tag != null) {
                                                tags.add(tag.toString());
                                            }
                                        }
                                    }

                                    Map<String, Object> fileData = new HashMap<>();
                                    fileData.put("id", id);
                                    fileData.put("filename", filename);
                                    fileData.put("tags", tags);

                                    files.add(fileData);
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
    public ResponseEntity<byte[]> downloadFile(
            @RequestParam("fileId") String fileId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireFileOwnership(userId, fileId);

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

    // ── DELETE /files/{fileId}: eliminar un archivo ────────────────────────────
    @DeleteMapping("/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable String fileId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireFileOwnership(userId, fileId);

        try {
            String adminToken = getAdminToken();

            http.delete()
                    .uri(directusUrl + "/files/" + fileId)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .toBodilessEntity();

            return ResponseEntity.ok(
                    Map.of(
                            "fileId", fileId,
                            "message", "Archivo eliminado correctamente"
                    )
            );

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── PATCH /files/tags ─────────────────────────────────────────────────────
    @PatchMapping(
            value = "/tags",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> updateTags(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        Object requestedFileId = request.get("fileId");
        String fileId = requestedFileId instanceof String
                ? (String) requestedFileId
                : null;
        authorizationService.requireFileOwnership(userId, fileId);

        try {
            Object rawTags = request.get("tags");

            List<String> tags = new ArrayList<>();

            if (rawTags instanceof List<?> tagList) {
                for (Object tag : tagList) {
                    if (tag != null) {
                        String normalized = tag.toString()
                                .trim()
                                .toLowerCase()
                                .replaceAll("\\s+", "-");

                        if (!normalized.isBlank() && !tags.contains(normalized)) {
                            tags.add(normalized);
                        }
                    }
                }
            }

            String adminToken = getAdminToken();

            http.patch()
                    .uri(directusUrl + "/files/" + fileId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + adminToken)
                    .body(Map.of("tags", tags))
                    .retrieve()
                    .toBodilessEntity();

            return ResponseEntity.ok(
                    Map.of(
                            "fileId", fileId,
                            "tags", tags
                    )
            );

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
