package com.studina.dinaapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * El token de admin se reusa entre requests.
 *
 * <p>Directus demora cada login a propósito ({@code LOGIN_STALL_TIME}), así que pedir uno por
 * consulta volvía lenta cada subida. Lo que importa sólo se ve contando los logins que llegan,
 * así que el stub los registra.
 */
class DirectusAdminTokenTests {

    private HttpServer server;
    private final AtomicInteger logins = new AtomicInteger();
    /** Cuántas consultas a la materia rechazar con 401 antes de aceptar. */
    private final AtomicInteger subjectRejections = new AtomicInteger();
    private final List<String> subjectTokens = new ArrayList<>();
    private volatile String loginExpires = "900000";
    private MutableClock clock;
    private DirectusAuthorizationService service;

    @BeforeEach
    void startStubDirectus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();

            int status = 200;
            String body;
            if (path.endsWith("/auth/login")) {
                int login = logins.incrementAndGet();
                String expires = loginExpires == null ? "" : ",\"expires\":" + loginExpires;
                body = "{\"data\":{\"access_token\":\"admin-token-" + login + "\"" + expires + "}}";
            } else if (path.startsWith("/items/subjects/")) {
                synchronized (subjectTokens) {
                    subjectTokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
                }
                if (subjectRejections.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                    status = 401;
                    body = "{\"errors\":[{\"message\":\"Invalid user credentials.\"}]}";
                } else {
                    body = "{\"data\":{\"id\":\"subject-1\",\"user\":\"user-1\"}}";
                }
            } else {
                body = "{\"data\":[]}";
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        clock = new MutableClock(Instant.parse("2026-10-14T10:00:00Z"));
        service = new DirectusAuthorizationService();
        ReflectionTestUtils.setField(service, "directusUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "adminEmail", "admin@studina.test");
        ReflectionTestUtils.setField(service, "adminPassword", "secret");
        ReflectionTestUtils.setField(service, "clock", clock);
    }

    @AfterEach
    void stopStubDirectus() {
        server.stop(0);
    }

    @Test
    void reusesTheTokenInsteadOfLoggingInOnEveryCall() {
        String first = service.getAdminToken();
        String second = service.getAdminToken();

        assertThat(second).isEqualTo(first);
        assertThat(logins).hasValue(1);
    }

    @Test
    void logsInAgainOnlyWhenTheTokenIsAboutToExpire() {
        // 15 minutos de vida y un minuto de margen: se reusa hasta el minuto 14.
        service.getAdminToken();

        clock.advance(Duration.ofMinutes(13).plusSeconds(59));
        service.getAdminToken();
        assertThat(logins).as("todavía le queda más que el margen").hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.getAdminToken()).isEqualTo("admin-token-2");
        assertThat(logins).hasValue(2);
    }

    @Test
    void assumesAShortLifeWhenDirectusDoesNotReportOne() {
        loginExpires = null;
        service.getAdminToken();

        clock.advance(Duration.ofMinutes(3).plusSeconds(59));
        service.getAdminToken();
        assertThat(logins).hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        service.getAdminToken();
        assertThat(logins).as("sin vencimiento informado se usan 5 minutos menos el margen").hasValue(2);
    }

    @Test
    void renewsAtHalfLifeWhenTheMarginWouldConsumeTheWholeToken() {
        loginExpires = "60000";
        service.getAdminToken();

        clock.advance(Duration.ofSeconds(29));
        service.getAdminToken();
        assertThat(logins).hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        service.getAdminToken();
        assertThat(logins).hasValue(2);
    }

    @Test
    void replacesACachedTokenThatDirectusRejects() {
        service.getAdminToken();
        subjectRejections.set(1);

        service.requireSubjectOwnership("user-1", "subject-1");

        assertThat(logins).as("el 401 descarta el token y pide uno nuevo").hasValue(2);
        assertThat(subjectTokens).containsExactly("Bearer admin-token-1", "Bearer admin-token-2");

        service.getAdminToken();
        assertThat(logins).as("el token nuevo queda cacheado para los demás").hasValue(2);
    }

    @Test
    void retriesARejectedTokenOnlyOnce() {
        subjectRejections.set(2);

        assertThatThrownBy(() -> service.requireSubjectOwnership("user-1", "subject-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(subjectTokens).hasSize(2);
        assertThat(logins).hasValue(2);
    }

    @Test
    void concurrentRequestsWithNoTokenShareASingleLogin() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> {
                start.await();
                return service.getAdminToken();
            });
        }
        try {
            List<Future<String>> futures = new ArrayList<>();
            calls.forEach(call -> futures.add(executor.submit(call)));
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get()).isEqualTo("admin-token-1");
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(logins).hasValue(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
