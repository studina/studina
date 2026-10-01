package com.studina.dinaapi.learning;

/**
 * No existe un intento con ese {@code attempt_id} para ese usuario.
 *
 * <p>Se distingue de un request inválido a propósito: el frontend traduce este caso a crear el
 * intento con la calificación corregida, porque una corrección puede llegar después de que el
 * POST original se haya perdido.
 */
public class AttemptNotFoundException extends RuntimeException {

    public AttemptNotFoundException(String message) {
        super(message);
    }
}
