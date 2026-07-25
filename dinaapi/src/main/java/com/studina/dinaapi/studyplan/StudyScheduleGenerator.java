package com.studina.dinaapi.studyplan;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class StudyScheduleGenerator {

    /*
     * Reglas iniciales del planificador.
     */
    private static final int MAX_SESSION_MINUTES = 60;
    private static final int MAX_DAILY_MINUTES = 120;
    private static final int BREAK_MINUTES = 15;
    private static final int REVIEW_SESSION_MINUTES = 60;

    public GeneratedStudyPlan generate(
            GenerateStudyPlanRequest request) {

        validate(request);

        List<StudyDay> availableDays =
                buildAvailableDays(request);

        int requiredMinutes =
                calculateRequiredMinutes(request.topics());

        if (availableDays.isEmpty()) {
            return new GeneratedStudyPlan(
                    false,
                    requiredMinutes,
                    0,
                    requiredMinutes,
                    List.of(),
                    "No existen días disponibles antes del examen."
            );
        }

        /*
         * Los últimos días disponibles se reservan para repaso.
         *
         * Antes se reservaban franjas completas. Ahora se reservan días
         * concretos y el repaso tiene una duración fija.
         */
        int reviewDayCount = Math.min(
                request.reviewSessions(),
                availableDays.size()
        );

        int studyDayCount =
                availableDays.size() - reviewDayCount;

        List<StudyDay> studyDays =
                new ArrayList<>(
                        availableDays.subList(
                                0,
                                studyDayCount
                        )
                );

        List<StudyDay> reviewDays =
                new ArrayList<>(
                        availableDays.subList(
                                studyDayCount,
                                availableDays.size()
                        )
                );

        /*
         * Cada día se convierte en slots concretos:
         *
         * - máximo 60 minutos por sesión;
         * - máximo 120 minutos por día;
         * - 15 minutos de descanso entre sesiones.
         */
        List<AvailableSlot> studySlots =
                buildSessionSlots(studyDays);

        int availableMinutes =
                calculateAvailableMinutes(studySlots);

        /*
         * Los temas se dividen en partes de hasta 60 minutos.
         */
        List<TopicChunk> topicChunks =
                splitTopicsIntoChunks(request.topics());

        if (studySlots.size() < topicChunks.size()) {
            int missingMinutes =
                    Math.max(
                            requiredMinutes - availableMinutes,
                            MAX_SESSION_MINUTES
                    );

            return new GeneratedStudyPlan(
                    false,
                    requiredMinutes,
                    availableMinutes,
                    missingMinutes,
                    List.of(),
                    "No hay suficientes sesiones disponibles para distribuir todos los temas."
            );
        }

        /*
         * En lugar de tomar los primeros slots consecutivos,
         * elegimos slots repartidos a lo largo del período.
         */
        List<AvailableSlot> balancedSlots =
                selectBalancedSlots(
                        studySlots,
                        topicChunks.size()
                );

        List<StudySession> sessions =
                createStudySessions(
                        topicChunks,
                        balancedSlots
                );

        sessions.addAll(
                createReviewSessions(reviewDays)
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

    /**
     * Genera todos los días que coinciden con la disponibilidad semanal.
     *
     * Si el usuario cargó más de una franja para el mismo día, ambas se
     * agrupan dentro del mismo StudyDay.
     */
    private List<StudyDay> buildAvailableDays(
            GenerateStudyPlanRequest request) {

        Map<LocalDate, List<TimeWindow>> windowsByDate =
                new LinkedHashMap<>();

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

                    windowsByDate
                            .computeIfAbsent(
                                    currentDate,
                                    ignored -> new ArrayList<>()
                            )
                            .add(
                                    new TimeWindow(
                                            start,
                                            end
                                    )
                            );
                }
            }

            currentDate = currentDate.plusDays(1);
        }

        return windowsByDate.entrySet()
                .stream()
                .map(entry -> {
                    List<TimeWindow> windows =
                            new ArrayList<>(
                                    entry.getValue()
                            );

                    windows.sort(
                            Comparator.comparing(
                                    TimeWindow::start
                            )
                    );

                    return new StudyDay(
                            entry.getKey(),
                            windows
                    );
                })
                .sorted(
                        Comparator.comparing(
                                StudyDay::date
                        )
                )
                .toList();
    }

    /**
     * Convierte la disponibilidad en sesiones utilizables.
     *
     * Ejemplo:
     *
     * disponibilidad 18:00-21:00
     *
     * genera:
     * 18:00-19:00
     * descanso 15 min
     * 19:15-20:15
     *
     * El resto del tiempo queda libre porque se alcanzó el máximo diario.
     */
    private List<AvailableSlot> buildSessionSlots(
            List<StudyDay> studyDays) {

        List<AvailableSlot> slots =
                new ArrayList<>();

        for (StudyDay studyDay : studyDays) {

            int dailyUsedMinutes = 0;

            for (TimeWindow window : studyDay.windows()) {

                if (dailyUsedMinutes >= MAX_DAILY_MINUTES) {
                    break;
                }

                LocalDateTime currentTime =
                        window.start();

                while (
                        currentTime.isBefore(window.end())
                                && dailyUsedMinutes
                                < MAX_DAILY_MINUTES
                ) {

                    int remainingDailyMinutes =
                            MAX_DAILY_MINUTES
                                    - dailyUsedMinutes;

                    long remainingWindowMinutes =
                            Duration.between(
                                    currentTime,
                                    window.end()
                            ).toMinutes();

                    int slotMinutes =
                            (int) Math.min(
                                    MAX_SESSION_MINUTES,
                                    Math.min(
                                            remainingDailyMinutes,
                                            remainingWindowMinutes
                                    )
                            );

                    if (slotMinutes <= 0) {
                        break;
                    }

                    LocalDateTime slotEnd =
                            currentTime.plusMinutes(
                                    slotMinutes
                            );

                    slots.add(
                            new AvailableSlot(
                                    currentTime,
                                    slotEnd
                            )
                    );

                    dailyUsedMinutes += slotMinutes;

                    currentTime =
                            slotEnd.plusMinutes(
                                    BREAK_MINUTES
                            );
                }
            }
        }

        slots.sort(
                Comparator.comparing(
                        AvailableSlot::start
                )
        );

        return slots;
    }

    /**
     * Divide cada tema en partes de máximo 60 minutos.
     *
     * Además intercala los temas:
     *
     * A parte 1
     * B parte 1
     * C parte 1
     * A parte 2
     * B parte 2
     *
     * En lugar de:
     *
     * A parte 1
     * A parte 2
     * A parte 3
     * B parte 1
     */
    private List<TopicChunk> splitTopicsIntoChunks(
            List<StudyTopic> topics) {

        Map<Integer, List<TopicChunk>> chunksByTopic =
                new LinkedHashMap<>();

        for (int topicIndex = 0;
                topicIndex < topics.size();
                topicIndex++) {

            StudyTopic topic =
                    topics.get(topicIndex);

            List<TopicChunk> topicChunks =
                    new ArrayList<>();

            int remainingMinutes =
                    topic.estimatedMinutes();

            int partNumber = 1;

            while (remainingMinutes > 0) {

                int chunkMinutes =
                        Math.min(
                                remainingMinutes,
                                MAX_SESSION_MINUTES
                        );

                topicChunks.add(
                        new TopicChunk(
                                topicIndex,
                                topic.title(),
                                partNumber,
                                chunkMinutes,
                                topic.estimatedMinutes()
                        )
                );

                remainingMinutes -= chunkMinutes;
                partNumber++;
            }

            chunksByTopic.put(
                    topicIndex,
                    topicChunks
            );
        }

        List<TopicChunk> interleaved =
                new ArrayList<>();

        boolean chunksRemaining = true;
        int round = 0;

        while (chunksRemaining) {
            chunksRemaining = false;

            for (List<TopicChunk> topicChunks
                    : chunksByTopic.values()) {

                if (round < topicChunks.size()) {
                    interleaved.add(
                            topicChunks.get(round)
                    );

                    chunksRemaining = true;
                }
            }

            round++;
        }

        return interleaved;
    }

    /**
     * Selecciona slots distribuidos de manera uniforme.
     *
     * Ejemplo:
     *
     * 10 slots disponibles
     * 4 sesiones necesarias
     *
     * En lugar de usar 0,1,2,3 se usan aproximadamente:
     * 0,3,6,9
     */
    private List<AvailableSlot> selectBalancedSlots(
            List<AvailableSlot> availableSlots,
            int requiredSlotCount) {

        if (requiredSlotCount == 0) {
            return List.of();
        }

        if (requiredSlotCount == 1) {
            int middleIndex =
                    availableSlots.size() / 2;

            return List.of(
                    availableSlots.get(middleIndex)
            );
        }

        List<AvailableSlot> selected =
                new ArrayList<>();

        int lastSelectedIndex = -1;

        for (int index = 0;
                index < requiredSlotCount;
                index++) {

            double proportionalPosition =
                    (double) index
                            * (availableSlots.size() - 1)
                            / (requiredSlotCount - 1);

            int selectedIndex =
                    (int) Math.round(
                            proportionalPosition
                    );

            /*
             * Garantiza que nunca se seleccione dos veces
             * el mismo slot por efecto del redondeo.
             */
            selectedIndex =
                    Math.max(
                            selectedIndex,
                            lastSelectedIndex + 1
                    );

            int remainingRequiredSlots =
                    requiredSlotCount - index - 1;

            int maximumAllowedIndex =
                    availableSlots.size()
                            - remainingRequiredSlots
                            - 1;

            selectedIndex =
                    Math.min(
                            selectedIndex,
                            maximumAllowedIndex
                    );

            selected.add(
                    availableSlots.get(
                            selectedIndex
                    )
            );

            lastSelectedIndex = selectedIndex;
        }

        return selected;
    }

    private List<StudySession> createStudySessions(
            List<TopicChunk> chunks,
            List<AvailableSlot> slots) {

        List<StudySession> sessions =
                new ArrayList<>();

        for (int index = 0;
                index < chunks.size();
                index++) {

            TopicChunk chunk =
                    chunks.get(index);

            AvailableSlot slot =
                    slots.get(index);

            LocalDateTime sessionEnd =
                    slot.start().plusMinutes(
                            chunk.minutes()
                    );

            String title =
                    buildSessionTitle(chunk);

            sessions.add(
                    new StudySession(
                            title,
                            slot.start(),
                            sessionEnd,
                            StudySessionType.STUDY,
                            chunk.topicIndex(),
                            chunk.partNumber()
                    )
            );
        }

        return sessions;
    }

    /**
     * Los repasos se ubican en los últimos días disponibles.
     *
     * Ya no ocupan toda la franja disponible. Duran 60 minutos.
     * El último repaso se etiqueta como simulacro.
     */
    private List<StudySession> createReviewSessions(
            List<StudyDay> reviewDays) {

        List<StudySession> sessions =
                new ArrayList<>();

        for (int index = 0;
                index < reviewDays.size();
                index++) {

            StudyDay day =
                    reviewDays.get(index);

            if (day.windows().isEmpty()) {
                continue;
            }

            TimeWindow firstWindow =
                    day.windows().getFirst();

            long availableWindowMinutes =
                    Duration.between(
                            firstWindow.start(),
                            firstWindow.end()
                    ).toMinutes();

            int reviewMinutes =
                    (int) Math.min(
                            REVIEW_SESSION_MINUTES,
                            availableWindowMinutes
                    );

            if (reviewMinutes <= 0) {
                continue;
            }

            boolean isLast =
                    index
                            == reviewDays.size() - 1;

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
                            firstWindow.start(),
                            firstWindow.start()
                                    .plusMinutes(
                                            reviewMinutes
                                    ),
                            type,
                            null,
                            null
                    )
            );
        }

        return sessions;
    }

    private String buildSessionTitle(
            TopicChunk chunk) {

        boolean topicHasOnePart =
                chunk.totalTopicMinutes()
                        <= MAX_SESSION_MINUTES;

        if (topicHasOnePart) {
            return chunk.topicTitle();
        }

        return chunk.topicTitle()
                + " — Parte "
                + chunk.partNumber();
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

    private record TimeWindow(
            LocalDateTime start,
            LocalDateTime end
    ) {
    }

    private record StudyDay(
            LocalDate date,
            List<TimeWindow> windows
    ) {
    }

    private record AvailableSlot(
            LocalDateTime start,
            LocalDateTime end
    ) {
    }

    private record TopicChunk(
            int topicIndex,
            String topicTitle,
            int partNumber,
            int minutes,
            int totalTopicMinutes
    ) {
    }
}