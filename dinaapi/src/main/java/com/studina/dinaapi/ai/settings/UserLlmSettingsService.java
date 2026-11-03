package com.studina.dinaapi.ai.settings;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.ai.llm.LlmProperties;
import com.studina.dinaapi.ai.llm.LlmUseCase;

@Service
public class UserLlmSettingsService {

    private static final Set<String> SUPPORTED_PROVIDERS =
            Set.of("google-genai", "ollama", "openai");
    private static final Set<String> CREDENTIAL_PROVIDERS =
            Set.of("google-genai", "openai");
    private static final Pattern MODEL_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");

    private final UserLlmSettingsRepository repository;
    private final CredentialCipher credentialCipher;
    private final LlmProperties defaults;

    public UserLlmSettingsService(
            UserLlmSettingsRepository repository,
            CredentialCipher credentialCipher,
            LlmProperties defaults
    ) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.defaults = defaults;
    }

    public UserLlmSettingsView current(String userId) {
        return repository.findByUserId(userId)
                .map(this::toView)
                .orElseGet(this::defaultView);
    }

    public Optional<UserLlmRuntimeSettings> runtimeFor(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByUserId(userId).map(settings ->
                new UserLlmRuntimeSettings(
                        settings.provider(),
                        settings.chatModel(),
                        settings.generationModel(),
                        settings.classificationModel(),
                        settings.summaryModel(),
                        credentialCipher.decrypt(settings.encryptedApiKey())));
    }

    public UserLlmSettingsView update(
            String userId,
            UpdateUserLlmSettingsRequest request
    ) {
        if (request == null) {
            throw badRequest("La configuración es obligatoria");
        }

        String provider = normalizeProvider(request.provider());
        String chatModel = normalizeRequiredModel(request.chatModel(), "chat");
        String generationModel = normalizeOptionalModel(
                request.generationModel(), "generación");
        String classificationModel = normalizeOptionalModel(
                request.classificationModel(), "clasificación");
        String summaryModel = normalizeOptionalModel(request.summaryModel(), "resumen");

        Optional<StoredUserLlmSettings> current = repository.findByUserId(userId);
        String encryptedApiKey = resolveEncryptedCredential(
                provider, request.apiKey(), current);

        StoredUserLlmSettings saved = repository.save(new StoredUserLlmSettings(
                current.map(StoredUserLlmSettings::id).orElse(null),
                userId,
                provider,
                chatModel,
                generationModel,
                classificationModel,
                summaryModel,
                encryptedApiKey));
        return toView(saved);
    }

    public UserLlmSettingsView reset(String userId) {
        repository.deleteByUserId(userId);
        return defaultView();
    }

    private String resolveEncryptedCredential(
            String provider,
            String newApiKey,
            Optional<StoredUserLlmSettings> current
    ) {
        if (!CREDENTIAL_PROVIDERS.contains(provider)) {
            return null;
        }
        if (newApiKey != null && !newApiKey.isBlank()) {
            if (!credentialCipher.isConfigured()) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "El servidor no configuró el cifrado de credenciales");
            }
            return credentialCipher.encrypt(newApiKey.trim());
        }

        if (current.isPresent()
                && provider.equals(current.get().provider())
                && current.get().encryptedApiKey() != null
                && !current.get().encryptedApiKey().isBlank()) {
            return current.get().encryptedApiKey();
        }
        throw badRequest("Tenés que ingresar una API key para " + provider);
    }

    private UserLlmSettingsView toView(StoredUserLlmSettings settings) {
        return new UserLlmSettingsView(
                "user",
                settings.provider(),
                settings.chatModel(),
                fallback(settings.generationModel(), settings.chatModel()),
                fallback(settings.classificationModel(), settings.chatModel()),
                fallback(settings.summaryModel(), settings.chatModel()),
                settings.encryptedApiKey() != null
                        && !settings.encryptedApiKey().isBlank());
    }

    private UserLlmSettingsView defaultView() {
        return new UserLlmSettingsView(
                "server",
                defaults.getProvider(),
                defaults.modelFor(LlmUseCase.CHAT),
                defaults.modelFor(LlmUseCase.GENERATION),
                defaults.modelFor(LlmUseCase.CLASSIFICATION),
                defaults.modelFor(LlmUseCase.SUMMARY),
                false);
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider == null
                ? ""
                : provider.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw badRequest("Proveedor no soportado: " + provider);
        }
        return normalized;
    }

    private static String normalizeRequiredModel(String model, String label) {
        String normalized = normalizeOptionalModel(model, label);
        if (normalized == null) {
            throw badRequest("El modelo de " + label + " es obligatorio");
        }
        return normalized;
    }

    private static String normalizeOptionalModel(String model, String label) {
        if (model == null || model.isBlank()) {
            return null;
        }
        String normalized = model.trim();
        if (!MODEL_NAME.matcher(normalized).matches()) {
            throw badRequest("El modelo de " + label + " tiene un formato inválido");
        }
        return normalized;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
