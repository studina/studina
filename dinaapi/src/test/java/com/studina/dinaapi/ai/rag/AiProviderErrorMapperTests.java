package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.genai.errors.ApiException;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Qué se reintenta y qué no.
 *
 * <p>Un 503 por sobrecarga del modelo dejaba un archivo sin temas para siempre: la subida
 * respondía como exitosa y sólo se recuperaba borrando el archivo y volviéndolo a subir.
 * La cuota agotada, en cambio, no se arregla esperando: reintentarla sólo suma latencia
 * y más llamadas contra el límite.
 */
class AiProviderErrorMapperTests {

    @Test
    void exposesOnlyTypedStatusAndAllowlistedCodesInDiagnostics() {
        var upstream = new AiProviderErrorMapper.HttpFailure(503, 2000L, false, "UNAVAILABLE");
        upstream.initCause(new IllegalStateException("private upstream body and API key"));
        var failure = AiProviderErrorMapper.diagnostic(new RuntimeException("private wrapper", upstream));
        assertThat(failure.kind()).isEqualTo(AiProviderErrorMapper.Kind.SERVICE_UNAVAILABLE);
        assertThat(failure.upstreamStatus()).isEqualTo(503);
        assertThat(failure.providerCode()).isEqualTo("UNAVAILABLE");
        assertThat(failure.retryAfterMs()).isEqualTo(2000L);
        assertThat(failure.rootCauseClass()).isEqualTo("IllegalStateException");
        assertThat(failure.toString()).doesNotContain("private", "API key");

        var google = AiProviderErrorMapper.diagnostic(new ApiException(400, "INVALID_ARGUMENT", "private document"));
        assertThat(google.upstreamStatus()).isEqualTo(400);
        assertThat(google.providerCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(google.toString()).doesNotContain("private document");
    }

    @Test
    void doesNotInventAnHttpStatusFromAnExceptionMessage() {
        var failure = AiProviderErrorMapper.diagnostic(new IllegalArgumentException("private document says 503"));
        assertThat(failure.upstreamStatus()).isNull();
        assertThat(failure.providerCode()).isEqualTo("unknown");
        assertThat(failure.toString()).doesNotContain("private document");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "not JSON", "{}", "[]",
            "{\"error\":{\"code\":\"private-api-key\",\"status\":\"private document\"}}",
            "{\"error\":{\"status\":\"UNAVAILABLE\\nprivate document\"}}"})
    void discardsPrivateOrMalformedProviderCodes(String body) {
        assertThat(AiProviderErrorMapper.providerCodeFromBody(body)).isEqualTo("unknown");
    }

    @Test
    void extractsMachineCodesWithoutRetainingTheErrorMessage() {
        assertThat(AiProviderErrorMapper.providerCodeFromBody(
                "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"private material\"}}"))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(AiProviderErrorMapper.providerCodeFromBody(
                "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"private API key\"}}"))
                .isEqualTo("insufficient_quota");
        assertThat(new AiProviderErrorMapper.HttpFailure(400, null, false, "private-api-key").providerCode())
                .isEqualTo("unknown");
    }

    @Test
    void treatsAnOverloadedModelAsWorthRetrying() {
        // El mensaje real que devolvió Gemini cuando falló la clasificación.
        Throwable error = new RuntimeException(
                "503 . This model is currently experiencing high demand. "
                        + "Spikes in demand are usually temporary. Please try again later.");

        assertThat(AiProviderErrorMapper.isTransient(error)).isTrue();
    }

    @Test
    void findsTheCauseEvenWhenItComesWrapped() {
        Throwable error = new IllegalStateException("Falló la clasificación",
                new RuntimeException("The service is currently unavailable"));

        assertThat(AiProviderErrorMapper.isTransient(error)).isTrue();
    }

