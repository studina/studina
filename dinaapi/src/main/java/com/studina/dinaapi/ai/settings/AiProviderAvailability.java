package com.studina.dinaapi.ai.settings;

import java.util.Arrays;
import java.util.List;
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
                && "gemini-3.8-flash".equals(model.trim().replaceFirst("^models/", ""))) {
            throw new ModelUnavailableException();
        }
    }

    public static final class ModelUnavailableException extends IllegalArgumentException {
        public ModelUnavailableException() {
            super("Gemini 3.8 Flash se retiró de las opciones de Studina. "
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
