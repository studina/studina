package com.studina.dinaapi.studyplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

class GeminiStudyPlanGeneratorStructuredOutputTests {

    private static final LocalDate START = LocalDate.of(2026, 11, 21);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void requestsNativeJsonAndConvertsACompleteFourteenTopicPlan() throws Exception {
        var model = new QueuedChatModel(json(validResponse()));

        GeneratedStudyPlan result = generator(model).generate(request());

        assertThat(result.canBeScheduled()).isTrue();
        assertThat(result.requiredMinutes()).isEqualTo(14 * 60);
        assertThat(result.availableMinutes()).isEqualTo(7 * 5 * 60);
        assertThat(result.sessions()).hasSize(17);
        assertThat(result.sessions().stream()
                .filter(session -> session.type() == StudySessionType.STUDY)
                .map(StudySession::topicIndex))
                .containsExactlyElementsOf(IntStream.range(0, 14).boxed().toList());
        assertThat(result.sessions().stream()
                .filter(session -> session.type() == StudySessionType.REVIEW)).hasSize(2);
        assertThat(result.sessions().stream()
                .filter(session -> session.type() == StudySessionType.MOCK_EXAM))
                .singleElement()
                .satisfies(session -> assertThat(session.startDateTime().toLocalDate())
                        .isEqualTo(START.plusDays(6)));
        assertThat(model.prompts).singleElement().satisfies(this::assertNativeJson);
    }

    @Test
    void alsoRequestsNativeJsonWhenRepairingAnInvalidCalendar() throws Exception {
        var valid = validResponse();
        var sessions = new ArrayList<>(valid.sessions());
        var first = sessions.get(0);
        sessions.set(0, new GeminiStudySession(first.title(),
                START.minusDays(1) + "T18:00:00", START.minusDays(1) + "T19:00:00",
                first.type(), first.topicIndex(), first.partNumber()));
        var invalid = new GeminiStudyPlanResponse(true, valid.topicPlans(), sessions, "Plan para corregir.");
        var model = new QueuedChatModel(json(invalid), json(valid));

        GeneratedStudyPlan result = generator(model).generate(request());

        assertThat(result.canBeScheduled()).isTrue();
        assertThat(result.sessions()).hasSize(17);
        assertThat(model.prompts).hasSize(2).allSatisfy(this::assertNativeJson);
    }

    @Test
    void rejectsANullRequiredTopicIndexInsteadOfTreatingItAsTheFirstTopic() throws Exception {
        String malformed = json(validResponse()).replaceFirst("\"topicIndex\":0", "\"topicIndex\":null");
        var model = new QueuedChatModel(malformed);

        assertThatThrownBy(() -> generator(model).generate(request()))
                .isInstanceOf(StudyPlanGenerationException.class)
                .hasCauseInstanceOf(tools.jackson.databind.exc.MismatchedInputException.class)
                .hasMessage("La IA no pudo generar el plan de estudio. Volvé a intentarlo.");

        assertThat(model.prompts).hasSize(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsFailureClassesAndDurationWithoutProviderMessageContents(CapturedOutput output) {
        String sensitiveMarker = "FAKE_PRIVATE_PROVIDER_CONTENT_FOR_LOGGING_TEST";
        var failure = new IllegalStateException(sensitiveMarker,
                new IllegalArgumentException(sensitiveMarker));
        ChatModel model = prompt -> {
            throw failure;
        };

        assertThatThrownBy(() -> generator(model).generate(request()))
                .isInstanceOf(StudyPlanGenerationException.class)
                .hasMessage("La IA no pudo generar el plan de estudio. Volvé a intentarlo.")
                .hasCause(failure);

        assertThat(output.getAll())
                .contains("Study plan provider call failed", "exceptionClass=IllegalStateException",
                        "rootCauseClass=IllegalArgumentException")
                .containsPattern("callMs=\\d+")
                .doesNotContain(sensitiveMarker);
    }

    private GeminiStudyPlanGenerator generator(ChatModel model) {
        return new GeminiStudyPlanGenerator(ChatClient.builder(model), new StudyPlanValidator(),
                "gemini-study-plan-test", 0.0, 2);
    }

    private void assertNativeJson(Prompt prompt) {
        assertThat(prompt.getOptions()).isInstanceOf(GoogleGenAiChatOptions.class);
        var options = (GoogleGenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo("gemini-study-plan-test");
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getResponseSchema()).isNotBlank();
        try {
            var schema = JSON.readTree(options.getResponseSchema());
            assertThat(schema.path("properties").has("topicPlans")).isTrue();
            assertThat(schema.path("properties").has("sessions")).isTrue();
            assertThat(schema.at("/properties/sessions/items/properties/topicIndex/nullable").asBoolean())
                    .isTrue();
            assertThat(schema.at("/properties/sessions/items/properties/partNumber/nullable").asBoolean())
                    .isTrue();
            assertThat(schema.at("/properties/topicPlans/items/properties/topicIndex/nullable").asBoolean())
                    .isFalse();
        } catch (Exception exception) {
            throw new AssertionError("The model must receive a valid JSON response schema", exception);
        }
    }

    private GenerateAiStudyPlanRequest request() {
        var topics = IntStream.range(0, 14)
                .mapToObj(index -> new AiStudyTopic("topic-" + index, "Tema " + index, "",
                        0.0, "not_started", 0.0, index, 0))
                .toList();
        var availability = Arrays.stream(DayOfWeek.values())
                .map(day -> new StudyAvailability(day, LocalTime.of(18, 0), LocalTime.of(23, 0)))
                .toList();
        return new GenerateAiStudyPlanRequest("MIDTERM", "Segundo parcial", "", START,
                START.plusDays(7), 3, topics, availability);
    }

    private GeminiStudyPlanResponse validResponse() {
        var topics = IntStream.range(0, 14)
                .mapToObj(index -> new GeminiTopicPlan(index, 60, "Una sesión de estudio."))
                .toList();
        var sessions = new ArrayList<GeminiStudySession>();
        for (int index = 0; index < 14; index++) {
            LocalDate date = START.plusDays(index / 2);
            String start = index % 2 == 0 ? "18:00:00" : "19:15:00";
            String end = index % 2 == 0 ? "19:00:00" : "20:15:00";
            sessions.add(new GeminiStudySession("Estudio del tema " + index, date + "T" + start,
                    date + "T" + end, StudySessionType.STUDY, index, 1));
        }
        for (int day = 4; day <= 6; day++) {
            LocalDate date = START.plusDays(day);
            var type = day == 6 ? StudySessionType.MOCK_EXAM : StudySessionType.REVIEW;
            sessions.add(new GeminiStudySession("Repaso general", date + "T21:00:00",
                    date + "T22:00:00", type, null, null));
        }
        return new GeminiStudyPlanResponse(true, topics, sessions, "Plan completo.");
    }

    private String json(GeminiStudyPlanResponse response) throws Exception {
        return JSON.writeValueAsString(response);
    }

    private static final class QueuedChatModel implements ChatModel {
        private final Queue<String> responses;
        private final List<Prompt> prompts = new ArrayList<>();

        private QueuedChatModel(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public GoogleGenAiChatOptions getOptions() {
            return GoogleGenAiChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (responses.isEmpty()) {
                throw new AssertionError("The generator made an unexpected additional provider call");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responses.remove()))));
        }
    }
}
