package com.studina.dinaapi.auth;

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

@Service
public class DirectusAuthorizationService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();

    @Value("${directus.url}")
    private String directusUrl;

    @Value("${directus.admin-email}")
    private String adminEmail;

    @Value("${directus.admin-password}")
    private String adminPassword;

    public String requireUserId(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);

        try {
            Map<String, Object> response = http.get()
                    .uri(directusUrl + "/users/me?fields=id")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(MAP_TYPE);

            Map<String, Object> data = dataMap(response);
            Object userId = data.get("id");

            if (userId == null || userId.toString().isBlank()) {
                throw unauthorized();
            }

            return userId.toString();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401
                    || exception.getStatusCode().value() == 403) {
                throw unauthorized();
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "No se pudo validar la sesión con Directus",
                    exception
            );
        }
    }

    public void requireFileOwnership(String userId, String fileId) {
        ownedFileSubjectFolderName(userId, fileId);
    }

    /**
     * Que el archivo sea del usuario no alcanza cuando el pedido es sobre una materia: uno de
     * otra materia suya pasaría {@link #requireFileOwnership}. La carpeta del archivo se llama
     * como la materia, así que se compara contra eso.
     */
    public void requireFilesInSubject(String userId, String subjectId, List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tenés que seleccionar al menos un archivo"
            );
        }

        for (String fileId : fileIds) {
            if (!subjectId.equals(ownedFileSubjectFolderName(userId, fileId))) {
                throw forbidden();
            }
        }
    }

    /** Recorre archivo → carpeta de la materia → carpeta del usuario y devuelve el nombre de la del medio. */
    private String ownedFileSubjectFolderName(String userId, String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El fileId es obligatorio"
            );
        }

        try {
            String adminToken = getAdminToken();
            Map<String, Object> file = getData(
                    "/files/" + fileId + "?fields=id,folder",
                    adminToken
            );

            String subjectFolderId = relationId(file.get("folder"));
            if (subjectFolderId == null) {
                throw forbidden();
            }

            Map<String, Object> subjectFolder = getData(
                    "/folders/" + subjectFolderId + "?fields=id,name,parent",
                    adminToken
            );
            String userFolderId = relationId(subjectFolder.get("parent"));
            if (userFolderId == null) {
                throw forbidden();
            }

            Map<String, Object> userFolder = getData(
                    "/folders/" + userFolderId + "?fields=id,name,parent",
                    adminToken
            );
            String folderOwner = String.valueOf(userFolder.get("name"));

            if (!userId.equals(folderOwner) || userFolder.get("parent") != null) {
                throw forbidden();
            }
            return String.valueOf(subjectFolder.get("name"));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw forbidden();
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "No se pudo verificar la propiedad del archivo",
                    exception
            );
        }
    }

    public void requireFileOwnership(String userId, List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tenés que seleccionar al menos un archivo"
            );
        }

        fileIds.forEach(fileId -> requireFileOwnership(userId, fileId));
    }

    public void requireSubjectOwnership(String userId, String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El subjectId es obligatorio"
            );
        }

        try {
            Map<String, Object> subject = getData(
                    "/items/subjects/" + subjectId + "?fields=id,user",
                    getAdminToken()
            );

            if (!userId.equals(relationId(subject.get("user")))) {
                throw forbidden();
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw forbidden();
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "No se pudo verificar la propiedad de la materia",
                    exception
            );
        }
    }

    private Map<String, Object> getData(String path, String adminToken) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .body(MAP_TYPE);

        return dataMap(response);
    }

    public String getAdminToken() {
        Map<String, Object> response = http.post()
                .uri(directusUrl + "/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "email", adminEmail,
                        "password", adminPassword
                ))
                .retrieve()
                .body(MAP_TYPE);

        Object token = dataMap(response).get("access_token");
        if (token == null || token.toString().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Directus no devolvió un token administrativo"
            );
        }

        return token.toString();
    }

    private Map<String, Object> dataMap(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map<?, ?>)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Directus devolvió una respuesta inválida"
            );
        }

        Map<String, Object> data = null;
        try {
            data = (Map<String, Object>) response.get("data");
        } catch (ClassCastException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Directus devolvió una respuesta inválida",
                    exception
            );
        }
        return data;
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)
                || authorizationHeader.substring(7).isBlank()) {
            throw unauthorized();
        }

        return authorizationHeader.substring(7).trim();
    }

    private String relationId(Object relation) {
        if (relation == null) {
            return null;
        }

        if (relation instanceof Map<?, ?> relationMap) {
            Object id = relationMap.get("id");
            return id != null ? id.toString() : null;
        }

        return relation.toString();
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "La sesión es inválida o expiró"
        );
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "No tenés permiso para acceder a este archivo"
        );
    }
}
