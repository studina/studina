package com.studina.dinaapi.ai.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class CredentialCipherTests {

    private static final String TEST_KEY = Base64.getEncoder()
            .encodeToString(new byte[32]);

    @Test
    void encryptsWithARandomIvAndDecryptsTheOriginalValue() {
        CredentialCipher cipher = new CredentialCipher(TEST_KEY);

        String first = cipher.encrypt("secret-key");
        String second = cipher.encrypt("secret-key");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(first).doesNotContain("secret-key");
        assertThat(cipher.decrypt(first)).isEqualTo("secret-key");
        assertThat(cipher.decrypt(second)).isEqualTo("secret-key");
    }

    @Test
    void allowsTheApplicationToStartWithoutAKeyButRejectsEncryption() {
        CredentialCipher cipher = new CredentialCipher("");

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("secret-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_CREDENTIALS_ENCRYPTION_KEY");
    }

    @Test
    void rejectsKeysThatAreNotExactly256Bits() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new CredentialCipher(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
