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

    public static String indexingMessage(Throwable exception) {
        if (isQuotaExceeded(exception)) {
            return "Se alcanzó la cuota de IA para procesar documentos. "
                    + "Intentá nuevamente más tarde";
        }

        return "El archivo se guardó, pero no se pudo indexar";
    }
}
