package com.studina.dinaapi.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(DirectusAuthorizationService.class);

    /** Antes de que venza, el token se renueva con este margen para no perderlo a mitad de un request. */
    private static final Duration ADMIN_TOKEN_REFRESH_MARGIN = Duration.ofSeconds(60);

    /** Si Directus no informa el vencimiento, se asume uno corto antes que reusar un token muerto. */
    private static final Duration DEFAULT_ADMIN_TOKEN_TTL = Duration.ofMinutes(5);

    private final RestClient http = RestClient.create();

    /**
     * El token de admin, reusado entre requests.
     *
     * <p>Antes cada llamada a {@link #getAdminToken()} hacía un login, y Directus demora cada login
     * a propósito ({@code LOGIN_STALL_TIME}, 500 ms por defecto). Como se pide en casi cada
     * consulta, una subida con detección de temas sumaba unos 16 logins: ~8 s por archivo sólo de
     * espera, y una sesión nueva en Directus por cada uno.
     */
    private volatile AdminToken adminToken;

    /** Reemplazable en los tests para simular el paso del tiempo sin esperarlo. */
    private Clock clock = Clock.systemUTC();

    private record AdminToken(String value, Instant refreshAt) {
    }

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
            return withAdminToken(adminToken -> {
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
            });
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
            Map<String, Object> subject = withAdminToken(adminToken -> getData(
                    "/items/subjects/" + subjectId + "?fields=id,user",
                    adminToken
            ));

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

    /**
     * Corre una consulta con el token de admin y, si Directus lo rechaza con un 401, la repite
     * una sola vez con uno nuevo.
     *
     * <p>El token cacheado puede morir antes de tiempo, por ejemplo si se recrea Directus con la
     * API levantada. Estas verificaciones de propiedad corren al principio de casi todos los
     * requests, así que acá se detecta el token muerto y se reemplaza para todos los demás, en
     * lugar de dejarlo roto hasta su vencimiento.
     */
    private <T> T withAdminToken(Function<String, T> query) {
        try {
            return query.apply(getAdminToken());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 401) {
                throw exception;
            }
            logger.warn("Directus rechazó el token de admin cacheado; se pide uno nuevo y se reintenta");
            invalidateAdminToken();
            return query.apply(getAdminToken());
        }
    }

    public String getAdminToken() {
        AdminToken current = adminToken;
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.value();
        }
        synchronized (this) {
            // Otro hilo pudo haberlo renovado mientras éste esperaba el lock: así varios requests
            // con el token vencido hacen un solo login y no uno cada uno.
            current = adminToken;
            if (current != null && clock.instant().isBefore(current.refreshAt())) {
                return current.value();
            }
            adminToken = login();
            return adminToken.value();
        }
    }

    public void invalidateAdminToken() {
        adminToken = null;
    }

    private AdminToken login() {
        logger.debug("Login de admin en Directus");
        Map<String, Object> response = http.post()
                .uri(directusUrl + "/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "email", adminEmail,
                        "password", adminPassword
                ))
                .retrieve()
                .body(MAP_TYPE);

        Map<String, Object> data = dataMap(response);
        Object token = data.get("access_token");
        if (token == null || token.toString().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Directus no devolvió un token administrativo"
            );
        }

        return new AdminToken(token.toString(), clock.instant().plus(reuseWindow(data.get("expires"))));
    }

    /**
     * Cuánto se reusa un token: su vida ({@code expires}, en ms) menos el margen. Si la vida es
     * tan corta que el margen se la comería, se renueva a la mitad.
     */
    private Duration reuseWindow(Object expires) {
        Duration ttl = expires instanceof Number millis && millis.longValue() > 0
                ? Duration.ofMillis(millis.longValue())
                : DEFAULT_ADMIN_TOKEN_TTL;
        if (ttl.compareTo(ADMIN_TOKEN_REFRESH_MARGIN.multipliedBy(2)) < 0) {
            return ttl.dividedBy(2);
        }
        return ttl.minus(ADMIN_TOKEN_REFRESH_MARGIN);
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
