package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiProviderErrorMapperTests {

    @Test
    void recognizesQuotaErrorsInNestedCauses() {
        RuntimeException exception = new RuntimeException(
                "Falló la indexación",
                new RuntimeException("429 You exceeded your current quota")
        );

        assertThat(AiProviderErrorMapper.isQuotaExceeded(exception)).isTrue();
        assertThat(AiProviderErrorMapper.indexingMessage(exception))
                .contains("cuota de IA")
                .contains("más tarde");
    }

    @Test
    void usesGenericMessageForOtherProviderErrors() {
        RuntimeException exception =
                new RuntimeException("Connection refused");

        assertThat(AiProviderErrorMapper.isQuotaExceeded(exception)).isFalse();
        assertThat(AiProviderErrorMapper.indexingMessage(exception))
                .isEqualTo(
                        "El archivo se guardó, pero no se pudo indexar"
                );
    }
}
