package com.studina.dinaapi.ai.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.ai.llm.LlmProperties;

/** Providers offered by this deployment, independently of the development catalog. */
@Component
public class AiProviderAvailability {

    private static final Set<String> SUPPORTED = Set.of("google-genai", "ollama", "openai");
    // Models exercised successfully with Studina's native schemas in the deployed application.
    // A successful integration does not guarantee a project's quota or provider uptime.
    private static final List<ModelOption> GEMINI_MODELS = List.of(
            new ModelOption("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
            new ModelOption("gemini-3.5-flash", "Gemini 3.5 Flash"),
            new ModelOption("gemini-3.6-flash", "Gemini 3.6 Flash"));

    public record ModelOption(String value, String label) {}
    private final List<String> providers;

    public AiProviderAvailability(
            @Value("${ai.allowed-providers:google-genai,ollama,openai}") String configuredProviders,
            LlmProperties defaults
    ) {
        providers = Arrays.stream(configuredProviders.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (providers.isEmpty() || !SUPPORTED.containsAll(providers)) {
            throw new IllegalArgumentException("AI_ALLOWED_PROVIDERS debe incluir proveedores válidos: " + SUPPORTED);
        }
        if (!isAvailable(defaults.getProvider())) {
            throw new IllegalArgumentException("AI_ALLOWED_PROVIDERS debe incluir el proveedor general: "
                    + defaults.getProvider());
        }
    }

    public List<String> providers() {
        return providers;
    }

    public boolean isAvailable(String provider) {
        return providers.contains(provider);
    }

    public static void requireModelAvailable(String provider, String model) {
        if ("google-genai".equals(provider) && model != null
                && GEMINI_MODELS.stream().noneMatch(option -> option.value().equals(
                        model.trim().replaceFirst("^models/", "")))) {
            throw new ModelUnavailableException();
        }
    }

    public Map<String, List<ModelOption>> availableModels() {
        return isAvailable("google-genai") ? Map.of("google-genai", GEMINI_MODELS) : Map.of();
    }

    public static final class ModelUnavailableException extends IllegalArgumentException {
        public ModelUnavailableException() {
            super("El modelo Gemini configurado no está habilitado en Studina. "
                    + "Elegí otro modelo en Mi perfil y guardá la configuración.");
        }
    }

    public void requireAvailable(String provider) {
        if (!isAvailable(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El proveedor " + provider + " no está disponible en este servidor. "
                            + "Elegí otro proveedor o usá la configuración general en Mi perfil.");
        }
    }
}
