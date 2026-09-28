package com.studina.dinaapi.ai.rag;

import java.util.function.Supplier;

/**
 * Reintento con backoff exponencial para llamadas al proveedor de IA.
 *
 * <p>Sólo reintenta lo que se resuelve solo —modelo saturado, servicio momentáneamente caído—
 * según {@link AiProviderErrorMapper#isTransient}. Cualquier otro error se propaga en el acto:
 * un request inválido va a fallar igual la segunda vez, y reintentarlo sólo agrega demora.
 *
 * <p>La espera se inyecta para que los tests no tarden lo que tarda el backoff real.
 */
public final class AiRetry {

    /** Cómo esperar entre intentos. En producción duerme; en los tests, no. */
    public interface Sleeper {
        void sleep(long millis);
    }

    private AiRetry() {
    }

    public static <T> T withBackoff(
            int maxAttempts,
            long baseDelayMs,
            Sleeper sleeper,
            Supplier<T> call,
            RetryListener listener) {

        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts debe ser al menos 1");
        }

        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException exception) {
                if (!AiProviderErrorMapper.isTransient(exception)) {
                    throw exception;
                }
                lastError = exception;
                if (attempt == maxAttempts) {
                    break;
                }
                long delay = baseDelayMs * (1L << (attempt - 1));
                if (listener != null) {
                    listener.onRetry(attempt, maxAttempts, delay);
                }
                sleeper.sleep(delay);
            }
        }

        throw lastError;
    }

    /** Para dejar rastro de cada reintento sin acoplar esta clase a un logger. */
    public interface RetryListener {
        void onRetry(int attempt, int maxAttempts, long delayMs);
    }

    /** Duerme de verdad, restaurando la marca de interrupción si la espera se corta. */
    public static Sleeper realSleeper() {
        return millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Se interrumpió la espera entre reintentos", interrupted);
            }
        };
    }
}