    @Test
    void doesNotTreatAnAmbiguous429AsALongTermQuotaOrRetryIt() {
        Throwable error = new RuntimeException("429 Quota exceeded. Please try again later.");

        assertThat(AiProviderErrorMapper.isQuotaExceeded(error))
                .as("legacy callers keep treating every 429 as a limit").isTrue();
        assertThat(AiProviderErrorMapper.isLimitReached(error)).isTrue();
        assertThat(AiProviderErrorMapper.kind(error)).isEqualTo(AiProviderErrorMapper.Kind.RATE_LIMIT);
        assertThat(AiProviderErrorMapper.isTransient(error))
                .as("esperar un par de segundos no devuelve cuota")
                .isFalse();
    }

    @Test
    void doesNotRetryWhatWillFailAgainTheSameWay() {
        assertThat(AiProviderErrorMapper.isTransient(
                new RuntimeException("400 Invalid request: missing field"))).isFalse();
        assertThat(AiProviderErrorMapper.isTransient(
                new RuntimeException("401 Unauthorized"))).isFalse();
        assertThat(AiProviderErrorMapper.isTransient(new RuntimeException())).isFalse();
    }

    @Test
    void explainsAnUnknownModelWithoutExposingTheProviderException() {
        Throwable error = new RuntimeException("Request failed",
                new RuntimeException("404: Unknown"));

        assertThat(AiProviderErrorMapper.isModelNotFound(error)).isTrue();
        assertThat(AiProviderErrorMapper.chatMessage(error))
                .isEqualTo("El proveedor no encontró el modelo configurado. "
                        + "Revisá el nombre del modelo en Mi perfil");
    }

    @Test
    void explainsRejectedCredentials() {
        Throwable error = new RuntimeException("401 Unauthorized: incorrect API key");

        assertThat(AiProviderErrorMapper.isAuthenticationFailure(error)).isTrue();
        assertThat(AiProviderErrorMapper.chatMessage(error))
                .isEqualTo("El proveedor rechazó la API key. Revisala en Mi perfil");
    }

    @Test
    void recognizesGeminiNetworkFailuresWithoutReadingTheirPrivateMessages() {
        var disconnected = new com.google.genai.errors.GenAiIOException("private URL and credentials",
                new java.net.SocketException("connection reset"));
        assertThat(AiProviderErrorMapper.kind(disconnected)).isEqualTo(AiProviderErrorMapper.Kind.CONNECTIVITY);
        assertThat(AiProviderErrorMapper.isProviderFailure(disconnected)).isTrue();
        assertThat(AiProviderErrorMapper.isTransient(disconnected)).isFalse();
        assertThat(AiProviderErrorMapper.chatMessage(disconnected)).doesNotContain("private URL and credentials");
        var timeout = new com.google.genai.errors.GenAiIOException("private URL", new SocketTimeoutException());
        assertThat(AiProviderErrorMapper.kind(timeout)).isEqualTo(AiProviderErrorMapper.Kind.TIMEOUT);
    }

    @ParameterizedTest
    @CsvSource({
            "429,false,RATE_LIMIT,429", "429,true,QUOTA,429",
            "503,false,SERVICE_UNAVAILABLE,503", "504,false,TIMEOUT,504", "408,false,TIMEOUT,504",
            "401,false,AUTHENTICATION,422", "403,false,AUTHENTICATION,422",
            "404,false,MODEL_NOT_FOUND,422", "400,false,UNKNOWN,502", "500,false,UNKNOWN,502"
    })
    void mapsTypedHttpMetadataToSafePublicStatuses(
            int upstreamStatus, boolean quota, AiProviderErrorMapper.Kind kind, int publicStatus) {
        var error = new AiProviderErrorMapper.HttpFailure(upstreamStatus, 2501L, quota);
        error.initCause(new RuntimeException("private document and api-key 503"));

        assertThat(AiProviderErrorMapper.kind(error)).isEqualTo(kind);
        assertThat(AiProviderErrorMapper.httpStatus(error).value()).isEqualTo(publicStatus);
        assertThat(AiProviderErrorMapper.isProviderFailure(error)).isTrue();
        assertThat(AiProviderErrorMapper.isTransient(error)).isEqualTo(upstreamStatus == 503);
        assertThat(AiProviderErrorMapper.retryAfterMillis(error)).hasValue(2501L);
        assertThat(AiProviderErrorMapper.chatMessage(error)).doesNotContain("private", "api-key");
        assertThat(error.getMessage()).isEqualTo("El proveedor de IA rechazó la solicitud");
    }

