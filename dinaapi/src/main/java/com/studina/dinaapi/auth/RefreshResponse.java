package com.studina.dinaapi.auth;

/** El access token renovado. El refresh token rotado vuelve en la cookie, no acá. */
public record RefreshResponse(String token, long expiresIn) {}
