package com.studina.dinaapi.ai.rag;

public final class AiProviderErrorMapper {

    private AiProviderErrorMapper() {
    }

    public static boolean isQuotaExceeded(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && (message.contains("429")
                    || message.toLowerCase().contains("quota"))) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }

    public static boolean isAuthenticationFailure(Throwable exception) {
        return containsAny(exception,
                "401", "403", "unauthorized", "invalid api key",
                "incorrect api key", "authentication");
    }

    public static boolean isModelNotFound(Throwable exception) {
        return containsAny(exception,
                "404", "not found", "model_not_found", "unknown model");
    }

    public static String chatMessage(Throwable exception) {
        if (isAuthenticationFailure(exception)) {
            return "El proveedor rechazó la API key. Revisala en Mi perfil";
        }
        if (isModelNotFound(exception)) {
            return "El proveedor no encontró el modelo configurado. "
                    + "Revisá el nombre del modelo en Mi perfil";
        }
        if (isQuotaExceeded(exception)) {
            return "Se alcanzó la cuota del proveedor de IA. "
                    + "Intentá nuevamente más tarde";
        }
        if (isTransient(exception)) {
            return "El proveedor de IA está temporalmente ocupado. "
                    + "Intentá nuevamente en unos segundos";
        }
        return "No se pudo obtener una respuesta del proveedor de IA";
    }

    /**
     * Fallos del proveedor que se resuelven solos: el modelo está saturado o momentáneamente
     * caído. Vale reintentar en segundos.
     *
     * <p>La cuota agotada queda deliberadamente afuera: un 429 no se arregla esperando un par
     * de segundos, así que reintentar sólo agrega latencia y más llamadas contra el límite.
     */
    public static boolean isTransient(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("503")
                        || lower.contains("overloaded")
                        || lower.contains("unavailable")
                        || lower.contains("high demand")
                        || lower.contains("try again later")) {
                    return !isQuotaExceeded(exception);
                }
            }
            current = current.getCause();
        }

        return false;
    }

    public static String indexingMessage(Throwable exception) {
        if (isQuotaExceeded(exception)) {
            return "Se alcanzó la cuota de IA para procesar documentos. "
                    + "Intentá nuevamente más tarde";
        }

        return "El archivo se guardó, pero no se pudo indexar";
    }

    private static boolean containsAny(Throwable exception, String... fragments) {
        Throwable current = exception;

        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                for (String fragment : fragments) {
                    if (lower.contains(fragment)) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }

        return false;
    }
}