    @Test
    void typedCausesWinOverValidationWrappersAndMisleadingOuterStatusText() {
        var rateLimit = new RuntimeException("503 overload", new ApiException(429, "RESOURCE_EXHAUSTED", "RPM quota exceeded"));
        assertThat(AiProviderErrorMapper.kind(rateLimit)).isEqualTo(AiProviderErrorMapper.Kind.RATE_LIMIT);
        assertThat(AiProviderErrorMapper.isTransient(rateLimit)).isFalse();

        var unavailable = new IllegalArgumentException("private validation wrapper",
                new ApiException(503, "UNAVAILABLE", "private upstream response"));
        assertThat(AiProviderErrorMapper.kind(unavailable)).isEqualTo(AiProviderErrorMapper.Kind.SERVICE_UNAVAILABLE);
        assertThat(AiProviderErrorMapper.isProviderFailure(unavailable)).isTrue();
        assertThat(AiProviderErrorMapper.chatMessage(unavailable)).doesNotContain("private");
    }

    @Test
    void distinguishesPerDayGoogleQuotaFromPerMinuteRateLimits() {
        assertThat(AiProviderErrorMapper.isQuotaExceeded(new ApiException(429, "RESOURCE_EXHAUSTED",
                "quotaMetric: GenerateRequestsPerDayPerProject"))).isTrue();
        assertThat(AiProviderErrorMapper.kind(new ApiException(429, "RESOURCE_EXHAUSTED",
                "quotaMetric: GenerateRequestsPerMinutePerProject")))
                .isEqualTo(AiProviderErrorMapper.Kind.RATE_LIMIT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"insufficient_quota", "billing_hard_limit_reached", "billing_limit",
            "requests_per_day", "daily quota exhausted", "daily limit exceeded"})
    void recognizesOnlyExplicitLongTermQuotaEvidence(String code) {
        assertThat(AiProviderErrorMapper.isLongTermQuota(code)).isTrue();
        assertThat(AiProviderErrorMapper.isQuotaExceeded(new RuntimeException("429: " + code))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"quota exceeded", "rate_limit_exceeded", "requests_per_minute", "quota for tokens per minute"})
    void keepsAmbiguousQuotaAndShortTermLimitsInTheRateLimitCategory(String code) {
        assertThat(AiProviderErrorMapper.isLongTermQuota(code)).isFalse();
        assertThat(AiProviderErrorMapper.kind(new RuntimeException("429: " + code)))
                .isEqualTo(AiProviderErrorMapper.Kind.RATE_LIMIT);
    }

    @Test
    void readsOpenAiStatusQuotaCodesAndRetryAfterFromTheInstalledSdkTypes() {
        Headers headers = Headers.builder().put("Retry-After", "7").build();
        var busy = InternalServerException.builder().statusCode(503).headers(headers).build();
        assertThat(AiProviderErrorMapper.kind(busy)).isEqualTo(AiProviderErrorMapper.Kind.SERVICE_UNAVAILABLE);
        assertThat(AiProviderErrorMapper.retryAfterMillis(busy)).hasValue(7000L);

        var quota = RateLimitException.builder().headers(headers).error(ErrorObject.builder()
                .code("insufficient_quota").message("private provider response")
                .param((String) null).type("insufficient_quota").build()).build();
        assertThat(AiProviderErrorMapper.isQuotaExceeded(quota)).isTrue();
        assertThat(AiProviderErrorMapper.isTransient(quota)).isFalse();
        assertThat(AiProviderErrorMapper.chatMessage(quota)).doesNotContain("private provider response");

        var rpm = RateLimitException.builder().headers(headers).build();
        assertThat(AiProviderErrorMapper.kind(rpm)).isEqualTo(AiProviderErrorMapper.Kind.RATE_LIMIT);
    }

