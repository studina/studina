package com.studina.dinaapi.studyplan;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class StudyScheduleGenerator {

    public GeneratedStudyPlan generate(
            GenerateStudyPlanRequest request) {

        validate(request);

        List<AvailableSlot> allSlots =
                buildAvailableSlots(request);

        if (allSlots.isEmpty()) {
            return new GeneratedStudyPlan(
                    false,
                    calculateRequiredMinutes(request.topics()),
                    0,
                    calculateRequiredMinutes(request.topics()),
                    List.of(),
                    "No existen franjas disponibles antes del examen."
            );
        }

        int reservedSlotCount = Math.min(
                request.reviewSessions(),
                allSlots.size()
        );

        int studySlotCount =
                allSlots.size() - reservedSlotCount;

        List<AvailableSlot> studySlots =
                new ArrayList<>(
                        allSlots.subList(0, studySlotCount)
                );

        List<AvailableSlot> reviewSlots =
                new ArrayList<>(
                        allSlots.subList(
                                studySlotCount,
                                allSlots.size()
                        )
                );

        int requiredMinutes =
                calculateRequiredMinutes(request.topics());

        int availableMinutes =
                calculateAvailableMinutes(studySlots);

        if (availableMinutes < requiredMinutes) {
            return new GeneratedStudyPlan(
                    false,
                    requiredMinutes,
                    availableMinutes,
                    requiredMinutes - availableMinutes,
                    List.of(),
                    "No hay tiempo suficiente para distribuir todos los temas."
            );
        }

        List<StudySession> sessions =
                distributeTopics(
                        request.topics(),
                        studySlots
                );

        sessions.addAll(
                createReviewSessions(reviewSlots)
        );

        sessions.sort(
                Comparator.comparing(
                        StudySession::startDateTime
                )
        );

        return new GeneratedStudyPlan(
                true,
                requiredMinutes,
                availableMinutes,
                0,
                List.copyOf(sessions),
                "El plan fue generado correctamente."
        );
    }

    private List<AvailableSlot> buildAvailableSlots(
            GenerateStudyPlanRequest request) {

        List<AvailableSlot> slots = new ArrayList<>();

        LocalDate currentDate = request.startDate();

        while (currentDate.isBefore(request.examDate())) {

            for (StudyAvailability availability
                    : request.availability()) {

                if (currentDate.getDayOfWeek()
                        == availability.dayOfWeek()) {

                    LocalDateTime start =
                            LocalDateTime.of(
                                    currentDate,
                                    availability.startTime()
                            );

                    LocalDateTime end =
                            LocalDateTime.of(
                                    currentDate,
                                    availability.endTime()
                            );

                    slots.add(
                            new AvailableSlot(start, end)
                    );
                }
            }

            currentDate = currentDate.plusDays(1);
        }

        slots.sort(
                Comparator.comparing(
                        AvailableSlot::start
                )
        );

        return slots;
    }

    private List<StudySession> distributeTopics(
            List<StudyTopic> topics,
            List<AvailableSlot> slots) {

        List<StudySession> sessions =
                new ArrayList<>();

        int slotIndex = 0;
        LocalDateTime currentTime =
                slots.getFirst().start();

        for (int topicIndex = 0;
                topicIndex < topics.size();
                topicIndex++) {

            StudyTopic topic = topics.get(topicIndex);

            int remainingTopicMinutes =
                    topic.estimatedMinutes();

            int partNumber = 1;

            while (remainingTopicMinutes > 0) {

                if (slotIndex >= slots.size()) {
                    throw new IllegalStateException(
                            "No quedaron franjas disponibles."
                    );
                }

                AvailableSlot currentSlot =
                        slots.get(slotIndex);

                if (currentTime.isBefore(currentSlot.start())) {
                    currentTime = currentSlot.start();
                }

                long slotRemainingMinutes =
                        Duration.between(
                                currentTime,
                                currentSlot.end()
                        ).toMinutes();

                if (slotRemainingMinutes <= 0) {
                    slotIndex++;

                    if (slotIndex < slots.size()) {
                        currentTime =
                                slots.get(slotIndex).start();
                    }

                    continue;
                }

                int sessionMinutes =
                        (int) Math.min(
                                remainingTopicMinutes,
                                slotRemainingMinutes
                        );

                LocalDateTime sessionEnd =
                        currentTime.plusMinutes(
                                sessionMinutes
                        );

                String sessionTitle =
                        buildSessionTitle(
                                topic.title(),
                                partNumber,
                                remainingTopicMinutes,
                                sessionMinutes
                        );

                sessions.add(
                        new StudySession(
                                sessionTitle,
                                currentTime,
                                sessionEnd,
                                StudySessionType.STUDY,
                                topicIndex,
                                partNumber
                        )
                );

                remainingTopicMinutes -= sessionMinutes;
                currentTime = sessionEnd;
                partNumber++;
            }
        }

        return sessions;
    }

    private List<StudySession> createReviewSessions(
            List<AvailableSlot> reviewSlots) {

        List<StudySession> sessions =
                new ArrayList<>();

        for (int index = 0;
                index < reviewSlots.size();
                index++) {

            AvailableSlot slot =
                    reviewSlots.get(index);

            boolean isLast =
                    index == reviewSlots.size() - 1;

            StudySessionType type =
                    isLast
                            ? StudySessionType.MOCK_EXAM
                            : StudySessionType.REVIEW;

            String title =
                    isLast
                            ? "Simulacro previo al examen"
                            : "Repaso general";

            sessions.add(
                    new StudySession(
                            title,
                            slot.start(),
                            slot.end(),
                            type,
                            null,
                            null
                    )
            );
        }

        return sessions;
    }

    private String buildSessionTitle(
            String topicTitle,
            int partNumber,
            int remainingTopicMinutes,
            int sessionMinutes) {

        boolean topicFitsInOneSession =
                partNumber == 1
                        && sessionMinutes
                        == remainingTopicMinutes;

        if (topicFitsInOneSession) {
            return topicTitle;
        }

        return topicTitle
                + " — Parte "
                + partNumber;
    }

    private int calculateRequiredMinutes(
            List<StudyTopic> topics) {

        return topics.stream()
                .mapToInt(
                        StudyTopic::estimatedMinutes
                )
                .sum();
    }

    private int calculateAvailableMinutes(
            List<AvailableSlot> slots) {

        return slots.stream()
                .mapToInt(slot ->
                        Math.toIntExact(
                                Duration.between(
                                        slot.start(),
                                        slot.end()
                                ).toMinutes()
                        )
                )
                .sum();
    }

    private void validate(
            GenerateStudyPlanRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "La solicitud es obligatoria."
            );
        }

        if (request.startDate() == null
                || request.examDate() == null) {

            throw new IllegalArgumentException(
                    "Las fechas son obligatorias."
            );
        }

        if (!request.startDate()
                .isBefore(request.examDate())) {

            throw new IllegalArgumentException(
                    "La fecha de inicio debe ser anterior al examen."
            );
        }

        if (request.topics() == null
                || request.topics().isEmpty()) {

            throw new IllegalArgumentException(
                    "Debe existir al menos un tema."
            );
        }

        if (request.availability() == null
                || request.availability().isEmpty()) {

            throw new IllegalArgumentException(
                    "Debe existir al menos una franja disponible."
            );
        }

        if (request.reviewSessions() < 0) {
            throw new IllegalArgumentException(
                    "La cantidad de repasos no puede ser negativa."
            );
        }

        validateTopics(request.topics());
        validateAvailability(request.availability());
    }

    private void validateTopics(
            List<StudyTopic> topics) {

        for (StudyTopic topic : topics) {

            if (topic == null
                    || topic.title() == null
                    || topic.title().isBlank()) {

                throw new IllegalArgumentException(
                        "Todos los temas deben tener un título."
                );
            }

            if (topic.estimatedMinutes() <= 0) {
                throw new IllegalArgumentException(
                        "La duración de cada tema debe ser mayor que cero."
                );
            }
        }
    }

    private void validateAvailability(
            List<StudyAvailability> availabilityList) {

        for (StudyAvailability availability
                : availabilityList) {

            if (availability == null
                    || availability.dayOfWeek() == null
                    || availability.startTime() == null
                    || availability.endTime() == null) {

                throw new IllegalArgumentException(
                        "Las franjas disponibles están incompletas."
                );
            }

            if (!availability.startTime()
                    .isBefore(availability.endTime())) {

                throw new IllegalArgumentException(
                        "La hora inicial debe ser anterior a la hora final."
                );
            }
        }
    }

    private record AvailableSlot(
            LocalDateTime start,
            LocalDateTime end
    ) {
    }
}