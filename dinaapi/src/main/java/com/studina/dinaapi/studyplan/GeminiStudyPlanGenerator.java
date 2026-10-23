package com.studina.dinaapi.studyplan;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class GeminiStudyPlanGenerator {

    private static final int MAX_DAILY_MINUTES = 120;

    private final ChatClient chatClient;
    private final StudyPlanValidator validator;

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    @Value("${GEMINI_MODEL}")
    private String configuredModel;

    @Value("${GEMINI_STUDY_PLAN_TEMPERATURE:0.1}")
    private Double configuredTemperature;

    @Value("${GEMINI_STUDY_PLAN_MAX_REPAIR_ATTEMPTS:2}")
    private int maxRepairAttempts;

    public GeminiStudyPlanGenerator(
            ChatClient.Builder chatClientBuilder,
            StudyPlanValidator validator
    ) {
        this.chatClient =
                chatClientBuilder.build();

        this.validator =
                validator;
    }

    public GeneratedStudyPlan generate(
            GenerateStudyPlanRequest request
    ) {

        validateRequest(
                request
        );

        String requestJson =
                serialize(
                        request
                );

        GeminiStudyPlanResponse response =
                requestInitialPlan(
                        requestJson
                );

        StudyPlanValidationResult validation =
                validator.validate(
                        request,
                        response
                );

        int repairAttempt = 0;

        while (
                !validation.valid()
                        && repairAttempt < maxRepairAttempts
        ) {

            repairAttempt++;

            response =
                    requestRepairedPlan(
                            requestJson,
                            response,
                            validation.errors()
                    );

            validation =
                    validator.validate(
                            request,
                            response
                    );
        }

        if (!validation.valid()) {

            throw new IllegalStateException(
                    "Gemini no pudo generar un calendario válido. "
                            + String.join(
                                    " | ",
                                    validation.errors()
                            )
            );
        }

        return mapResponse(
                request,
                response
        );
    }

    private GeminiStudyPlanResponse requestInitialPlan(
            String requestJson
    ) {

        return chatClient.prompt()
                .system(
                        buildSystemPrompt()
                )
                .user(
                        """
                        Generá el calendario de estudio
                        para esta solicitud.

                        SOLICITUD:

                        %s

                        Devolvé únicamente el calendario
                        solicitado respetando estrictamente
                        las reglas del sistema.
                        """.formatted(
                                requestJson
                        )
                )
                .options(
                        GoogleGenAiChatOptions.builder()
                                .model(
                                        configuredModel
                                )
                                .temperature(
                                        configuredTemperature
                                )
                )
                .call()
                .entity(
                        GeminiStudyPlanResponse.class
                );
    }

    private GeminiStudyPlanResponse requestRepairedPlan(
            String requestJson,
            GeminiStudyPlanResponse previousResponse,
            List<String> errors
    ) {

        String previousResponseJson =
                serialize(
                        previousResponse
                );

        String validationErrors =
                errors.stream()
                        .map(
                                error ->
                                        "- " + error
                        )
                        .reduce(
                                "",
                                (left, right) ->
                                        left
                                                + right
                                                + "\n"
                        );

        return chatClient.prompt()
                .system(
                        buildSystemPrompt()
                )
                .user(
                        """
                        El calendario anterior NO es válido.

                        Tenés que regenerarlo completo
                        corrigiendo todos los errores.

                        SOLICITUD ORIGINAL:

                        %s

                        CALENDARIO ANTERIOR:

                        %s

                        ERRORES DETECTADOS POR STUDINA:

                        %s

                        Generá nuevamente TODO el calendario.

                        No expliques los errores.
                        No devuelvas una corrección parcial.
                        Devolvé el calendario completo corregido.
                        """.formatted(
                                requestJson,
                                previousResponseJson,
                                validationErrors
                        )
                )
                .options(
                        GoogleGenAiChatOptions.builder()
                                .model(
                                        configuredModel
                                )
                                .temperature(
                                        configuredTemperature
                                )
                )
                .call()
                .entity(
                        GeminiStudyPlanResponse.class
                );
    }

    private String buildSystemPrompt() {

        return """
                Sos el motor inteligente de planificación
                académica de Studina.

                Tu tarea es GENERAR el calendario de estudio
                del estudiante.

                Recibís:

                - startDate;
                - examDate;
                - topics;
                - estimatedMinutes por tema;
                - availability semanal;
                - reviewSessions.

                VOS tenés que decidir:

                - qué día estudiar cada tema;
                - en qué horario;
                - cómo dividir temas largos;
                - cómo distribuir las sesiones;
                - cuándo ubicar repasos;
                - cuándo ubicar el simulacro.

                REGLAS OBLIGATORIAS

                1. Todas las sesiones deben ocurrir
                   desde startDate inclusive
                   hasta examDate exclusive.

                2. Solo podés utilizar días y horarios
                   incluidos en availability.

                3. Ninguna sesión puede durar
                   más de 60 minutos.

                4. No programes más de 120 minutos
                   totales por día.

                5. Si existen varias sesiones
                   el mismo día, debe haber al menos
                   15 minutos de descanso entre ellas.

                6. Para cada tema, la suma de minutos
                   de todas sus sesiones STUDY debe
                   coincidir EXACTAMENTE con
                   estimatedMinutes.

                7. Si estimatedMinutes es mayor
                   a 60, dividí el tema en varias
                   sesiones.

                8. Distribuí el estudio durante
                   todo el período disponible.

                9. Evitá concentrar todas las sesiones
                   al principio o justo antes
                   del examen.

                10. Intercalá diferentes temas
                    cuando sea razonable.

                11. topicIndex representa la posición
                    del tema dentro del array topics.

                    Primer tema = 0
                    Segundo tema = 1
                    etc.

                12. Toda sesión STUDY debe incluir:

                    topicIndex válido
                    partNumber >= 1

                13. Si un tema tiene varias partes,
                    partNumber debe identificar
                    el orden de las partes.

                14. REVIEW y MOCK_EXAM deben tener:

                    topicIndex = null
                    partNumber = null

                15. reviewSessions representa
                    la cantidad TOTAL de sesiones
                    destinadas a repaso.

                16. Si reviewSessions es mayor que 0:

                    debe existir exactamente
                    un MOCK_EXAM;

                    el resto deben ser REVIEW;

                    el MOCK_EXAM debe ser
                    la última sesión de repaso.

                17. Los repasos deben distribuirse
                    razonablemente durante el período.

                18. Los tipos permitidos son
                    exactamente:

                    STUDY
                    REVIEW
                    MOCK_EXAM

                19. startDateTime y endDateTime
                    deben utilizar formato ISO local:

                    yyyy-MM-dd'T'HH:mm:ss

                20. No inventes disponibilidad.

                21. No programes sesiones después
                    del examen.

                22. Preferí duraciones de:

                    15
                    30
                    45
                    60 minutos

                    cuando sea posible.

                23. Si las restricciones hacen imposible
                    estudiar todos los temas:

                    canBeScheduled = false
                    sessions = []
                    message debe explicar brevemente
                    el motivo.

                24. Si el calendario es posible:

                    canBeScheduled = true

                    sessions debe contener
                    el calendario COMPLETO.

                El resultado debe ser realista,
                distribuido y pedagógicamente razonable.
                """;
    }

    private GeneratedStudyPlan mapResponse(
            GenerateStudyPlanRequest request,
            GeminiStudyPlanResponse aiResponse
    ) {

        int requiredMinutes =
                request.topics()
                        .stream()
                        .mapToInt(
                                StudyTopic::estimatedMinutes
                        )
                        .sum();

        int availableMinutes =
                calculateAvailableMinutes(
                        request
                );

        if (!aiResponse.canBeScheduled()) {

            return new GeneratedStudyPlan(
                    false,
                    requiredMinutes,
                    availableMinutes,
                    Math.max(
                            0,
                            requiredMinutes
                                    - availableMinutes
                    ),
                    List.of(),
                    aiResponse.message() == null
                            || aiResponse.message()
                                    .isBlank()
                            ? "Gemini determinó que no es posible generar el calendario."
                            : aiResponse.message()
            );
        }

        List<GeminiStudySession> aiSessions =
                aiResponse.sessions() == null
                        ? List.of()
                        : aiResponse.sessions();

        List<StudySession> sessions =
                new ArrayList<>();

        for (
                GeminiStudySession aiSession
                : aiSessions
        ) {

            LocalDateTime start =
                    LocalDateTime.parse(
                            aiSession.startDateTime()
                    );

            LocalDateTime end =
                    LocalDateTime.parse(
                            aiSession.endDateTime()
                    );

            sessions.add(
                    new StudySession(
                            aiSession.title(),
                            start,
                            end,
                            aiSession.type(),
                            aiSession.topicIndex(),
                            aiSession.partNumber()
                    )
            );
        }

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
                List.copyOf(
                        sessions
                ),
                aiResponse.message() == null
                        || aiResponse.message()
                                .isBlank()
                        ? "El calendario fue generado por Gemini."
                        : aiResponse.message()
        );
    }

    private int calculateAvailableMinutes(
            GenerateStudyPlanRequest request
    ) {

        int totalMinutes = 0;

        LocalDate current =
                request.startDate();

        while (
                current.isBefore(
                        request.examDate()
                )
        ) {

            final LocalDate date =
                    current;

            int dayMinutes =
                    request.availability()
                            .stream()
                            .filter(
                                    availability ->
                                            availability.dayOfWeek()
                                                    == date.getDayOfWeek()
                            )
                            .mapToInt(
                                    availability ->
                                            Math.toIntExact(
                                                    Duration.between(
                                                            availability.startTime(),
                                                            availability.endTime()
                                                    ).toMinutes()
                                            )
                            )
                            .sum();

            totalMinutes +=
                    Math.min(
                            dayMinutes,
                            MAX_DAILY_MINUTES
                    );

            current =
                    current.plusDays(1);
        }

        return totalMinutes;
    }

    private String serialize(
            Object value
    ) {

        try {

            return objectMapper
                    .writeValueAsString(
                            value
                    );

        } catch (
                JsonProcessingException exception
        ) {

            throw new IllegalStateException(
                    "No se pudo serializar información para Gemini.",
                    exception
            );
        }
    }

    private void validateRequest(
            GenerateStudyPlanRequest request
    ) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "La solicitud es obligatoria."
            );
        }

        if (
                request.startDate() == null
                        || request.examDate() == null
        ) {
            throw new IllegalArgumentException(
                    "Las fechas son obligatorias."
            );
        }

        if (
                !request.startDate()
                        .isBefore(
                                request.examDate()
                        )
        ) {
            throw new IllegalArgumentException(
                    "La fecha de inicio debe ser anterior al examen."
            );
        }

        if (
                request.topics() == null
                        || request.topics().isEmpty()
        ) {
            throw new IllegalArgumentException(
                    "Debe existir al menos un tema."
            );
        }

        for (
                StudyTopic topic
                : request.topics()
        ) {

            if (
                    topic == null
                            || topic.title() == null
                            || topic.title().isBlank()
            ) {
                throw new IllegalArgumentException(
                        "Todos los temas deben tener nombre."
                );
            }

            if (
                    topic.estimatedMinutes()
                            <= 0
            ) {
                throw new IllegalArgumentException(
                        "Todos los temas deben tener una duración mayor que cero."
                );
            }
        }

        if (
                request.availability() == null
                        || request.availability().isEmpty()
        ) {
            throw new IllegalArgumentException(
                    "Debe existir al menos una franja de disponibilidad."
            );
        }

        for (
                StudyAvailability availability
                : request.availability()
        ) {

            if (
                    availability == null
                            || availability.dayOfWeek() == null
                            || availability.startTime() == null
                            || availability.endTime() == null
            ) {
                throw new IllegalArgumentException(
                        "La disponibilidad contiene datos incompletos."
                );
            }

            if (
                    !availability.startTime()
                            .isBefore(
                                    availability.endTime()
                            )
            ) {
                throw new IllegalArgumentException(
                        "El inicio de una franja debe ser anterior a su fin."
                );
            }
        }

        if (
                request.reviewSessions()
                        < 0
        ) {
            throw new IllegalArgumentException(
                    "La cantidad de repasos no puede ser negativa."
            );
        }
    }
}