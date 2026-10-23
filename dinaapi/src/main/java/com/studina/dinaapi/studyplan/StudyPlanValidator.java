package com.studina.dinaapi.studyplan;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class StudyPlanValidator {

    private static final int MAX_SESSION_MINUTES = 60;
    private static final int MAX_DAILY_MINUTES = 120;
    private static final int MIN_BREAK_MINUTES = 15;

    public StudyPlanValidationResult validate(
            GenerateStudyPlanRequest request,
            GeminiStudyPlanResponse response
    ) {

        List<String> errors =
                new ArrayList<>();

        if (response == null) {
            return StudyPlanValidationResult.failure(
                    List.of(
                            "Gemini no devolvió ninguna respuesta."
                    )
            );
        }

        List<GeminiStudySession> sessions =
                response.sessions() == null
                        ? List.of()
                        : response.sessions();

        /*
         * Si Gemini considera que el plan
         * no puede generarse, no debe devolver
         * sesiones parciales.
         */
        if (!response.canBeScheduled()) {

            if (!sessions.isEmpty()) {
                errors.add(
                        "Si canBeScheduled es false, sessions debe estar vacío."
                );
            }

            if (
                    response.message() == null
                            || response.message().isBlank()
            ) {
                errors.add(
                        "Si el plan no puede generarse, message debe explicar el motivo."
                );
            }

            return errors.isEmpty()
                    ? StudyPlanValidationResult.success()
                    : StudyPlanValidationResult.failure(
                            errors
                    );
        }

        if (sessions.isEmpty()) {

            errors.add(
                    "canBeScheduled es true pero no se generaron sesiones."
            );

            return StudyPlanValidationResult.failure(
                    errors
            );
        }

        List<ParsedSession> parsedSessions =
                new ArrayList<>();

        Map<Integer, Integer> studyMinutesByTopic =
                new HashMap<>();

        for (
                int index = 0;
                index < sessions.size();
                index++
        ) {

            GeminiStudySession session =
                    sessions.get(index);

            String label =
                    "Sesión " + (index + 1);

            if (session == null) {
                errors.add(
                        label + ": la sesión es null."
                );
                continue;
            }

            if (
                    session.title() == null
                            || session.title().isBlank()
            ) {
                errors.add(
                        label + ": title es obligatorio."
                );
            }

            if (session.type() == null) {
                errors.add(
                        label + ": type es obligatorio."
                );
            }

            LocalDateTime start =
                    parseDateTime(
                            session.startDateTime(),
                            label + ".startDateTime",
                            errors
                    );

            LocalDateTime end =
                    parseDateTime(
                            session.endDateTime(),
                            label + ".endDateTime",
                            errors
                    );

            if (
                    start == null
                            || end == null
            ) {
                continue;
            }

            if (!start.isBefore(end)) {

                errors.add(
                        label
                                + ": startDateTime debe ser anterior a endDateTime."
                );

                continue;
            }

            if (
                    !start.toLocalDate()
                            .equals(
                                    end.toLocalDate()
                            )
            ) {
                errors.add(
                        label
                                + ": una sesión no puede cruzar de día."
                );
            }

            if (
                    start.toLocalDate()
                            .isBefore(
                                    request.startDate()
                            )
                            || !start.toLocalDate()
                                    .isBefore(
                                            request.examDate()
                                    )
            ) {

                errors.add(
                        label
                                + ": la fecha debe estar entre startDate inclusive y examDate exclusive."
                );
            }

            long duration =
                    Duration.between(
                            start,
                            end
                    ).toMinutes();

            if (
                    duration
                            > MAX_SESSION_MINUTES
            ) {
                errors.add(
                        label
                                + ": dura "
                                + duration
                                + " minutos; el máximo es "
                                + MAX_SESSION_MINUTES
                                + "."
                );
            }

            if (
                    !isInsideAvailability(
                            request,
                            start,
                            end
                    )
            ) {

                errors.add(
                        label
                                + ": está fuera de la disponibilidad indicada por el estudiante."
                );
            }

            validateSessionMetadata(
                    request,
                    session,
                    label,
                    errors
            );

            /*
             * Sumamos cuánto estudio real
             * recibió cada topic.
             */
            if (
                    session.type()
                            == StudySessionType.STUDY
                            && session.topicIndex()
                                    != null
                            && session.topicIndex()
                                    >= 0
                            && session.topicIndex()
                                    < request.topics()
                                            .size()
            ) {

                studyMinutesByTopic.merge(
                        session.topicIndex(),
                        Math.toIntExact(
                                duration
                        ),
                        Integer::sum
                );
            }

            parsedSessions.add(
                    new ParsedSession(
                            session,
                            start,
                            end,
                            Math.toIntExact(
                                    duration
                            )
                    )
            );
        }

        validateTopicMinutes(
                request,
                studyMinutesByTopic,
                errors
        );

        validateDailyLimitsAndBreaks(
                parsedSessions,
                errors
        );

        validateReviewSessions(
                request,
                parsedSessions,
                errors
        );

        return errors.isEmpty()
                ? StudyPlanValidationResult.success()
                : StudyPlanValidationResult.failure(
                        errors
                );
    }

    private LocalDateTime parseDateTime(
            String value,
            String field,
            List<String> errors
    ) {

        if (
                value == null
                        || value.isBlank()
        ) {

            errors.add(
                    field + " es obligatorio."
            );

            return null;
        }

        try {

            return LocalDateTime.parse(
                    value
            );

        } catch (
                DateTimeParseException exception
        ) {

            errors.add(
                    field
                            + " debe usar formato ISO local yyyy-MM-dd'T'HH:mm:ss."
            );

            return null;
        }
    }

    private boolean isInsideAvailability(
            GenerateStudyPlanRequest request,
            LocalDateTime start,
            LocalDateTime end
    ) {

        return request.availability()
                .stream()
                .anyMatch(
                        availability ->
                                availability.dayOfWeek()
                                        == start.getDayOfWeek()
                                        && !start.toLocalTime()
                                                .isBefore(
                                                        availability.startTime()
                                                )
                                        && !end.toLocalTime()
                                                .isAfter(
                                                        availability.endTime()
                                                )
                );
    }

    private void validateSessionMetadata(
            GenerateStudyPlanRequest request,
            GeminiStudySession session,
            String label,
            List<String> errors
    ) {

        if (session.type() == null) {
            return;
        }

        if (
                session.type()
                        == StudySessionType.STUDY
        ) {

            if (
                    session.topicIndex()
                            == null
            ) {

                errors.add(
                        label
                                + ": una sesión STUDY debe tener topicIndex."
                );

            } else if (
                    session.topicIndex()
                            < 0
                            || session.topicIndex()
                                    >= request.topics()
                                            .size()
            ) {

                errors.add(
                        label
                                + ": topicIndex no corresponde a un tema existente."
                );
            }

            if (
                    session.partNumber()
                            == null
                            || session.partNumber()
                                    <= 0
            ) {

                errors.add(
                        label
                                + ": una sesión STUDY debe tener partNumber >= 1."
                );
            }

        } else {

            if (
                    session.topicIndex()
                            != null
            ) {

                errors.add(
                        label
                                + ": REVIEW/MOCK_EXAM debe tener topicIndex null."
                );
            }

            if (
                    session.partNumber()
                            != null
            ) {

                errors.add(
                        label
                                + ": REVIEW/MOCK_EXAM debe tener partNumber null."
                );
            }
        }
    }

    private void validateTopicMinutes(
            GenerateStudyPlanRequest request,
            Map<Integer, Integer> studyMinutesByTopic,
            List<String> errors
    ) {

        for (
                int topicIndex = 0;
                topicIndex
                        < request.topics()
                                .size();
                topicIndex++
        ) {

            StudyTopic topic =
                    request.topics()
                            .get(
                                    topicIndex
                            );

            int actualMinutes =
                    studyMinutesByTopic
                            .getOrDefault(
                                    topicIndex,
                                    0
                            );

            if (
                    actualMinutes
                            != topic.estimatedMinutes()
            ) {

                errors.add(
                        "El tema "
                                + topicIndex
                                + " ("
                                + topic.title()
                                + ") necesita "
                                + topic.estimatedMinutes()
                                + " minutos de STUDY, pero Gemini asignó "
                                + actualMinutes
                                + "."
                );
            }
        }
    }

    private void validateDailyLimitsAndBreaks(
            List<ParsedSession> sessions,
            List<String> errors
    ) {

        Map<LocalDate, List<ParsedSession>> byDate =
                new HashMap<>();

        for (
                ParsedSession session
                : sessions
        ) {

            byDate.computeIfAbsent(
                    session.start()
                            .toLocalDate(),
                    ignored ->
                            new ArrayList<>()
            ).add(
                    session
            );
        }

        for (
                Map.Entry<
                        LocalDate,
                        List<ParsedSession>> entry
                : byDate.entrySet()
        ) {

            List<ParsedSession> daySessions =
                    entry.getValue();

            daySessions.sort(
                    Comparator.comparing(
                            ParsedSession::start
                    )
            );

            int totalMinutes =
                    daySessions.stream()
                            .mapToInt(
                                    ParsedSession
                                            ::durationMinutes
                            )
                            .sum();

            if (
                    totalMinutes
                            > MAX_DAILY_MINUTES
            ) {

                errors.add(
                        "El día "
                                + entry.getKey()
                                + " tiene "
                                + totalMinutes
                                + " minutos programados; el máximo diario es "
                                + MAX_DAILY_MINUTES
                                + "."
                );
            }

            for (
                    int index = 1;
                    index
                            < daySessions.size();
                    index++
            ) {

                ParsedSession previous =
                        daySessions.get(
                                index - 1
                        );

                ParsedSession current =
                        daySessions.get(
                                index
                        );

                long gapMinutes =
                        Duration.between(
                                previous.end(),
                                current.start()
                        ).toMinutes();

                if (
                        gapMinutes
                                < MIN_BREAK_MINUTES
                ) {

                    errors.add(
                            "El día "
                                    + entry.getKey()
                                    + " debe haber al menos "
                                    + MIN_BREAK_MINUTES
                                    + " minutos entre sesiones."
                    );
                }
            }
        }
    }

    private void validateReviewSessions(
            GenerateStudyPlanRequest request,
            List<ParsedSession> sessions,
            List<String> errors
    ) {

        List<ParsedSession> reviews =
                sessions.stream()
                        .filter(
                                session ->
                                        session.session()
                                                .type()
                                                == StudySessionType.REVIEW
                                                || session.session()
                                                        .type()
                                                        == StudySessionType.MOCK_EXAM
                        )
                        .sorted(
                                Comparator.comparing(
                                        ParsedSession::start
                                )
                        )
                        .toList();

        if (
                reviews.size()
                        != request.reviewSessions()
        ) {

            errors.add(
                    "Se solicitaron "
                            + request.reviewSessions()
                            + " sesiones de repaso, pero Gemini generó "
                            + reviews.size()
                            + "."
            );
        }

        if (
                request.reviewSessions()
                        == 0
        ) {
            return;
        }

        long mockCount =
                reviews.stream()
                        .filter(
                                session ->
                                        session.session()
                                                .type()
                                                == StudySessionType.MOCK_EXAM
                        )
                        .count();

        if (mockCount != 1) {

            errors.add(
                    "Debe existir exactamente un MOCK_EXAM cuando reviewSessions es mayor que cero."
            );
        }

        if (
                !reviews.isEmpty()
                        && reviews.get(
                                        reviews.size() - 1
                                )
                                .session()
                                .type()
                                != StudySessionType.MOCK_EXAM
        ) {

            errors.add(
                    "La última sesión de repaso debe ser MOCK_EXAM."
            );
        }
    }

    private record ParsedSession(
            GeminiStudySession session,
            LocalDateTime start,
            LocalDateTime end,
            int durationMinutes
    ) {
    }
}