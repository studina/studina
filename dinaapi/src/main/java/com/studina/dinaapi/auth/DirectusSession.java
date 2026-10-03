package com.studina.dinaapi.auth;

/**
 * Lo que devuelve Directus al autenticar.
 *
 * <p>El {@code accessToken} dura poco —15 minutos por defecto— y el {@code refreshToken}
 * es lo que permite renovarlo sin volver a pedir la contraseña. {@code expiresInMillis}
 * viaja hasta el frontend para que pueda renovar antes del vencimiento en vez de esperar
 * a comerse un 401 con una escritura en curso.
 */
record DirectusSession(String accessToken, String refreshToken, long expiresInMillis) {}
