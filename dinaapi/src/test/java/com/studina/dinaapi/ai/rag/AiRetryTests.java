package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.core.JsonProcessingException;

/** El bucle de reintento, con la espera inyectada para que los tests no tarden el backoff real. */
class AiRetryTests {

    private final List<Long> esperas = new ArrayList<>();
    private final AiRetry.Sleeper sleeperFalso = esperas::add;

    private static RuntimeException sobrecarga() {
        return new RuntimeException("503 . This model is currently experiencing high demand.");
    }

    @Test
    void returnsWithoutRetryingWhenTheFirstCallWorks() {
        AtomicInteger llamadas = new AtomicInteger();

        String resultado = AiRetry.withBackoff(3, 1000, sleeperFalso, () -> {
            llamadas.incrementAndGet();
            return "ok";
        }, null);

        assertThat(resultado).isEqualTo("ok");
        assertThat(llamadas.get()).isEqualTo(1);
        assertThat(esperas).isEmpty();
    }

    @Test
    void recoversWhenTheProviderComesBack() {
        AtomicInteger llamadas = new AtomicInteger();

        // Es el caso que motivó todo esto: el primer intento cae, el segundo anda.
        String resultado = AiRetry.withBackoff(3, 1000, sleeperFalso, () -> {
            if (llamadas.incrementAndGet() == 1) {
                throw sobrecarga();
            }
            return "clasificado";
        }, null);

        assertThat(resultado).isEqualTo("clasificado");
        assertThat(llamadas.get()).isEqualTo(2);
        assertThat(esperas).containsExactly(1000L);
    }

    @Test
    void givesUpAfterTheConfiguredAttemptsWithExponentialBackoff() {
        AtomicInteger llamadas = new AtomicInteger();

        assertThatThrownBy(() -> AiRetry.withBackoff(3, 1000, sleeperFalso, () -> {
            llamadas.incrementAndGet();
            throw sobrecarga();
        }, null)).hasMessageContaining("503");

        assertThat(llamadas.get()).isEqualTo(3);
        // Espera entre intentos, no después del último: el backoff duplica cada vez.
        assertThat(esperas).containsExactly(1000L, 2000L);
    }

    @Test
    void failsImmediatelyWhenRetryingCannotHelp() {
        AtomicInteger llamadas = new AtomicInteger();

        assertThatThrownBy(() -> AiRetry.withBackoff(3, 1000, sleeperFalso, () -> {
            llamadas.incrementAndGet();
            throw new RuntimeException("400 Invalid request");
        }, null)).hasMessageContaining("400");

        assertThat(llamadas.get())
                .as("un request inválido va a fallar igual la segunda vez")
                .isEqualTo(1);
        assertThat(esperas).isEmpty();
    }

    @Test
    void reportsEachRetryForTheLog() {
        List<String> avisos = new ArrayList<>();

        assertThatThrownBy(() -> AiRetry.withBackoff(3, 500, sleeperFalso,
                () -> {
                    throw sobrecarga();
                },
                (intento, maximo, espera) ->
                        avisos.add("intento %d/%d espera %d".formatted(intento, maximo, espera))))
                .hasMessageContaining("503");

        assertThat(avisos).containsExactly("intento 1/3 espera 500", "intento 2/3 espera 1000");
    }

    @Test
    void passesTheRemainingBudgetAfterCallTimeAndJitteredBackoff() {
        AtomicLong clock = new AtomicLong();
        List<Long> budgets = new ArrayList<>();
        List<Long> notifiedDelays = new ArrayList<>();

        String result = AiRetry.withBackoff(2, 1000, 60_000, delay -> {
            esperas.add(delay);
            clock.addAndGet(delay);
        }, remaining -> {
            budgets.add(remaining);
            if (budgets.size() == 1) {
                clock.addAndGet(1200);
                throw unavailable(null);
            }
            return "ok";
        }, (attempt, maximum, delay) -> notifiedDelays.add(delay), clock::get, delay -> 750);

        assertThat(result).isEqualTo("ok");
        assertThat(budgets).containsExactly(60_000L, 58_050L);
        assertThat(esperas).containsExactly(750L);
        assertThat(notifiedDelays).containsExactly(750L);
    }

