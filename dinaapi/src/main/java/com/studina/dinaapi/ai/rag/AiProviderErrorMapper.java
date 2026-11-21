package com.studina.dinaapi.ai.rag;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Provider failure classification without exposing upstream messages. */
public final class AiProviderErrorMapper {

    public enum Kind {
        QUOTA, RATE_LIMIT, SERVICE_UNAVAILABLE, TIMEOUT, CONNECTIVITY,
        AUTHENTICATION, MODEL_NOT_FOUND, UNKNOWN
    }

    /** Retains HTTP metadata when an SDK would otherwise discard response headers. */
    public static final class HttpFailure extends RuntimeException {
        private final int status;
        private final Long retryAfterMillis;
        private final boolean quota;
        private final String providerCode;

        public HttpFailure(int status, Long retryAfterMillis, boolean quota) {
            this(status, retryAfterMillis, quota, "unknown");
        }

        public HttpFailure(int status, Long retryAfterMillis, boolean quota, String providerCode) {
            super("El proveedor de IA rechazó la solicitud");
            this.status = status;
            this.retryAfterMillis = retryAfterMillis != null && retryAfterMillis >= 0 ? retryAfterMillis : null;
            this.quota = quota;
            this.providerCode = safeProviderCode(providerCode);
        }

        public int status() { return status; }
        public Long retryAfterMillis() { return retryAfterMillis; }
        public boolean quota() { return quota; }
        public String providerCode() { return providerCode; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SAFE_PROVIDER_CODES = Set.of(
            "INVALID_ARGUMENT", "UNAVAILABLE", "RESOURCE_EXHAUSTED", "PERMISSION_DENIED",
            "UNAUTHENTICATED", "NOT_FOUND", "INTERNAL", "DEADLINE_EXCEEDED", "FAILED_PRECONDITION",
            "CANCELLED", "UNKNOWN", "OUT_OF_RANGE", "UNIMPLEMENTED", "ABORTED", "DATA_LOSS",
            "insufficient_quota", "rate_limit_exceeded", "invalid_api_key", "model_not_found",
            "context_length_exceeded", "invalid_request_error", "server_error", "permission_denied");

    /** Fixed fields only: never exception messages, provider bodies, headers or credentials. */
    public record Diagnostic(Kind kind, Integer upstreamStatus, String providerCode,
            Long retryAfterMs, String rootCauseClass) {}

    public static Diagnostic diagnostic(Throwable exception) {
        List<Throwable> chain = causes(exception);
        Integer status = null;
        String code = "unknown";
        for (Throwable cause : chain) {
            if (cause instanceof HttpFailure failure) {
                status = failure.status();
                code = failure.providerCode();
            } else if (cause instanceof ApiException failure) {
                status = failure.code();
                code = safeProviderCode(failure.status());
            } else if (cause instanceof OpenAIServiceException failure) {
                status = failure.statusCode();
                try { code = safeProviderCode(failure.code().orElse("unknown")); }
                catch (OpenAIException invalidErrorData) { code = "unknown"; }
            } else if (cause instanceof RestClientResponseException failure) {
                status = failure.getStatusCode().value();
                code = providerCodeFromBody(failure.getResponseBodyAsString());
            }
            if (status != null) break;
        }
        if (status != null && (status < 100 || status > 599)) status = null;
        var retryAfter = retryAfterMillis(exception);
        return new Diagnostic(kind(exception), status, code,
                retryAfter.isPresent() ? retryAfter.getAsLong() : null,
                chain.isEmpty() ? "unknown" : chain.getLast().getClass().getSimpleName());
    }

    /** Reads only an allowlisted machine code, not arbitrary upstream messages or identifiers. */
    public static String providerCodeFromBody(String body) {
        if (body == null || body.length() > 16 * 1024) return "unknown";
        try {
            var root = JSON.readTree(body);
            if (root == null) return "unknown";
            var error = root.path("error");
            String status = safeProviderCode(error.path("status").asText());
            return !"unknown".equals(status) ? status : safeProviderCode(error.path("code").asText());
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return "unknown";
        }
    }

    private static String safeProviderCode(String code) {
        return code != null && SAFE_PROVIDER_CODES.contains(code) ? code : "unknown";
    }

    private static final Pattern LEADING_STATUS = Pattern.compile(
            "^(?:(?:http(?:/[0-9.]+)?|status(?: code)?|response status(?: code)?)\\s*[:=]?\\s*)?"
                    + "(400|401|403|404|408|429|500|502|503|504)(?=$|[\\s:.,-])",
            Pattern.CASE_INSENSITIVE);

    private AiProviderErrorMapper() {
    }

    public static Kind kind(Throwable exception) {
        List<Throwable> chain = causes(exception);
        // Typed HTTP metadata wins over wrapper messages, including validation wrappers.
        for (Throwable current : chain) {
            if (current instanceof HttpFailure failure) {
                return fromStatus(failure.status(), failure.quota());
            }
            if (current instanceof ApiException failure) {
                return fromStatus(failure.code(), isLongTermQuota(failure.message()));
            }
            if (current instanceof OpenAIServiceException failure) {
                return fromStatus(failure.statusCode(), openAiQuota(failure));
            }
            if (current instanceof RestClientResponseException failure) {
                return fromStatus(failure.getStatusCode().value(), isLongTermQuota(failure.getResponseBodyAsString()));
            }
        }
        for (Throwable current : chain) {
            if (isTimeout(current)) {
                return Kind.TIMEOUT;
            }
        }
        for (Throwable current : chain) {
            if (current instanceof ConnectException || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException || current instanceof ResourceAccessException
                    || current instanceof OpenAIIoException || current instanceof GenAiIOException) {
                return Kind.CONNECTIVITY;
            }
        }
        // Validation and JSON errors may contain arbitrary private document text.
        if (chain.stream().anyMatch(current -> current instanceof IllegalArgumentException
                || current instanceof JsonProcessingException)) {
            return Kind.UNKNOWN;
        }
        for (Throwable current : chain) {
            Kind legacy = legacyKind(current.getMessage());
            if (legacy != Kind.UNKNOWN) {
                return legacy;
            }
        }
        return Kind.UNKNOWN;
    }

    /** Text resembling an HTTP status alone does not qualify as a provider failure. */
    public static boolean isProviderFailure(Throwable exception) {
        return causes(exception).stream().anyMatch(current -> current instanceof HttpFailure
                || current instanceof ApiException || current instanceof GenAiIOException || current instanceof OpenAIException
                || current instanceof RestClientResponseException || current instanceof ResourceAccessException
                || isTimeout(current) || current instanceof ConnectException
                || current instanceof UnknownHostException || current instanceof NoRouteToHostException);
    }

    public static HttpStatus httpStatus(Throwable exception) {
        return switch (kind(exception)) {
            case QUOTA, RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AUTHENTICATION, MODEL_NOT_FOUND -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    public static boolean isLimitReached(Throwable exception) {
        Kind kind = kind(exception);
        return kind == Kind.QUOTA || kind == Kind.RATE_LIMIT;
    }

    public static boolean isQuotaExceeded(Throwable exception) {
        // Historical callers used this name for every 429, not just long-term quota exhaustion.
        return isLimitReached(exception) || causes(exception).stream().map(Throwable::getMessage)
                .filter(java.util.Objects::nonNull).map(message -> message.toLowerCase(Locale.ROOT))
                .anyMatch(message -> message.contains("429") || message.contains("quota"));
    }

    public static boolean isAuthenticationFailure(Throwable exception) {
        return kind(exception) == Kind.AUTHENTICATION;
    }

    public static boolean isModelNotFound(Throwable exception) {
        return kind(exception) == Kind.MODEL_NOT_FOUND;
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

    /** Detailed provider diagnostics are confined to the resource-generation endpoints. */
    public static String generationMessage(Throwable exception) {
        return switch (kind(exception)) {
            case AUTHENTICATION -> "El proveedor rechazó la API key. Revisala en Mi perfil";
            case MODEL_NOT_FOUND -> "El proveedor no encontró el modelo configurado. "
                    + "Revisá el nombre del modelo en Mi perfil";
            case QUOTA -> "Se alcanzó la cuota del proveedor de IA. "
                    + "Revisá la cuota de tu cuenta o esperá a que se restablezca";
            case RATE_LIMIT -> "Se alcanzó el límite de solicitudes del proveedor de IA. "
                    + "Intentá nuevamente más tarde";
            case SERVICE_UNAVAILABLE -> "El proveedor de IA está temporalmente ocupado. "
                    + "Intentá nuevamente más tarde";
            case TIMEOUT -> "El proveedor de IA tardó demasiado en responder. Intentá nuevamente más tarde";
            case CONNECTIVITY -> "No se pudo conectar con el proveedor de IA. Intentá nuevamente más tarde";
            case UNKNOWN -> "No se pudo obtener una respuesta del proveedor de IA";
        };
    }

    /** Legacy overload detection used by classification and evaluation, without a new deadline policy. */
    public static boolean isTransient(Throwable exception) {
        Kind failure = kind(exception);
        if (failure != Kind.UNKNOWN || isProviderFailure(exception)) {
            return failure == Kind.SERVICE_UNAVAILABLE;
        }
        List<Throwable> chain = causes(exception);
        if (chain.stream().anyMatch(current -> current instanceof IllegalArgumentException
                || current instanceof JsonProcessingException)) {
            return false;
        }
        // Preserve the pre-existing textual overload signals for callers that own their retries.
        boolean quota = chain.stream().map(Throwable::getMessage).filter(java.util.Objects::nonNull)
                .map(message -> message.toLowerCase(Locale.ROOT))
                .anyMatch(message -> message.contains("429") || message.contains("quota"));
        return !quota && chain.stream().map(Throwable::getMessage).filter(java.util.Objects::nonNull)
                .map(message -> message.toLowerCase(Locale.ROOT))
                .anyMatch(message -> message.contains("503") || message.contains("overloaded")
                        || message.contains("unavailable") || message.contains("high demand")
                        || message.contains("try again later"));
    }

    public static String indexingMessage(Throwable exception) {
        if (isQuotaExceeded(exception)) {
            return "Se alcanzó la cuota de IA para procesar documentos. Intentá nuevamente más tarde";
        }
        return "El archivo se guardó, pero no se pudo indexar";
    }

    public static OptionalLong retryAfterMillis(Throwable exception) {
        Instant now = Instant.now();
        OptionalLong result = OptionalLong.empty();
        for (Throwable current : causes(exception)) {
            if (current instanceof HttpFailure failure && failure.retryAfterMillis() != null) {
                result = maximum(result, OptionalLong.of(failure.retryAfterMillis()));
            } else if (current instanceof RestClientResponseException failure) {
                HttpHeaders headers = failure.getResponseHeaders();
                if (headers != null) {
                    result = maximum(result, headerDelay(headers.get(HttpHeaders.RETRY_AFTER), now));
                    List<String> milliseconds = headers.get("Retry-After-Ms");
                    if (milliseconds != null) {
                        for (String value : milliseconds) {
                            result = maximum(result, parseRetryAfterMillisHeader(value));
                        }
                    }
                }
            } else if (current instanceof OpenAIServiceException failure) {
                result = maximum(result, headerDelay(failure.headers().values("Retry-After"), now));
                for (String value : failure.headers().values("Retry-After-Ms")) {
                    result = maximum(result, parseRetryAfterMillisHeader(value));
                }
            }
        }
        return result;
    }

    /** OpenAI also supports this non-standard millisecond retry hint. */
    public static OptionalLong parseRetryAfterMillisHeader(String value) {
        if (value == null || !value.trim().matches("[0-9]+")) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException tooLarge) {
            return OptionalLong.of(Long.MAX_VALUE);
        }
    }

    /** Parses Retry-After without shortening long waits, rounding fractional milliseconds up. */
    public static OptionalLong parseRetryAfter(String value, Instant now) {
        if (value == null || now == null) {
            return OptionalLong.empty();
        }
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]+")) {
            try {
                return OptionalLong.of(Math.multiplyExact(Long.parseLong(trimmed), 1000L));
            } catch (NumberFormatException | ArithmeticException tooLarge) {
                return OptionalLong.of(Long.MAX_VALUE);
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(now, retryAt);
            if (delay.isNegative() || delay.isZero()) {
                return OptionalLong.of(0);
            }
            long wholeMillis = Math.multiplyExact(delay.getSeconds(), 1000L);
            long remainingMillis = (delay.getNano() + 999_999L) / 1_000_000L;
            return OptionalLong.of(Math.addExact(wholeMillis, remainingMillis));
        } catch (ArithmeticException tooLarge) {
            return OptionalLong.of(Long.MAX_VALUE);
        } catch (DateTimeException invalid) {
            return OptionalLong.empty();
        }
    }

    /** Explicit long-term quota evidence; a generic quota error can also mean RPM/TPM. */
    public static boolean isLongTermQuota(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.substring(0, Math.min(message.length(), 65_536)).toLowerCase(Locale.ROOT);
        return lower.contains("insufficient_quota") || lower.contains("billing_hard_limit")
                || lower.contains("billing_limit") || lower.contains("billing limit")
                || lower.contains("perday") || lower.contains("per_day") || lower.contains("per day")
                || lower.contains("daily quota") || lower.contains("daily limit");
    }

    private static boolean isTimeout(Throwable exception) {
        return exception instanceof SocketTimeoutException || exception instanceof HttpTimeoutException
                || exception instanceof InterruptedIOException && "timeout".equalsIgnoreCase(exception.getMessage());
    }

    private static Kind fromStatus(int status, boolean quota) {
        return switch (status) {
            case 429 -> quota ? Kind.QUOTA : Kind.RATE_LIMIT;
            case 503 -> Kind.SERVICE_UNAVAILABLE;
            case 408, 504 -> Kind.TIMEOUT;
            case 401, 403 -> Kind.AUTHENTICATION;
            case 404 -> Kind.MODEL_NOT_FOUND;
            default -> Kind.UNKNOWN;
        };
    }

    private static Kind legacyKind(String message) {
        if (message == null) {
            return Kind.UNKNOWN;
        }
        String lower = message.trim().toLowerCase(Locale.ROOT);
        Matcher status = LEADING_STATUS.matcher(lower);
        if (status.find()) {
            return fromStatus(Integer.parseInt(status.group(1)), isLongTermQuota(lower));
        }
        if (lower.startsWith("the service is currently unavailable")
                || lower.startsWith("service unavailable") || lower.startsWith("model overloaded")
                || lower.startsWith("the model is overloaded")
                || lower.startsWith("this model is currently experiencing high demand")) {
            return Kind.SERVICE_UNAVAILABLE;
        }
        if (lower.startsWith("unauthorized") || lower.startsWith("invalid api key")
                || lower.startsWith("incorrect api key")) {
            return Kind.AUTHENTICATION;
        }
        if (lower.startsWith("model_not_found") || lower.startsWith("unknown model")) {
            return Kind.MODEL_NOT_FOUND;
        }
        return Kind.UNKNOWN;
    }

    private static boolean openAiQuota(OpenAIServiceException failure) {
        try {
            return isLongTermQuota(failure.code().orElse("")) || isLongTermQuota(failure.type().orElse(""))
                    || isLongTermQuota(failure.getMessage());
        } catch (OpenAIException invalidErrorData) {
            return isLongTermQuota(failure.getMessage());
        }
    }

    private static OptionalLong headerDelay(List<String> values, Instant now) {
        OptionalLong result = OptionalLong.empty();
        if (values != null) {
            for (String value : values) {
                result = maximum(result, parseRetryAfter(value, now));
            }
        }
        return result;
    }

    private static OptionalLong maximum(OptionalLong left, OptionalLong right) {
        if (left.isEmpty()) {
            return right;
        }
        return right.isPresent() ? OptionalLong.of(Math.max(left.getAsLong(), right.getAsLong())) : left;
    }

    private static List<Throwable> causes(Throwable exception) {
        List<Throwable> result = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception;
        while (current != null && result.size() < 64 && visited.add(current)) {
            result.add(current);
            current = current.getCause();
        }
        return result;
    }
}
