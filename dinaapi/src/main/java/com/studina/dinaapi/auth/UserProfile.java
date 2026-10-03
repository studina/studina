package com.studina.dinaapi.auth;

/**
 * Los datos del usuario que el frontend muestra.
 *
 * <p>Existe porque al arrancar con la sesión guardada sólo tenemos la cookie: la renovación
 * devuelve un token pero ningún dato de quién es. {@code GET /auth/me} los recupera.
 */
public record UserProfile(String userId, String name, String email) {}
