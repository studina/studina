package com.studina.dinaapi.learning;

/**
 * Una tarjeta candidata a entrar en una ronda de repaso.
 *
 * <p>{@code mastery} es {@code null} cuando la tarjeta todavía no tiene intentos. No es lo
 * mismo que un mastery de {@code 0.0}: en un caso no hay evidencia y en el otro la evidencia
 * dice que la persona no la sabe. La ronda los trata distinto.
 */
public record ReviewCandidate(
        String cardId,
        Double mastery
) {
    public boolean practiced() {
        return mastery != null;
    }

    /** Mastery para ordenar: una tarjeta sin practicar va adelante, como material nuevo. */
    public double masteryForOrdering() {
        return mastery == null ? 0.0 : mastery;
    }
}
