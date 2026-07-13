package com.studina.dinaapi.auth;

import java.util.Map;
import java.util.Optional;

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

    private final RestClient http = RestClient.create();

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // ── Login helper reutilizable ──────────────────────────────────────────────
    private AuthResponse doLogin(String email, String password) {
        Map<String, Object> loginResp = http.post()
                .uri(directusUrl + "/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(MAP_TYPE);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loginResp.get("data");
        String token = (String) data.get("access_token");

        Map<String, Object> userResp = http.get()
                .uri(directusUrl + "/users/me")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(MAP_TYPE);

        @SuppressWarnings("unchecked")
        Map<String, Object> userData = (Map<String, Object>) userResp.get("data");
        String userId = (String) userData.get("id");
        String firstName = Optional.ofNullable(userData.get("first_name")).map(Object::toString).orElse("");
        String lastName  = Optional.ofNullable(userData.get("last_name")).map(Object::toString).orElse("");
        String name = (firstName + " " + lastName).trim();
        String userEmail = (String) userData.get("email");

        return new AuthResponse(userId, name, userEmail, token);
    }

    // ── POST /auth/login ──────────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        try {
            return ResponseEntity.ok(doLogin(req.email(), req.password()));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Email o contraseña incorrectos"));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al conectar con el servidor de autenticación"));
        }
    }

    // ── POST /auth/register ───────────────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        try {
            // 1. Login como admin para obtener token
            Map<String, Object> adminResp = http.post()
                    .uri(directusUrl + "/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", adminEmail, "password", adminPassword))
                    .retrieve()
                    .body(MAP_TYPE);

            @SuppressWarnings("unchecked")
            Map<String, Object> adminData = (Map<String, Object>) adminResp.get("data");
            String adminToken = (String) adminData.get("access_token");

            // 2. Crear el nuevo usuario
            http.post()
                    .uri(directusUrl + "/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + adminToken)
                    .body(Map.of(
                            "first_name", req.firstName(),
                            "last_name",  req.lastName(),
                            "email",      req.email(),
                            "password",   req.password(),
                            "status",     "active"
                    ))
                    .retrieve()
                    .body(MAP_TYPE);

            // 3. Login con el nuevo usuario
            return ResponseEntity.ok(doLogin(req.email(), req.password()));

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "El email ya está registrado"));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "No se pudo crear la cuenta. Intentá de nuevo"));
        }
    }
}
