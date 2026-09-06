package com.studina.dinaapi.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Value("${directus.url}")
    private String directusUrl;

    @Value("${directus.admin-email}")
    private String adminEmail;

    @Value("${directus.admin-password}")
    private String adminPassword;

    @Value("${directus.user-role-name:Studina User}")
    private String userRoleName;

    private final RestClient http = RestClient.create();

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    // ── Login helper reutilizable ──────────────────────────────────────────────
    private AuthResponse doLogin(String email, String password) {
        String token = loginForToken(email, password, true);
        String userId = getCurrentUserId(token);
        String adminToken = getAdminToken();
        Map<String, Object> userData = getUserAsAdmin(adminToken, userId);

        if (relationId(userData.get("role")) == null) {
            String roleId = getOrCreateUserRole(adminToken);
            assignRole(adminToken, userId, roleId);

            // El token anterior conserva role=null. Emitimos uno nuevo para que
            // la sesión actual ya tenga el rol recién asignado.
            token = loginForToken(email, password, true);
            userData = getUserAsAdmin(adminToken, userId);
        }

        String firstName = optionalString(userData.get("first_name"));
        String lastName = optionalString(userData.get("last_name"));
        String userEmail = Optional.ofNullable(userData.get("email"))
                .map(obj -> obj.toString())
                .filter(value -> !value.isBlank())
                .orElse(email);
        String name = (firstName + " " + lastName).trim();
        if (name.isBlank()) {
            name = userEmail.contains("@")
                    ? userEmail.substring(0, userEmail.indexOf('@'))
                    : "Usuario";
        }

        return new AuthResponse(userId, name, userEmail, token);
    }

    private String loginForToken(
            String email,
            String password,
            boolean userCredentials) {
        Map<String, Object> loginResponse;

        try {
            loginResponse = http.post()
                    .uri(directusUrl + "/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", password))
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (userCredentials
                    && (status == 400 || status == 401 || status == 403)) {
                throw new InvalidCredentialsException();
            }
            throw exception;
        }

        Object token = dataMap(loginResponse).get("access_token");
        if (token == null || token.toString().isBlank()) {
            throw new IllegalStateException("Directus no devolvió un token de acceso");
        }

        return token.toString();
    }

    private String getCurrentUserId(String token) {
        Map<String, Object> userResponse = http.get()
                .uri(directusUrl + "/users/me?fields=id")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(MAP_TYPE);

        Object userId = dataMap(userResponse).get("id");
        if (userId == null || userId.toString().isBlank()) {
            throw new IllegalStateException("Directus no devolvió el usuario autenticado");
        }

        return userId.toString();
    }

    private String getAdminToken() {
        return loginForToken(adminEmail, adminPassword, false);
    }

    private Map<String, Object> getUserAsAdmin(String adminToken, String userId) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/users/" + userId
                        + "?fields=id,first_name,last_name,email,role")
                .header("Authorization", "Bearer " + adminToken)
                .retrieve()
                .body(MAP_TYPE);

        return dataMap(response);
    }

    @SuppressWarnings("unchecked")
    private String findUserRole(String adminToken) {
        String encodedRoleName = URLEncoder.encode(
                userRoleName,
                StandardCharsets.UTF_8
        );
        Map<String, Object> response = http.get()
                .uri(directusUrl
                        + "/roles?filter[name][_eq]=" + encodedRoleName
                        + "&fields=id&limit=1")
                .header("Authorization", "Bearer " + adminToken)
                .retrieve()
                .body(MAP_TYPE);

        Object rawData = response != null ? response.get("data") : null;
        if (!(rawData instanceof List<?> roles) || roles.isEmpty()) {
            return null;
        }

        Map<String, Object> role = (Map<String, Object>) roles.getFirst();
        return relationId(role);
    }

    private String getOrCreateUserRole(String adminToken) {
        String existingRole = findUserRole(adminToken);
        if (existingRole != null) {
            return existingRole;
        }

        Map<String, Object> response = http.post()
                .uri(directusUrl + "/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + adminToken)
                .body(Map.of(
                        "name", userRoleName,
                        "icon", "school",
                        "description", "Usuarios estudiantes de Studina",
                        "admin_access", false,
                        "app_access", false
                ))
                .retrieve()
                .body(MAP_TYPE);

        Object roleId = dataMap(response).get("id");
        if (roleId == null) {
            throw new IllegalStateException("Directus no devolvió el rol creado");
        }

        return roleId.toString();
    }

    private void assignRole(String adminToken, String userId, String roleId) {
        http.patch()
                .uri(directusUrl + "/users/" + userId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + adminToken)
                .body(Map.of("role", roleId))
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataMap(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map<?, ?>)) {
            throw new IllegalStateException("Directus devolvió una respuesta inválida");
        }

        return (Map<String, Object>) response.get("data");
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

    private String optionalString(Object value) {
        return Optional.ofNullable(value)
                .map(obj -> obj.toString())
                .orElse("");
    }

    private static class InvalidCredentialsException
            extends RuntimeException {
    }

    // ── POST /auth/login ──────────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        String email = req.email() == null ? "" : req.email().trim();
        if (!EMAIL_PATTERN.matcher(email).matches()
                || req.password() == null
                || req.password().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Email o contraseña incorrectos"));
        }

        try {
            return ResponseEntity.ok(doLogin(email, req.password()));
        } catch (InvalidCredentialsException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Email o contraseña incorrectos"));
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al conectar con el servidor de autenticación"));
        }
    }

    // ── POST /auth/register ───────────────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        String email = req.email() == null ? "" : req.email().trim();
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Ingresá un email válido"));
        }

        try {
            String adminToken = getAdminToken();
            String roleId = getOrCreateUserRole(adminToken);

            Map<String, Object> userBody = new HashMap<>();
            userBody.put("first_name", req.firstName());
            userBody.put("last_name", req.lastName());
            userBody.put("email", email);
            userBody.put("password", req.password());
            userBody.put("status", "active");
            userBody.put("role", roleId);

            http.post()
                    .uri(directusUrl + "/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + adminToken)
                    .body(userBody)
                    .retrieve()
                    .body(MAP_TYPE);

            return ResponseEntity.ok(doLogin(email, req.password()));

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            if (status == 400 && responseBody.contains("RECORD_NOT_UNIQUE")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "El email ya está registrado"));
            }
            if (status == 400) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Revisá los datos ingresados"));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "No se pudo crear la cuenta. Intentá de nuevo"));
        }
    }
}
