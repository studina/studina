package com.studina.dinaapi.auth;

/**
 * Lo que recibe el frontend al iniciar sesión.
 *
 * <p>No trae el refresh token a propósito: viaja en una cookie {@code HttpOnly} que el
 * JavaScript no puede leer, así que un XSS no tiene de dónde robarlo. Acá va sólo el access
 * token, que dura 15 minutos y vive en memoria.
 */
public record AuthResponse(
        String userId,
        String name,
        String email,
        String token,
        long expiresIn) {}
