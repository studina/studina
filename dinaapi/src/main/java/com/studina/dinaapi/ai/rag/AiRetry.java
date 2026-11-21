package com.studina.dinaapi.ai.rag;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
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

        Objects.requireNonNull(call, "call es obligatorio");
        // Preserve the existing deterministic delays used by classification and evaluation.
        return execute(maxAttempts, baseDelayMs, 0, sleeper, remaining -> call.get(), listener,
                () -> 0L, LongUnaryOperator.identity());
    }

    /**
     * Bounds the complete operation, including calls and backoff. Each call receives the
     * remaining milliseconds so the provider can also bound its HTTP request timeout.
     * A server retry hint is never shortened to fit the remaining budget.
     */
    public static <T> T withBackoff(
            int maxAttempts,
            long baseDelayMs,
            long maxElapsedMs,
            Sleeper sleeper,
            LongFunction<T> callWithRemainingMillis,
            RetryListener listener) {
        return withBackoff(maxAttempts, baseDelayMs, maxElapsedMs, sleeper, callWithRemainingMillis,
                listener, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), AiRetry::equalJitter);
    }

    static <T> T withBackoff(
            int maxAttempts,
            long baseDelayMs,
            long maxElapsedMs,
            Sleeper sleeper,
            LongFunction<T> callWithRemainingMillis,
            RetryListener listener,
            LongSupplier monotonicMillis,
            LongUnaryOperator jitter) {
        if (maxElapsedMs <= 0) {
            throw new IllegalArgumentException("maxElapsedMs debe ser mayor que cero");
        }
        return execute(maxAttempts, baseDelayMs, maxElapsedMs, sleeper, callWithRemainingMillis,
                listener, monotonicMillis, jitter);
    }

    private static <T> T execute(
            int maxAttempts,
            long baseDelayMs,
            long maxElapsedMs,
            Sleeper sleeper,
            LongFunction<T> call,
            RetryListener listener,
            LongSupplier monotonicMillis,
            LongUnaryOperator jitter) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts debe ser al menos 1");
        }
        if (baseDelayMs < 0) {
            throw new IllegalArgumentException("baseDelayMs no puede ser negativo");
        }
        Objects.requireNonNull(sleeper, "sleeper es obligatorio");
        Objects.requireNonNull(call, "call es obligatorio");
        Objects.requireNonNull(monotonicMillis, "monotonicMillis es obligatorio");
        Objects.requireNonNull(jitter, "jitter es obligatorio");

        boolean bounded = maxElapsedMs > 0;
        long started = monotonicMillis.getAsLong();
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                if (lastError != null) {
                    throw lastError;
                }
                throw new IllegalStateException("Se interrumpió la llamada al proveedor de IA");
            }
            long remaining = remainingMillis(bounded, started, monotonicMillis, maxElapsedMs);
            if (remaining == 0) {
                break;
            }
            try {
                return call.apply(remaining);
            } catch (RuntimeException exception) {
                boolean transientFailure = bounded
                        ? AiProviderErrorMapper.kind(exception) == AiProviderErrorMapper.Kind.SERVICE_UNAVAILABLE
                        : AiProviderErrorMapper.isTransient(exception);
                if (exception instanceof IllegalArgumentException || !transientFailure) {
                    throw exception;
                }
                lastError = exception;
                if (attempt == maxAttempts || Thread.currentThread().isInterrupted()) {
                    break;
                }
                long backoff = exponentialDelay(baseDelayMs, attempt);
                long delay = bounded ? jitter.applyAsLong(backoff) : backoff;
                if (delay < 0 || delay > backoff) {
                    throw new IllegalArgumentException("El jitter debe estar entre cero y el backoff");
                }
                if (bounded) {
                    delay = Math.max(delay, AiProviderErrorMapper.retryAfterMillis(exception).orElse(0));
                    remaining = remainingMillis(true, started, monotonicMillis, maxElapsedMs);
                    if (delay >= remaining) {
                        break;
                    }
                }
                if (listener != null) {
                    listener.onRetry(attempt, maxAttempts, delay);
                }
                if (Thread.currentThread().isInterrupted()
                        || (bounded && delay >= remainingMillis(true, started, monotonicMillis, maxElapsedMs))) {
                    break;
                }
                sleeper.sleep(delay);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Se agotó el tiempo disponible para llamar al proveedor de IA");
    }

    private static long remainingMillis(
            boolean bounded, long started, LongSupplier monotonicMillis, long maxElapsedMs) {
        if (!bounded) {
            return Long.MAX_VALUE;
        }
        long elapsed = monotonicMillis.getAsLong() - started;
        // A negative delta indicates a backwards clock or an unrepresentably large interval.
        return elapsed < 0 || elapsed >= maxElapsedMs ? 0 : maxElapsedMs - elapsed;
    }

    private static long exponentialDelay(long baseDelayMs, int attempt) {
        if (baseDelayMs == 0) {
            return 0;
        }
        int exponent = attempt - 1;
        return exponent >= Long.SIZE - 1 || baseDelayMs > (Long.MAX_VALUE >> exponent)
                ? Long.MAX_VALUE : baseDelayMs << exponent;
    }

    private static long equalJitter(long delay) {
        if (delay <= 1) {
            return delay;
        }
        long lower = delay / 2;
        // The interval stays representable even when exponential backoff saturates.
        return lower + ThreadLocalRandom.current().nextLong(delay - lower + 1);
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