    @Test
    void readsSpringStatusAndHeadersAndPrefersTheLongestAvailableDelay() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "2");
        headers.add(HttpHeaders.RETRY_AFTER, "invalid-private-value");
        headers.add(HttpHeaders.RETRY_AFTER, "9");
        var spring = new RestClientResponseException("private response", 503, "private status", headers,
                "private body".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        var wrapper = new AiProviderErrorMapper.HttpFailure(503, 2501L, false);
        wrapper.initCause(spring);

        assertThat(AiProviderErrorMapper.kind(spring)).isEqualTo(AiProviderErrorMapper.Kind.SERVICE_UNAVAILABLE);
        assertThat(AiProviderErrorMapper.retryAfterMillis(wrapper)).hasValue(9000L);
        assertThat(AiProviderErrorMapper.chatMessage(spring)).doesNotContain("private");
    }

    @Test
    void identifiesTypedTimeoutsAndConnectivityWithoutRetryingThem() {
        Throwable[] timeouts = {new SocketTimeoutException("private 503 body"),
                new HttpTimeoutException("private timeout"), new InterruptedIOException("timeout"),
                new ResourceAccessException("private resource", new SocketTimeoutException("read timeout")),
                new OpenAIIoException("private sdk message", new SocketTimeoutException("read timeout"))};
        for (Throwable error : timeouts) {
            assertThat(AiProviderErrorMapper.kind(error)).isEqualTo(AiProviderErrorMapper.Kind.TIMEOUT);
            assertThat(AiProviderErrorMapper.httpStatus(error)).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(AiProviderErrorMapper.isProviderFailure(error)).isTrue();
            assertThat(AiProviderErrorMapper.isTransient(error)).isFalse();
        }
        Throwable[] connectivity = {new ConnectException("503 connection refused"), new UnknownHostException("private host"),
                new ResourceAccessException("private url", new ConnectException("refused")), new OpenAIIoException("I/O error")};
        for (Throwable error : connectivity) {
            assertThat(AiProviderErrorMapper.kind(error)).isEqualTo(AiProviderErrorMapper.Kind.CONNECTIVITY);
            assertThat(AiProviderErrorMapper.httpStatus(error)).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(AiProviderErrorMapper.isTransient(error)).isFalse();
        }
        assertThat(AiProviderErrorMapper.kind(new InterruptedIOException("interrupted")))
                .isEqualTo(AiProviderErrorMapper.Kind.UNKNOWN);
    }

    @Test
    void doesNotClassifyPrivateJsonOrValidationTextAsAProviderFailure() {
        Throwable[] errors = {new IllegalArgumentException("503 overloaded"),
                new RuntimeException("wrapper", new JsonProcessingException("503 private document") { }),
                new RuntimeException("the document mentions 503 unavailable"), new RuntimeException("1503: example")};
        for (Throwable error : errors) {
            assertThat(AiProviderErrorMapper.kind(error)).isEqualTo(AiProviderErrorMapper.Kind.UNKNOWN);
            assertThat(AiProviderErrorMapper.isProviderFailure(error)).isFalse();
        }
        assertThat(AiProviderErrorMapper.isProviderFailure(new RuntimeException("503: legacy failure"))).isFalse();
        assertThat(AiProviderErrorMapper.kind(null)).isEqualTo(AiProviderErrorMapper.Kind.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Provider overloaded; try again later", "Temporary high demand", "upstream unavailable"})
    void preservesLegacyOverloadDetectionWithoutBroadeningResourceGenerationClassification(String message) {
        var failure = new RuntimeException(message);
        assertThat(AiProviderErrorMapper.isTransient(failure)).isTrue();
        assertThat(AiProviderErrorMapper.kind(failure)).isEqualTo(AiProviderErrorMapper.Kind.UNKNOWN);
    }

    @Test
    void preservesLegacyPublicMessagesWhileGenerationCanDistinguishRateLimitsAndTimeouts() {
        var rate = new AiProviderErrorMapper.HttpFailure(429, null, false);
        assertThat(AiProviderErrorMapper.chatMessage(rate))
                .isEqualTo("Se alcanzó la cuota del proveedor de IA. Intentá nuevamente más tarde");
        assertThat(AiProviderErrorMapper.indexingMessage(rate))
                .isEqualTo("Se alcanzó la cuota de IA para procesar documentos. Intentá nuevamente más tarde");
        assertThat(AiProviderErrorMapper.generationMessage(rate)).contains("límite de solicitudes");
        var timeout = new RuntimeException(new SocketTimeoutException());
        assertThat(AiProviderErrorMapper.chatMessage(timeout))
                .isEqualTo("No se pudo obtener una respuesta del proveedor de IA");
        assertThat(AiProviderErrorMapper.generationMessage(timeout)).contains("tardó demasiado");
    }

    @Test
    void parsesRetryAfterSecondsAndHttpDatesWithoutShorteningTheWait() {
        Instant now = Instant.parse("2026-10-31T12:00:00.999500Z");
        assertThat(AiProviderErrorMapper.parseRetryAfter(" 7 ", now)).hasValue(7000L);
        assertThat(AiProviderErrorMapper.parseRetryAfter("0", now)).hasValue(0);
        assertThat(AiProviderErrorMapper.parseRetryAfter("Sat, 31 Oct 2026 12:00:01 GMT", now)).hasValue(1);
        assertThat(AiProviderErrorMapper.parseRetryAfter("Sat, 31 Oct 2026 12:00:03 GMT", now)).hasValue(2001L);
        assertThat(AiProviderErrorMapper.parseRetryAfter("Sat, 31 Oct 2026 11:59:59 GMT", now)).hasValue(0);
        assertThat(AiProviderErrorMapper.parseRetryAfter("9223372036854775807", now)).hasValue(Long.MAX_VALUE);
        assertThat(AiProviderErrorMapper.parseRetryAfter("999999999999999999999999999", now)).hasValue(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "+1", "1.5", "tomorrow", "", "private header", "1\r\nprivate-header: value"})
    void ignoresInvalidRetryAfterHeaders(String header) {
        assertThat(AiProviderErrorMapper.parseRetryAfter(header, Instant.EPOCH)).isEmpty();
    }

    @Test
    void absentAndInvalidRetryMetadataDoNotInventAWait() {
        assertThat(AiProviderErrorMapper.retryAfterMillis(new RuntimeException("Retry-After: 5"))).isEmpty();
        assertThat(AiProviderErrorMapper.retryAfterMillis(new AiProviderErrorMapper.HttpFailure(503, -1L, false))).isEmpty();
        assertThat(AiProviderErrorMapper.parseRetryAfter(null, Instant.EPOCH)).isEmpty();
        assertThat(AiProviderErrorMapper.parseRetryAfter("5", null)).isEmpty();
        assertThat(AiProviderErrorMapper.parseRetryAfterMillisHeader("2501")).hasValue(2501);
        assertThat(AiProviderErrorMapper.parseRetryAfterMillisHeader("-1")).isEmpty();
        assertThat(AiProviderErrorMapper.parseRetryAfterMillisHeader("999999999999999999999")).hasValue(Long.MAX_VALUE);
    }

    @Test
    void stopsWalkingACyclicCauseChain() {
        var first = new RuntimeException("private first");
        var second = new RuntimeException("private second", first);
        first.initCause(second);
        assertThat(AiProviderErrorMapper.kind(first)).isEqualTo(AiProviderErrorMapper.Kind.UNKNOWN);
        assertThat(AiProviderErrorMapper.retryAfterMillis(first)).isEmpty();
    }
}
