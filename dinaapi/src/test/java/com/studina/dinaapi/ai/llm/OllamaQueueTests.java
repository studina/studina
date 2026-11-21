package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.studina.dinaapi.ai.rag.AiProviderErrorMapper;
import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;

@Timeout(10)
@ExtendWith(OutputCaptureExtension.class)
class OllamaQueueTests {

    @Test
    void allowsOneActiveCallAndTwoWaitersThenRejectsWithoutCallingOllama(CapturedOutput output) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        var client = localClient(prompt -> {
            calls.incrementAndGet();
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            await(release);
            active.decrementAndGet();
            return response();
        });

        try (var workers = Executors.newFixedThreadPool(3)) {
            try {
                var first = workers.submit(() -> client.generate(request()));
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> client.generate(request()));
                var third = workers.submit(() -> client.generate(request()));
                awaitWaiting(client, 2);

                assertThatThrownBy(() -> client.generate(request()))
                        .isInstanceOfSatisfying(AiProviderErrorMapper.LocalQueueFailure.class,
                                failure -> assertThat(failure.reason())
                                        .isEqualTo(AiProviderErrorMapper.LocalQueueFailure.Reason.FULL));
                assertThat(calls).hasValue(1);
                release.countDown();
                assertThat(first.get(2, TimeUnit.SECONDS).content()).isEqualTo("response");
                assertThat(second.get(2, TimeUnit.SECONDS).content()).isEqualTo("response");
                assertThat(third.get(2, TimeUnit.SECONDS).content()).isEqualTo("response");
                assertThat(calls).hasValue(3);
                assertThat(maxActive).hasValue(1);
                assertThat(output.getAll()).contains("LLM call queued:", "queueCapacity=2",
                        "queueTimeoutSeconds=600", "phase=queue", "reason=FULL")
                        .doesNotContain("private document", "private system prompt");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void timesOutOnlyTheWaitingCallAndDoesNotReleaseAnotherCallsSlot() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var client = localClient(prompt -> {
            calls.incrementAndGet();
            started.countDown();
            await(release);
            return response();
        });
        ReflectionTestUtils.setField(client, "ollamaQueueTimeoutSeconds", 1L);

        try (var worker = Executors.newSingleThreadExecutor()) {
            try {
                var first = worker.submit(() -> client.generate(request()));
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                long before = System.nanoTime();
                assertThatThrownBy(() -> client.generate(request()))
                        .isInstanceOfSatisfying(AiProviderErrorMapper.LocalQueueFailure.class,
                                failure -> assertThat(failure.reason())
                                        .isEqualTo(AiProviderErrorMapper.LocalQueueFailure.Reason.WAIT_TIMEOUT));
                assertThat(Duration.ofNanos(System.nanoTime() - before))
                        .isBetween(Duration.ofMillis(900), Duration.ofSeconds(3));
                assertThat(calls).hasValue(1);
                awaitWaiting(client, 0);
                release.countDown();
                first.get(2, TimeUnit.SECONDS);
                assertThat(client.generate(request()).content()).isEqualTo("response");
                assertThat(calls).hasValue(2);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void interruptionRemovesTheWaiterWithoutLosingTheSemaphorePermit() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var client = localClient(prompt -> {
            started.countDown();
            await(release);
            return response();
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var first = workers.submit(() -> client.generate(request()));
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> client.generate(request()));
                awaitWaiting(client, 1);
                second.cancel(true);
                awaitWaiting(client, 0);
                release.countDown();
                first.get(2, TimeUnit.SECONDS);
                assertThat(client.generate(request()).content()).isEqualTo("response");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cloudCallsDoNotWaitForTheOllamaSlot() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ChatClient local = ChatClient.create(prompt -> {
            started.countDown();
            await(release);
            return response();
        });
        var settingsService = mock(UserLlmSettingsService.class);
        var factory = mock(LlmProviderClientFactory.class);
        var localSettings = new UserLlmRuntimeSettings("ollama", "local-model", null, null, null, null);
        var cloudSettings = new UserLlmRuntimeSettings("openai", "cloud-model", null, null, null, "synthetic-key");
        when(settingsService.runtimeFor("cloud-user")).thenReturn(Optional.of(cloudSettings));
        when(factory.create(localSettings, "local-model", 0.2)).thenReturn(local);
        when(factory.create(cloudSettings, "cloud-model", 0.2))
                .thenReturn(ChatClient.create(prompt -> response()));
        var client = new SpringAiLlmClient(mock(ChatClient.class), localProperties(), settingsService, factory);

        try (var worker = Executors.newSingleThreadExecutor()) {
            try {
                var first = worker.submit(() -> client.generate(LlmRequest.of("System", "User")));
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                var response = client.generate(LlmRequest.of("System", "User").forUser("cloud-user"));
                assertThat(response.provider()).isEqualTo("openai");
                assertThat(response.queueWaitMs()).isZero();
                assertThat(response.content()).isEqualTo("response");
                release.countDown();
                first.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectsInvalidQueueConfiguration() {
        var client = localClient(prompt -> response());
        ReflectionTestUtils.setField(client, "ollamaQueueTimeoutSeconds", 0L);
        assertThatThrownBy(client::validateOllamaQueue).isInstanceOf(IllegalArgumentException.class);
        ReflectionTestUtils.setField(client, "ollamaQueueTimeoutSeconds", 600L);
        ReflectionTestUtils.setField(client, "ollamaMaxQueuedRequests", -1);
        assertThatThrownBy(client::validateOllamaQueue).isInstanceOf(IllegalArgumentException.class);
    }

    private static SpringAiLlmClient localClient(ChatModel model) {
        return new SpringAiLlmClient(ChatClient.create(model), localProperties());
    }

    private static LlmProperties localProperties() {
        var properties = new LlmProperties();
        properties.setProvider("ollama");
        properties.setModel("local-model");
        return properties;
    }

    private static LlmRequest request() {
        return LlmRequest.of("private system prompt", "private document").forResourceGeneration();
    }

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("response"))));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test call was not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitWaiting(SpringAiLlmClient client, int expected) throws InterruptedException {
        var waiting = (AtomicInteger) ReflectionTestUtils.getField(client, "ollamaWaiting");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (waiting.get() != expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(waiting).hasValue(expected);
    }
}
