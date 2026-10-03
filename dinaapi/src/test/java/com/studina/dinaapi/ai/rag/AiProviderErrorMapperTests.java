package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Qué se reintenta y qué no.
 *
 * <p>Un 503 por sobrecarga del modelo dejaba un archivo sin temas para siempre: la subida
 * respondía como exitosa y sólo se recuperaba borrando el archivo y volviéndolo a subir.
 * La cuota agotada, en cambio, no se arregla esperando: reintentarla sólo suma latencia
 * y más llamadas contra el límite.
 */
class AiProviderErrorMapperTests {

    @Test
    void treatsAnOverloadedModelAsWorthRetrying() {
        // El mensaje real que devolvió Gemini cuando falló la clasificación.
        Throwable error = new RuntimeException(
                "503 . This model is currently experiencing high demand. "
                        + "Spikes in demand are usually temporary. Please try again later.");

        assertThat(AiProviderErrorMapper.isTransient(error)).isTrue();
    }

    @Test
    void findsTheCauseEvenWhenItComesWrapped() {
        Throwable error = new IllegalStateException("Falló la clasificación",
                new RuntimeException("The service is currently unavailable"));

        assertThat(AiProviderErrorMapper.isTransient(error)).isTrue();
    }

    @Test
    void doesNotRetryAnExhaustedQuota() {
        Throwable error = new RuntimeException("429 Quota exceeded. Please try again later.");

        assertThat(AiProviderErrorMapper.isQuotaExceeded(error)).isTrue();
        assertThat(AiProviderErrorMapper.isTransient(error))
                .as("esperar un par de segundos no devuelve cuota")
                .isFalse();
    }

    @Test
    void doesNotRetryWhatWillFailAgainTheSameWay() {
        assertThat(AiProviderErrorMapper.isTransient(
                new RuntimeException("400 Invalid request: missing field"))).isFalse();
        assertThat(AiProviderErrorMapper.isTransient(
                new RuntimeException("401 Unauthorized"))).isFalse();
        assertThat(AiProviderErrorMapper.isTransient(new RuntimeException())).isFalse();
    }

    @Test
    void explainsAnUnknownModelWithoutExposingTheProviderException() {
        Throwable error = new RuntimeException("Request failed",
                new RuntimeException("404: Unknown"));

        assertThat(AiProviderErrorMapper.isModelNotFound(error)).isTrue();
        assertThat(AiProviderErrorMapper.chatMessage(error))
                .isEqualTo("El proveedor no encontró el modelo configurado. "
                        + "Revisá el nombre del modelo en Mi perfil");
    }

    @Test
    void explainsRejectedCredentials() {
        Throwable error = new RuntimeException("401 Unauthorized: incorrect API key");

        assertThat(AiProviderErrorMapper.isAuthenticationFailure(error)).isTrue();
        assertThat(AiProviderErrorMapper.chatMessage(error))
                .isEqualTo("El proveedor rechazó la API key. Revisala en Mi perfil");
    }
}
