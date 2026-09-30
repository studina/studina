package com.studina.dinaapi.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Value("${directus.url}")
    private String directusUrl;

    @Value("${directus.admin-email}")
    private String adminEmail;

    @Value("${directus.admin-password}")
    private String adminPassword;

    @Value("${directus.user-role-name:Studina User}")
    private String userRoleName;

    /**
     * El navegador evalúa el Path contra la URL que él pide, que es /api/auth/... porque
     * Vite proxea y recién ahí reescribe el prefijo. El backend no lo ve, así que no puede
     * deducirlo. Acotarlo hace que la cookie viaje sólo en la renovación y el cierre de
     * sesión, y en ningún otro request.
     */
    @Value("${auth.cookie.path:/api/auth}")
    private String cookiePath;

    /** Los navegadores aceptan cookies Secure en localhost, así que en dev no molesta. */
    @Value("${auth.cookie.secure:true}")
    private boolean cookieSecure;

    /** Tiene que acompañar al REFRESH_TOKEN_TTL de Directus, que por defecto son 7 días. */
    @Value("${auth.cookie.max-age-seconds:604800}")
    private long cookieMaxAgeSeconds;

    private final RestClient http = RestClient.create();

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Nombre fijo: no hay nada que configurar y así se puede leer con @CookieValue. */
    private static final String REFRESH_COOKIE = "studina_refresh";

    /** Lo que hace falta para responder un login: el cuerpo y el token que va a la cookie. */
    private record LoginResult(AuthResponse response, String refreshToken) {}

    // ── Login helper reutilizable ──────────────────────────────────────────────
    private LoginResult doLogin(String email, String password) {
        DirectusSession session = loginForSession(email, password, true);
        String userId = getCurrentUserId(session.accessToken());
        String adminToken = getAdminToken();
        Map<String, Object> userData = getUserAsAdmin(adminToken, userId);

        if (relationId(userData.get("role")) == null) {
            String roleId = getOrCreateUserRole(adminToken);
            assignRole(adminToken, userId, roleId);

            // El token anterior conserva role=null. Emitimos uno nuevo para que
            // la sesión actual ya tenga el rol recién asignado.
            session = loginForSession(email, password, true);
            userData = getUserAsAdmin(adminToken, userId);
        }

        UserProfile profile = profileFrom(userId, userData, email);

        return new LoginResult(
                new AuthResponse(
                        profile.userId(),
                        profile.name(),
                        profile.email(),
                        session.accessToken(),
                        session.expiresInMillis()
                ),
                session.refreshToken()
        );
    }

    /**
     * Arma el nombre visible a partir de lo que tenga cargado el usuario en Directus.
     *
     * <p>Lo usan el login y {@code GET /auth/me}: al arrancar con la sesión guardada no hay
     * login de por medio, pero el frontend necesita los mismos datos.
     */
    private UserProfile profileFrom(String userId, Map<String, Object> userData, String fallbackEmail) {
        String firstName = optionalString(userData.get("first_name"));
        String lastName = optionalString(userData.get("last_name"));
        String userEmail = Optional.ofNullable(userData.get("email"))
                .map(obj -> obj.toString())
                .filter(value -> !value.isBlank())
                .orElse(fallbackEmail);
        String name = (firstName + " " + lastName).trim();
        if (name.isBlank()) {
            name = userEmail != null && userEmail.contains("@")
                    ? userEmail.substring(0, userEmail.indexOf('@'))
                    : "Usuario";
        }

        return new UserProfile(userId, name, userEmail);
    }

    // ── Cookie del refresh token ───────────────────────────────────────────────

    /**
     * El refresh token va en una cookie HttpOnly y no en el cuerpo de la respuesta: así el
     * JavaScript nunca lo ve y un XSS no tiene de dónde robarlo. Es lo que permite que
     * recargar no cierre la sesión sin dejar una credencial al alcance de la página.
     */
    private ResponseCookie refreshCookie(String refreshToken) {
        return ResponseCookie.from(REFRESH_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(cookiePath)
                .maxAge(Duration.ofSeconds(cookieMaxAgeSeconds))
                .build();
    }

    /** Misma cookie vacía y vencida: es la única forma de que el navegador la borre. */
    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(cookiePath)
                .maxAge(0)
                .build();
    }

    private ResponseEntity<?> respondWithSession(LoginResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
                .body(result.response());
    }

    private DirectusSession loginForSession(
            String email,
            String password,
            boolean userCredentials) {
        Map<String, Object> loginResponse;

        try {
            loginResponse = http.post()
                    .uri(directusUrl + "/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    // mode=json deja el refresh token en el cuerpo. Con el modo cookie
                    // quedaría en una cookie de este backend, que no es quien después
                    // tiene que renovar: el navegador habla con /auth/refresh.
                    .body(Map.of(
                            "email", email,
                            "password", password,
                            "mode", "json"
                    ))
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

        return sessionFrom(dataMap(loginResponse));
    }

    /**
     * Arma la sesión a partir de la respuesta de Directus.
     *
     * <p>{@code expires} puede faltar: en ese caso viaja un 0 y el frontend se queda sin
     * renovación preventiva, pero sigue teniendo el reintento ante un 401. Es preferible
     * a inventar una duración que no sabemos.
     */
    private DirectusSession sessionFrom(Map<String, Object> data) {
        Object token = data.get("access_token");
        if (token == null || token.toString().isBlank()) {
            throw new IllegalStateException("Directus no devolvió un token de acceso");
        }

        Object refreshToken = data.get("refresh_token");
        Object expires = data.get("expires");

        return new DirectusSession(
                token.toString(),
                refreshToken == null ? null : refreshToken.toString(),
                expires instanceof Number number ? number.longValue() : 0L
        );
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
        return loginForSession(adminEmail, adminPassword, false).accessToken();
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
            return respondWithSession(doLogin(email, req.password()));
        } catch (InvalidCredentialsException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Email o contraseña incorrectos"));
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al conectar con el servidor de autenticación"));
        }
    }

    // ── POST /auth/refresh ────────────────────────────────────────────────────

    /**
     * Renueva el token de acceso a partir del refresh token.
     *
     * <p>El access token de Directus vive 15 minutos. Sin esto, cualquier pantalla abierta
     * más de ese rato empezaba a recibir 401: en flashcards las autoevaluaciones se escriben
     * en segundo plano, así que el usuario terminaba la ronda sin enterarse de que la mitad
     * no se había guardado.
     *
     * <p>Un refresh token inválido o vencido devuelve 401: no es un error del servidor, es
     * una sesión que se terminó y hay que volver a iniciar.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return sessionExpired();
        }

        try {
            Map<String, Object> response = http.post()
                    .uri(directusUrl + "/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "refresh_token", refreshToken,
                            "mode", "json"
                    ))
                    .retrieve()
                    .body(MAP_TYPE);

            DirectusSession session = sessionFrom(dataMap(response));

            // Directus rota el refresh token en cada renovación, así que hay que volver a
            // setear la cookie: si no, la próxima renovación usaría uno ya consumido.
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                    .body(new RefreshResponse(
                            session.accessToken(),
                            session.expiresInMillis()
                    ));
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 400 || status == 401 || status == 403) {
                // La sesión se terminó: además de avisar, se borra la cookie que ya no sirve.
                return sessionExpired();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al conectar con el servidor de autenticación"));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al conectar con el servidor de autenticación"));
        }
    }

    private ResponseEntity<?> sessionExpired() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(Map.of("error", "La sesión expiró. Volvé a iniciar sesión"));
    }

    // ── POST /auth/logout ─────────────────────────────────────────────────────

    /**
     * Cierra la sesión de verdad: borra la cookie e invalida el refresh token en Directus.
     *
     * <p>Sin lo segundo el token seguiría siendo válido hasta vencer, así que una copia
     * robada sobreviviría al cierre de sesión. Si Directus falla igual se borra la cookie:
     * para el usuario la sesión terminó.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                http.post()
                        .uri(directusUrl + "/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("refresh_token", refreshToken, "mode", "json"))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException | IllegalStateException exception) {
                logger.warn("No se pudo invalidar la sesión en Directus: {}", exception.getMessage());
            }
        }

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .build();
    }

    // ── GET /auth/me ──────────────────────────────────────────────────────────

    /**
     * Quién es el dueño del access token.
     *
     * <p>Hace falta al arrancar con la sesión guardada: la renovación devuelve un token
     * pero ningún dato del usuario, y el nombre se muestra en el menú.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String accessToken = bearerToken(authorizationHeader);
        if (accessToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Falta el token de acceso"));
        }

        try {
            String userId = getCurrentUserId(accessToken);
            Map<String, Object> userData = getUserAsAdmin(getAdminToken(), userId);
            return ResponseEntity.ok(profileFrom(userId, userData, null));
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "La sesión expiró. Volvé a iniciar sesión"));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al conectar con el servidor de autenticación"));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al conectar con el servidor de autenticación"));
        }
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        return token.isBlank() ? null : token;
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

            return respondWithSession(doLogin(email, req.password()));

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
