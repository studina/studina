package com.studina.dinaapi.studyplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class StudyScheduleGeneratorTests {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private final StudyScheduleGenerator generator = new StudyScheduleGenerator();

    @Test
    void distributesTopicsAcrossThePeriodAndReservesTheLastDaysForReview() {
        GenerateStudyPlanRequest request = request(
                MONDAY,
                MONDAY.plusDays(7),
                List.of(
                        new StudyTopic("Álgebra", 120),
                        new StudyTopic("Análisis", 60),
                        new StudyTopic("Geometría", 30)),
                availability(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY),
                2);

        GeneratedStudyPlan plan = generator.generate(request);

        assertThat(plan.canBeScheduled()).isTrue();
        assertThat(plan.requiredMinutes()).isEqualTo(210);
        assertThat(plan.availableMinutes()).isEqualTo(360);
        assertThat(plan.missingMinutes()).isZero();
        assertThat(plan.sessions()).extracting(StudySession::title).containsExactly(
                "Álgebra — Parte 1",
                "Análisis",
                "Geometría",
                "Álgebra — Parte 2",
                "Repaso general",
                "Simulacro previo al examen");
        assertThat(plan.sessions()).extracting(StudySession::type).containsExactly(
                StudySessionType.STUDY,
                StudySessionType.STUDY,
                StudySessionType.STUDY,
                StudySessionType.STUDY,
                StudySessionType.REVIEW,
                StudySessionType.MOCK_EXAM);
        assertThat(plan.sessions()).isSortedAccordingTo(
                java.util.Comparator.comparing(StudySession::startDateTime));
    }

    @Test
    void interleavesLongTopicsInsteadOfFinishingOneBeforeStartingTheNext() {
        GeneratedStudyPlan plan = generator.generate(request(
                MONDAY,
                MONDAY.plusDays(4),
                List.of(
                        new StudyTopic("A", 120),
                        new StudyTopic("B", 120)),
                availability(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY),
                0));

        assertThat(plan.sessions()).extracting(StudySession::title).containsExactly(
                "A — Parte 1",
                "B — Parte 1",
                "A — Parte 2",
                "B — Parte 2");
        assertThat(plan.sessions()).extracting(StudySession::partNumber)
                .containsExactly(1, 1, 2, 2);
    }

    @Test
    void limitsStudyToTwoHoursPerDayAndLeavesBreaksBetweenSessions() {
        GeneratedStudyPlan plan = generator.generate(request(
                MONDAY,
                MONDAY.plusDays(1),
                List.of(new StudyTopic("Redes", 120)),
                List.of(new StudyAvailability(
                        DayOfWeek.MONDAY,
                        LocalTime.of(18, 0),
                        LocalTime.of(22, 0))),
                0));

        assertThat(plan.canBeScheduled()).isTrue();
        assertThat(plan.availableMinutes()).isEqualTo(120);
        assertThat(plan.sessions()).extracting(StudySession::startDateTime).containsExactly(
                LocalDateTime.of(MONDAY, LocalTime.of(18, 0)),
                LocalDateTime.of(MONDAY, LocalTime.of(19, 15)));
        assertThat(plan.sessions()).extracting(StudySession::endDateTime).containsExactly(
                LocalDateTime.of(MONDAY, LocalTime.of(19, 0)),
                LocalDateTime.of(MONDAY, LocalTime.of(20, 15)));
    }

    @Test
    void placesASingleSessionInTheMiddleOfTheAvailablePeriod() {
        GeneratedStudyPlan plan = generator.generate(request(
                MONDAY,
                MONDAY.plusDays(3),
                List.of(new StudyTopic("Bases de datos", 45)),
                availability(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY),
                0));

        assertThat(plan.sessions()).singleElement().satisfies(session -> {
            assertThat(session.startDateTime().toLocalDate()).isEqualTo(MONDAY.plusDays(1));
            assertThat(session.endDateTime()).isEqualTo(session.startDateTime().plusMinutes(45));
        });
    }

    @Test
    void shortReviewWindowsProduceShortReviews() {
        List<StudyAvailability> availability = List.of(
                new StudyAvailability(DayOfWeek.MONDAY, LocalTime.of(18, 0), LocalTime.of(18, 30)),
                new StudyAvailability(DayOfWeek.TUESDAY, LocalTime.of(18, 0), LocalTime.of(18, 30)));

        GeneratedStudyPlan plan = generator.generate(request(
                MONDAY,
                MONDAY.plusDays(2),
                List.of(new StudyTopic("Sistemas", 30)),
                availability,
                1));

        assertThat(plan.sessions()).hasSize(2);
        StudySession review = plan.sessions().get(1);
        assertThat(review.type()).isEqualTo(StudySessionType.MOCK_EXAM);
        assertThat(review.endDateTime()).isEqualTo(review.startDateTime().plusMinutes(30));
    }

    @Test
    void reportsWhenThereAreNoMatchingDaysBeforeTheExam() {
        GeneratedStudyPlan plan = generator.generate(request(
                MONDAY.plusDays(1),
                MONDAY.plusDays(2),
                List.of(new StudyTopic("Redes", 90)),
                availability(DayOfWeek.MONDAY),
                0));

        assertThat(plan.canBeScheduled()).isFalse();
        assertThat(plan.availableMinutes()).isZero();
        assertThat(plan.missingMinutes()).isEqualTo(90);
        assertThat(plan.sessions()).isEmpty();
        assertThat(plan.message()).contains("No existen días disponibles");
    }

    @Test
    void reportsAtLeastOneFullSessionWhenSlotsCannotHoldEveryChunk() {
        GeneratedStudyPlan plan = generator.generate(request(
                MONDAY,
                MONDAY.plusDays(2),
                List.of(new StudyTopic("Redes", 180)),
                availability(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                1));

        assertThat(plan.canBeScheduled()).isFalse();
        assertThat(plan.requiredMinutes()).isEqualTo(180);
        assertThat(plan.availableMinutes()).isEqualTo(120);
        assertThat(plan.missingMinutes()).isEqualTo(60);
        assertThat(plan.sessions()).isEmpty();
    }

    @Test
    void rejectsInvalidTopLevelRequests() {
        assertInvalid(null, "La solicitud es obligatoria");
        assertInvalid(new GenerateStudyPlanRequest(
                null, MONDAY, topics(), availability(DayOfWeek.MONDAY), 0), "Las fechas son obligatorias");
        assertInvalid(request(
                MONDAY, MONDAY, topics(), availability(DayOfWeek.MONDAY), 0),
                "La fecha de inicio debe ser anterior");
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), List.of(), availability(DayOfWeek.MONDAY), 0),
                "Debe existir al menos un tema");
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), topics(), List.of(), 0),
                "Debe existir al menos una franja");
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), topics(), availability(DayOfWeek.MONDAY), -1),
                "no puede ser negativa");
    }

    @Test
    void rejectsInvalidTopics() {
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), Arrays.asList((StudyTopic) null),
                availability(DayOfWeek.MONDAY), 0), "deben tener un título");
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), List.of(new StudyTopic("  ", 30)),
                availability(DayOfWeek.MONDAY), 0), "deben tener un título");
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), List.of(new StudyTopic("Redes", 0)),
                availability(DayOfWeek.MONDAY), 0), "debe ser mayor que cero");
    }

    @Test
    void rejectsIncompleteOrBackwardsAvailability() {
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), topics(),
                Arrays.asList((StudyAvailability) null), 0), "están incompletas");
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), topics(),
                List.of(new StudyAvailability(null, LocalTime.NOON, LocalTime.MAX)), 0),
                "están incompletas");
        assertInvalid(request(
                MONDAY, MONDAY.plusDays(1), topics(),
                List.of(new StudyAvailability(DayOfWeek.MONDAY, LocalTime.NOON, LocalTime.NOON)), 0),
                "La hora inicial debe ser anterior");
    }

    private void assertInvalid(GenerateStudyPlanRequest request, String message) {
        assertThatThrownBy(() -> generator.generate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private GenerateStudyPlanRequest request(
            LocalDate start,
            LocalDate exam,
            List<StudyTopic> topics,
            List<StudyAvailability> availability,
            int reviews) {
        return new GenerateStudyPlanRequest(start, exam, topics, availability, reviews);
    }

    private List<StudyTopic> topics() {
        return List.of(new StudyTopic("Redes", 60));
    }

    private List<StudyAvailability> availability(DayOfWeek... days) {
        return Arrays.stream(days)
                .map(day -> new StudyAvailability(day, LocalTime.of(18, 0), LocalTime.of(21, 0)))
                .toList();
    }
}
