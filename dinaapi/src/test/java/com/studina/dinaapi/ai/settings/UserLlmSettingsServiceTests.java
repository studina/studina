package com.studina.dinaapi.ai.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.ai.llm.LlmProperties;
import com.studina.dinaapi.ai.llm.LlmUseCase;

class UserLlmSettingsServiceTests {

    private static final String TEST_KEY = Base64.getEncoder()
            .encodeToString(new byte[32]);

    @Test
    void returnsServerDefaultsWhenTheUserHasNoOverride() {
        InMemoryRepository repository = new InMemoryRepository();
        LlmProperties defaults = new LlmProperties();
        defaults.setProvider("ollama");
        defaults.setModel("llama-default");
        UserLlmSettingsService service = service(repository, defaults);

        UserLlmSettingsView view = service.current("user-a");

        assertThat(view.source()).isEqualTo("server");
        assertThat(view.provider()).isEqualTo("ollama");
        assertThat(view.chatModel()).isEqualTo("llama-default");
        assertThat(view.credentialConfigured()).isFalse();
    }

    @Test
    void encryptsUserCredentialsAndNeverReturnsThemInThePublicView() {
        InMemoryRepository repository = new InMemoryRepository();
        UserLlmSettingsService service = service(repository, new LlmProperties());

        UserLlmSettingsView view = service.update("user-a", new UpdateUserLlmSettingsRequest(
                "google-genai",
                "gemini-chat",
                "gemini-generation",
                "",
                null,
                "private-api-key"));

        assertThat(view.source()).isEqualTo("user");
        assertThat(view.credentialConfigured()).isTrue();
        assertThat(view.classificationModel()).isEqualTo("gemini-chat");
        assertThat(repository.settings.encryptedApiKey())
                .startsWith("v1:")
                .doesNotContain("private-api-key");
        assertThat(service.runtimeFor("user-a")).hasValueSatisfying(runtime -> {
            assertThat(runtime.apiKey()).isEqualTo("private-api-key");
            assertThat(runtime.modelFor(LlmUseCase.GENERATION))
                    .isEqualTo("gemini-generation");
            assertThat(runtime.modelFor(LlmUseCase.SUMMARY))
                    .isEqualTo("gemini-chat");
        });
    }

    @Test
    void preservesTheCredentialWhenOnlyModelsChange() {
        InMemoryRepository repository = new InMemoryRepository();
        UserLlmSettingsService service = service(repository, new LlmProperties());
        service.update("user-a", new UpdateUserLlmSettingsRequest(
                "openai", "first-model", null, null, null, "secret"));
        String encrypted = repository.settings.encryptedApiKey();

        service.update("user-a", new UpdateUserLlmSettingsRequest(
                "openai", "second-model", null, null, null, ""));

        assertThat(repository.settings.encryptedApiKey()).isEqualTo(encrypted);
        assertThat(service.runtimeFor("user-a").orElseThrow().apiKey())
                .isEqualTo("secret");
    }

    @Test
    void requiresANewCredentialWhenSwitchingHostedProviders() {
        InMemoryRepository repository = new InMemoryRepository();
        UserLlmSettingsService service = service(repository, new LlmProperties());
        service.update("user-a", new UpdateUserLlmSettingsRequest(
                "google-genai", "gemini", null, null, null, "gemini-key"));

        assertThatThrownBy(() -> service.update(
                "user-a",
                new UpdateUserLlmSettingsRequest(
                        "openai", "gpt-model", null, null, null, "")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void ollamaDoesNotPersistCredentialsAndResetRestoresDefaults() {
        InMemoryRepository repository = new InMemoryRepository();
        LlmProperties defaults = new LlmProperties();
        UserLlmSettingsService service = service(repository, defaults);

        UserLlmSettingsView saved = service.update(
                "user-a",
                new UpdateUserLlmSettingsRequest(
                        "ollama", "llama3.2", null, null, null, "ignored"));
        UserLlmSettingsView reset = service.reset("user-a");

        assertThat(saved.credentialConfigured()).isFalse();
        assertThat(repository.settings).isNull();
        assertThat(reset.source()).isEqualTo("server");
    }

    private static UserLlmSettingsService service(
            InMemoryRepository repository,
            LlmProperties defaults
    ) {
        return new UserLlmSettingsService(
                repository, new CredentialCipher(TEST_KEY), defaults);
    }

    private static final class InMemoryRepository
            implements UserLlmSettingsRepository {

        private StoredUserLlmSettings settings;

        @Override
        public Optional<StoredUserLlmSettings> findByUserId(String userId) {
            return settings == null || !userId.equals(settings.userId())
                    ? Optional.empty()
                    : Optional.of(settings);
        }

        @Override
        public StoredUserLlmSettings save(StoredUserLlmSettings value) {
            settings = new StoredUserLlmSettings(
                    value.id() == null ? "settings-a" : value.id(),
                    value.userId(),
                    value.provider(),
                    value.chatModel(),
                    value.generationModel(),
                    value.classificationModel(),
                    value.summaryModel(),
                    value.encryptedApiKey());
            return settings;
        }

        @Override
        public void deleteByUserId(String userId) {
            if (settings != null && userId.equals(settings.userId())) {
                settings = null;
            }
        }
    }
}
