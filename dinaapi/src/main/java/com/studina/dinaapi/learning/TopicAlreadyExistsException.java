package com.studina.dinaapi.learning;

/**
 * Ya hay un topic con ese nombre normalizado en la materia.
 *
 * <p>Sólo lo tira la creación a mano: la clasificación de la IA reutiliza el existente en
 * silencio, pero cuando el nombre lo escribe el estudiante, unirlo a otro tema sin avisar
 * haría que el que "creó" no aparezca.
 */
public class TopicAlreadyExistsException extends RuntimeException {

    public TopicAlreadyExistsException(String message) {
        super(message);
    }
}
