package com.studina.dinaapi.studyplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class StudyPlanValidatorTests {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private final StudyPlanValidator validator = new StudyPlanValidator();

    @Test
    void rejectsAPlanConcentratedInTheFirstSevenDaysOfATwentyDayPeriod() {
        GenerateAiStudyPlanRequest request = request(
                MONDAY,
                MONDAY.plusDays(20),
                dailyAvailability(),
                0);
        GeminiStudyPlanResponse response = schedulableResponse(
                studySessions(MONDAY, 7),
                7 * 60);

        StudyPlanValidationResult result = validator.validate(request, response);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .anySatisfy(error -> assertThat(error)
                        .contains("2026-10-12", "2026-10-24"));
    }

    @Test
    void acceptsAPlanWithAtLeastOneSessionOnEveryAvailableDate() {
        GenerateAiStudyPlanRequest request = request(
                MONDAY,
                MONDAY.plusDays(20),
                dailyAvailability(),
                0);
        GeminiStudyPlanResponse response = schedulableResponse(
                studySessions(MONDAY, 20),
                20 * 60);

        StudyPlanValidationResult result = validator.validate(request, response);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void requiresOnlyWeekdaysPresentInAvailabilityAndExcludesExamDate() {
        List<StudyAvailability> availability = availability(
                DayOfWeek.MONDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY);
        GenerateAiStudyPlanRequest request = request(
                MONDAY,
                MONDAY.plusDays(7),
                availability,
                0);
        List<GeminiStudySession> sessions = List.of(
                studySession(MONDAY, 1),
                studySession(MONDAY.plusDays(2), 2),
                studySession(MONDAY.plusDays(4), 3));

        StudyPlanValidationResult result = validator.validate(
                request,
                schedulableResponse(sessions, 180));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void countsReviewAndMockExamSessionsAsDailyCoverage() {
        GenerateAiStudyPlanRequest request = request(
                MONDAY,
                MONDAY.plusDays(3),
                availability(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY),
                2);
        List<GeminiStudySession> sessions = List.of(
                studySession(MONDAY, 1),
                specialSession(MONDAY.plusDays(1), StudySessionType.REVIEW),
                specialSession(MONDAY.plusDays(2), StudySessionType.MOCK_EXAM));

        StudyPlanValidationResult result = validator.validate(
                request,
                schedulableResponse(sessions, 60));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void doesNotRequireDailyCoverageForAnImpossibleEmptyPlan() {
        GenerateAiStudyPlanRequest request = request(
                MONDAY,
                MONDAY.plusDays(20),
                dailyAvailability(),
                0);
        GeminiStudyPlanResponse response = new GeminiStudyPlanResponse(
                false,
                List.of(),
                List.of(),
                "La carga necesaria no entra en la disponibilidad.");

        StudyPlanValidationResult result = validator.validate(request, response);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    private GenerateAiStudyPlanRequest request(
            LocalDate startDate,
            LocalDate examDate,
            List<StudyAvailability> availability,
            int reviewSessions) {
        return new GenerateAiStudyPlanRequest(
                "EXAM",
                "Examen final",
                "",
                startDate,
                examDate,
                reviewSessions,
                List.of(new AiStudyTopic(
                        "topic-1",
                        "Redes",
                        "",
                        0.4,
                        "weak",
                        0.5,
                        2,
                        3)),
                availability);
    }

    private GeminiStudyPlanResponse schedulableResponse(
            List<GeminiStudySession> sessions,
            int plannedMinutes) {
        return new GeminiStudyPlanResponse(
                true,
                List.of(new GeminiTopicPlan(
                        0,
                        plannedMinutes,
                        "Necesita práctica distribuida.")),
                sessions,
                "Plan distribuido.");
    }

    private List<GeminiStudySession> studySessions(
            LocalDate startDate,
            int dayCount) {
        return IntStream.range(0, dayCount)
                .mapToObj(index -> studySession(
                        startDate.plusDays(index),
                        index + 1))
                .toList();
    }

    private GeminiStudySession studySession(
            LocalDate date,
            int partNumber) {
        return new GeminiStudySession(
                "Práctica de Redes",
                date + "T18:00:00",
                date + "T19:00:00",
                StudySessionType.STUDY,
                0,
                partNumber);
    }

    private GeminiStudySession specialSession(
            LocalDate date,
            StudySessionType type) {
        return new GeminiStudySession(
                type == StudySessionType.REVIEW
                        ? "Repaso general"
                        : "Simulacro",
                date + "T18:00:00",
                date + "T19:00:00",
                type,
                null,
                null);
    }

    private List<StudyAvailability> dailyAvailability() {
        return availability(DayOfWeek.values());
    }

    private List<StudyAvailability> availability(
            DayOfWeek... days) {
        return Arrays.stream(days)
                .map(day -> new StudyAvailability(
                        day,
                        LocalTime.of(18, 0),
                        LocalTime.of(22, 0)))
                .toList();
    }
}
