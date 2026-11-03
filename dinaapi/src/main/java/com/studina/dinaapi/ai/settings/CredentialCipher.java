package com.studina.dinaapi.ai.settings;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CredentialCipher {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialCipher(
            @Value("${ai.credentials.encryption-key:}") String encodedKey
    ) {
        this.encryptionKey = decodeKey(encodedKey);
    }

    public boolean isConfigured() {
        return encryptionKey != null;
    }

    public String encrypt(String plainText) {
        requireConfigured();
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("La credencial no puede estar vacía");
        }

        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(
                    plainText.getBytes(StandardCharsets.UTF_8));

            return VERSION + ":"
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo cifrar la credencial", exception);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return null;
        }
        requireConfigured();

        String[] parts = storedValue.split(":", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("Formato de credencial cifrada inválido");
        }

        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("No se pudo descifrar la credencial", exception);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "AI_CREDENTIALS_ENCRYPTION_KEY no está configurada");
        }
    }

    private static byte[] decodeKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException(
                        "AI_CREDENTIALS_ENCRYPTION_KEY debe representar 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "AI_CREDENTIALS_ENCRYPTION_KEY debe ser Base64 válido de 32 bytes",
                    exception);
        }
    }
}
