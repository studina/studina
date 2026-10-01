package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

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
}