    @Test
    void honorsRetryAfterWhenItIsLongerThanJitteredBackoff() {
        AtomicLong clock = new AtomicLong();
        List<Long> budgets = new ArrayList<>();

        String result = AiRetry.withBackoff(2, 1000, 60_000, delay -> {
            esperas.add(delay);
            clock.addAndGet(delay);
        }, remaining -> {
            budgets.add(remaining);
            if (budgets.size() == 1) {
                clock.addAndGet(1000);
                throw unavailable(10_000L);
            }
            return "ok";
        }, null, clock::get, delay -> 500);

        assertThat(result).isEqualTo("ok");
        assertThat(esperas).containsExactly(10_000L);
        assertThat(budgets).containsExactly(60_000L, 49_000L);
    }

    @Test
    void doesNotShortenBackoffForAnEarlierServerHint() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 60_000, sleeperFalso, remaining -> {
            calls.incrementAndGet();
            throw unavailable(100L);
        }, null, () -> 0L, delay -> 750)).isInstanceOf(AiProviderErrorMapper.HttpFailure.class);

        assertThat(calls).hasValue(2);
        assertThat(esperas).containsExactly(750L);
    }

    @Test
    void preservesTheLastFailureWithoutSleepingAfterTheFinalAttempt() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException first = unavailable(null);
        RuntimeException last = unavailable(null);

        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 60_000, sleeperFalso, remaining -> {
            throw calls.incrementAndGet() == 1 ? first : last;
        }, null, () -> 0L, LongUnaryOperator.identity())).isSameAs(last);

        assertThat(calls).hasValue(2);
        assertThat(esperas).containsExactly(1000L);
    }

    @ParameterizedTest
    @MethodSource("hintsThatCannotFit")
    void stopsWithoutSleepingOrRetryingBeforeAnUnfittableServerHint(long hint) {
        AtomicInteger calls = new AtomicInteger();
        List<Long> notifiedDelays = new ArrayList<>();
        RuntimeException original = unavailable(hint);

        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 10_000, sleeperFalso, remaining -> {
            calls.incrementAndGet();
            throw original;
        }, (attempt, maximum, delay) -> notifiedDelays.add(delay), () -> 0L, LongUnaryOperator.identity()))
                .isSameAs(original);

        assertThat(calls).hasValue(1);
        assertThat(esperas).isEmpty();
        assertThat(notifiedDelays).isEmpty();
    }

    static Stream<Long> hintsThatCannotFit() {
        return Stream.of(10_000L, 10_001L, Long.MAX_VALUE);
    }

    @Test
    void stopsWhenTheFailedCallConsumesTheEntireBudget() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        RuntimeException original = unavailable(null);

        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 60_000, sleeperFalso, remaining -> {
            calls.incrementAndGet();
            clock.addAndGet(remaining);
            throw original;
        }, null, clock::get, LongUnaryOperator.identity())).isSameAs(original);

        assertThat(calls).hasValue(1);
        assertThat(esperas).isEmpty();
    }

    @Test
    void rechecksTheBudgetAfterAnOversleepInsteadOfStartingAnotherCall() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        RuntimeException original = unavailable(null);

        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 2000,
                delay -> clock.addAndGet(2000), remaining -> {
                    calls.incrementAndGet();
                    throw original;
                }, null, clock::get, LongUnaryOperator.identity())).isSameAs(original);

        assertThat(calls).hasValue(1);
    }

    @Test
    void rechecksTheBudgetIfTheRetryListenerTakesTooLong() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        RuntimeException original = unavailable(null);

        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 2000, sleeperFalso, remaining -> {
            calls.incrementAndGet();
            throw original;
        }, (attempt, maximum, delay) -> clock.addAndGet(2000), clock::get, LongUnaryOperator.identity()))
                .isSameAs(original);

        assertThat(calls).hasValue(1);
        assertThat(esperas).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    void doesNotRetryInvalidPayloadsQuotaTimeoutsOrConnectivity(RuntimeException failure) {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 60_000, sleeperFalso, remaining -> {
            calls.incrementAndGet();
            throw failure;
        }, null, () -> 0L, LongUnaryOperator.identity())).isSameAs(failure);

        assertThat(calls).hasValue(1);
        assertThat(esperas).isEmpty();
    }

    static Stream<RuntimeException> nonRetryableFailures() {
        return Stream.of(
                new RuntimeException("Provider overloaded; try again later"),
                new IllegalArgumentException("503 Invalid generated JSON"),
                new RuntimeException(new JsonProcessingException("503 Invalid JSON") { }),
                new AiProviderErrorMapper.HttpFailure(429, 1000L, true),
                new RuntimeException("503", new java.net.SocketTimeoutException("Read timed out")),
                new RuntimeException("503", new java.net.ConnectException("Connection refused")));
    }

    @Test
    void preservesLegacyDelaysEvenWhenTheFailureCarriesRetryAfter() {
        RuntimeException failure = unavailable(10_000L);

        assertThatThrownBy(() -> AiRetry.withBackoff(3, 1000, sleeperFalso, () -> {
            throw failure;
        }, null)).isSameAs(failure);

        assertThat(esperas).containsExactly(1000L, 2000L);
    }

    @Test
    void keepsLegacyTextualOverloadRecoveryOutsideTheBoundedResourcePolicy() {
        AtomicInteger calls = new AtomicInteger();
        String result = AiRetry.withBackoff(3, 1000, sleeperFalso, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("Provider overloaded; try again later");
            }
            return "evaluated";
        }, null);
        assertThat(result).isEqualTo("evaluated");
        assertThat(calls).hasValue(3);
        assertThat(esperas).containsExactly(1000L, 2000L);
    }

    @Test
    void saturatesExponentialBackoffInsteadOfOverflowingOrWrappingTheShift() {
        assertThatThrownBy(() -> AiRetry.withBackoff(66, 1, sleeperFalso, () -> {
            throw unavailable(null);
        }, null)).isInstanceOf(AiProviderErrorMapper.HttpFailure.class);

        assertThat(esperas).hasSize(65).allSatisfy(delay -> assertThat(delay).isPositive());
        assertThat(esperas.get(62)).isEqualTo(1L << 62);
        assertThat(esperas.get(63)).isEqualTo(Long.MAX_VALUE);
        assertThat(esperas.get(64)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void treatsAnUnrepresentablyLargeElapsedTimeAsBudgetExhaustion() {
        AtomicLong clock = new AtomicLong(Long.MIN_VALUE);
        AtomicInteger calls = new AtomicInteger();
        RuntimeException original = unavailable(null);

        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, Long.MAX_VALUE, sleeperFalso, remaining -> {
            calls.incrementAndGet();
            clock.set(Long.MAX_VALUE);
            throw original;
        }, null, clock::get, LongUnaryOperator.identity())).isSameAs(original);

        assertThat(calls).hasValue(1);
        assertThat(esperas).isEmpty();
    }

    @Test
    void stopsOnAnInterruptedThreadWithoutClearingTheFlag() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException original = unavailable(null);
        try {
            assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 60_000, delay -> {
                Thread.currentThread().interrupt();
            }, remaining -> {
                calls.incrementAndGet();
                throw original;
            }, null, () -> 0L, LongUnaryOperator.identity())).isSameAs(original);

            assertThat(calls).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void doesNotStartACallWhenAlreadyInterrupted() {
        AtomicInteger calls = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 60_000, sleeperFalso, remaining -> {
                calls.incrementAndGet();
                return "unreachable";
            }, null, () -> 0L, LongUnaryOperator.identity())).isInstanceOf(IllegalStateException.class);

            assertThat(calls).hasValue(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsInvalidRetryArgumentsBeforeCallingTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        LongFunction<String> call = remaining -> {
            calls.incrementAndGet();
            return "unreachable";
        };
        assertThatThrownBy(() -> AiRetry.withBackoff(0, 1000, 60_000, sleeperFalso, call, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiRetry.withBackoff(2, -1, 60_000, sleeperFalso, call, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 0, sleeperFalso, call, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, -1, sleeperFalso, call, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 60_000, null, call, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AiRetry.withBackoff(2, 1000, 60_000, sleeperFalso, null, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(calls).hasValue(0);
    }

    private static AiProviderErrorMapper.HttpFailure unavailable(Long retryAfterMillis) {
        return new AiProviderErrorMapper.HttpFailure(503, retryAfterMillis, false);
    }
}
