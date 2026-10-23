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

    /*
     * Lo creamos internamente en lugar de
     * inyectarlo como bean de Spring.
     *
     * findAndRegisterModules() permite serializar
     * correctamente LocalDate, LocalTime, etc.
     */
    private final ObjectMapper objectMapper
            = new ObjectMapper()
                    .findAndRegisterModules();

    private final StudyPlanValidator validator;

    private final String configuredModel;
    private final Double configuredTemperature;
    private final int maxRepairAttempts;

    public GeminiStudyPlanGenerator(
            ChatClient.Builder builder,
            StudyPlanValidator validator,
            @Value("${GEMINI_MODEL}") String configuredModel,
            @Value("${GEMINI_STUDY_PLAN_TEMPERATURE:0.1}") Double configuredTemperature,
            @Value("${GEMINI_STUDY_PLAN_MAX_REPAIR_ATTEMPTS:2}") int maxRepairAttempts
    ) {

        this.chatClient
                = builder.build();

        this.validator
                = validator;

        this.configuredModel
                = configuredModel;

        this.configuredTemperature
                = configuredTemperature;

        this.maxRepairAttempts
                = maxRepairAttempts;
    }

    public GeneratedStudyPlan generate(
            GenerateAiStudyPlanRequest request
    ) {

        validateRequest(
                request
        );

        /*
         * Serializamos toda la solicitud:
         *
         * - hito;
         * - fechas;
         * - disponibilidad;
         * - topics;
         * - métricas de progreso.
         *
         * Todo esto llega a Gemini.
         */
        String requestJson
                = serialize(
                        request
                );

        /*
         * Primer intento:
         *
         * Gemini decide:
         *
         * 1. cuántos minutos necesita cada tema;
         * 2. cómo repartir esos minutos;
         * 3. cuándo estudiar;
         * 4. repasos;
         * 5. simulacro.
         */
        GeminiStudyPlanResponse response
                = requestInitialPlan(
                        requestJson
                );

        /*
         * Studina NO genera el calendario.
         *
         * Solo valida que la salida generada
         * por Gemini respete las reglas duras.
         */
        StudyPlanValidationResult validation
                = validator.validate(
                        request,
                        response
                );

        int repairAttempt = 0;

        /*
         * Si Gemini produjo un calendario inválido,
         * le devolvemos:
         *
         * - la solicitud original;
         * - su calendario anterior;
         * - los errores concretos detectados.
         *
         * Gemini debe regenerar TODO el plan.
         */
        while (!validation.valid()
                && repairAttempt
                < maxRepairAttempts) {

            repairAttempt++;

            response
                    = requestRepairedPlan(
                            requestJson,
                            response,
                            validation.errors()
                    );

            validation
                    = validator.validate(
                            request,
                            response
                    );
        }

        /*
         * No existe fallback manual.
         *
         * Si Gemini no logró producir una salida
         * válida después de los intentos permitidos,
         * la generación falla.
         */
        if (!validation.valid()) {

            throw new IllegalStateException(
                    "Gemini no pudo generar un calendario válido. "
                    + String.join(
                            " | ",
                            validation.errors()
                    )
            );
        }

        /*
         * Convertimos la respuesta de Gemini
         * al formato que ya entiende el frontend.
         */
        return mapResponse(
                request,
                response
        );
    }

    /*
     * ========================================================
     * PRIMERA GENERACIÓN
     * ========================================================
     */
    private GeminiStudyPlanResponse requestInitialPlan(
            String requestJson
    ) {

        return chatClient.prompt()
                .system(
                        buildSystemPrompt()
                )
                .user(
                        """
                        Generá un plan de estudio personalizado
                        para la siguiente solicitud.

                        SOLICITUD:

                        %s

                        Primero analizá el progreso de cada tema
                        y decidí cuánto tiempo de estudio necesita.

                        Después construí el calendario completo
                        respetando estrictamente todas las reglas
                        indicadas en el mensaje de sistema.

                        Devolvé únicamente la respuesta estructurada
                        solicitada.
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

    /*
     * ========================================================
     * REPARACIÓN AUTOMÁTICA
     * ========================================================
     */
    private GeminiStudyPlanResponse requestRepairedPlan(
            String requestJson,
            GeminiStudyPlanResponse previousResponse,
            List<String> errors
    ) {

        String previousResponseJson
                = serialize(
                        previousResponse
                );

        String validationErrors
                = errors.stream()
                        .map(
                                error
                                -> "- " + error
                        )
                        .reduce(
                                "",
                                (left, right)
                                -> left
                                + right
                                + "\n"
                        );

        return chatClient.prompt()
                .system(
                        buildSystemPrompt()
                )
                .user(
                        """
                        El plan de estudio anterior NO es válido.

                        Debés regenerarlo COMPLETO corrigiendo
                        todos los errores detectados por Studina.

                        SOLICITUD ORIGINAL:

                        %s

                        RESPUESTA ANTERIOR DE GEMINI:

                        %s

                        ERRORES DETECTADOS:

                        %s

                        Importante:

                        - revisá nuevamente la decisión de plannedMinutes;
                        - respetá las métricas de progreso;
                        - corregí todas las sesiones inválidas;
                        - no devuelvas solamente los elementos modificados;
                        - devolvé nuevamente topicPlans completo;
                        - devolvé nuevamente sessions completo;
                        - no expliques la corrección fuera de los campos
                          de la respuesta.

                        Regenerá ahora el plan completo.
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

    /*
     * ========================================================
     * PROMPT DEL SISTEMA
     * ========================================================
     */
    private String buildSystemPrompt() {

        return """
                Sos el motor inteligente de planificación
                académica de Studina.

                Tu tarea es GENERAR un plan de estudio
                personalizado para un estudiante a partir
                de su progreso real, el hito académico,
                los temas seleccionados y su disponibilidad.

                NO recibís una duración predeterminada
                para cada tema.

                VOS debés decidir:

                - qué temas necesitan mayor prioridad;
                - cuánto tiempo dedicar a cada tema;
                - cuántas sesiones necesita cada tema;
                - qué día estudiar cada tema;
                - en qué horario estudiar;
                - cómo dividir temas largos;
                - cómo distribuir el estudio en el tiempo;
                - cuándo ubicar repasos;
                - cuándo ubicar el simulacro final.

                ==================================================
                INFORMACIÓN RECIBIDA
                ==================================================

                Recibís una solicitud que contiene:

                - milestoneType:
                  tipo de hito académico.

                - milestoneTitle:
                  nombre del examen, parcial, entrega
                  u objetivo del estudiante.

                - milestoneDescription:
                  información adicional sobre el hito.

                - startDate:
                  fecha desde la cual se puede estudiar.

                - examDate:
                  fecha del hito académico.

                - topics:
                  temas que el usuario seleccionó
                  explícitamente para preparar.

                - availability:
                  días y horarios en los que el estudiante
                  puede estudiar.

                - reviewSessions:
                  cantidad total de sesiones destinadas
                  a repaso, incluyendo el simulacro final.

                ==================================================
                INFORMACIÓN DE CADA TEMA
                ==================================================

                Cada elemento de topics contiene:

                - topicId:
                  identificador del tema.

                - title:
                  nombre del tema.

                - description:
                  descripción del contenido del tema.
                  Puede estar vacía.

                - masteryScore:
                  valor entre 0 y 1 que representa
                  el dominio demostrado por el estudiante.

                  Valores más altos indican mayor dominio.

                  Puede ser null si todavía no existe
                  suficiente evidencia de aprendizaje.

                - coverage:
                  valor entre 0 y 1 que representa
                  cuánto del tema fue trabajado.

                  0 significa que prácticamente
                  no fue trabajado.

                  1 significa que existe evidencia
                  sobre la totalidad del tema.

                - level:
                  clasificación del estado de aprendizaje.

                  Puede tomar estos valores:

                  null
                  = sin evidencia suficiente o sin empezar.

                  weak
                  = necesita refuerzo.

                  progressing
                  = se encuentra en progreso.

                  strong
                  = presenta buen dominio.

                - totalResources:
                  cantidad de recursos de aprendizaje
                  asociados al tema.

                  Puede utilizarse como una señal aproximada
                  de la extensión o amplitud del contenido.

                - totalAttempts:
                  cantidad de interacciones, respuestas
                  o evaluaciones registradas sobre el tema.

                  Un valor bajo implica menor evidencia
                  para evaluar el dominio del estudiante.

                ==================================================
                PRIMERA TAREA: DECIDIR LA CARGA DE ESTUDIO
                ==================================================

                Antes de ubicar sesiones en el calendario,
                analizá cada tema y decidí cuánto tiempo
                de estudio necesita.

                Para CADA tema seleccionado debés devolver
                exactamente un elemento en topicPlans.

                Cada topicPlan debe contener:

                - topicIndex:
                  índice del tema dentro del array topics.

                  Primer tema = 0
                  Segundo tema = 1
                  Tercer tema = 2
                  etc.

                - plannedMinutes:
                  cantidad TOTAL de minutos de estudio
                  que considerás adecuada para ese tema.

                - rationale:
                  explicación breve de por qué asignaste
                  esa cantidad de tiempo.

                plannedMinutes NO viene dado por el sistema.

                Es una decisión que debés tomar utilizando:

                - masteryScore;
                - coverage;
                - level;
                - totalResources;
                - totalAttempts;
                - descripción del tema;
                - tiempo disponible hasta el hito;
                - disponibilidad semanal del estudiante;
                - relación entre los distintos temas.

                ==================================================
                CRITERIOS PEDAGÓGICOS
                ==================================================

                Utilizá las métricas como señales,
                no como reglas matemáticas rígidas.

                En general:

                - un tema con level = weak debería recibir
                  mayor atención;

                - un tema con masteryScore bajo debería
                  recibir más tiempo;

                - un tema con coverage bajo debería recibir
                  más tiempo porque todavía existe contenido
                  poco trabajado;

                - un tema sin evidencia suficiente
                  no debe interpretarse como dominado;

                - un tema con level = strong puede recibir
                  menos tiempo;

                - un tema dominado NO debe desaparecer
                  completamente si el usuario lo seleccionó;

                - una cantidad alta de recursos puede indicar
                  que el tema posee mayor extensión;

                - pocos totalAttempts implican menor evidencia,
                  por lo que conviene evitar asumir que
                  el estudiante domina el tema;

                - no asignes exactamente la misma cantidad
                  de minutos a todos los temas salvo que
                  las métricas realmente lo justifiquen.

                Priorizá especialmente los temas que:

                - necesitan refuerzo;
                - presentan bajo dominio;
                - tienen poca cobertura;
                - tienen poca evidencia;
                - son amplios y contienen varios recursos.

                plannedMinutes debe ser mayor que cero.

                Preferí, cuando resulte razonable,
                cantidades en múltiplos de 15 minutos:

                30
                45
                60
                75
                90
                105
                120

                No existe un máximo rígido de minutos
                por tema.

                Un tema complejo o con mucha necesidad
                puede requerir varias sesiones distribuidas
                en diferentes días.

                ==================================================
                SEGUNDA TAREA: CONSTRUIR EL CALENDARIO
                ==================================================

                Una vez definidos los plannedMinutes
                de todos los temas, construí sessions.

                La suma de las sesiones STUDY asociadas
                a cada topicIndex debe coincidir EXACTAMENTE
                con plannedMinutes del topicPlan correspondiente.

                Ejemplo:

                topicPlan:

                topicIndex = 0
                plannedMinutes = 90

                Una planificación válida podría ser:

                sesión STUDY de 60 minutos
                +
                sesión STUDY de 30 minutos

                Total = 90 minutos.

                ==================================================
                REGLAS OBLIGATORIAS DEL CALENDARIO
                ==================================================

                1. Todas las sesiones deben ocurrir
                   desde startDate inclusive
                   hasta examDate exclusive.

                2. Nunca programes una sesión
                   en examDate.

                3. Solo podés utilizar días y horarios
                   incluidos en availability.

                4. No inventes disponibilidad adicional.

                5. Ninguna sesión individual puede durar
                   más de 60 minutos.

                6. No programes más de 120 minutos
                   totales de sesiones por día.

                7. Si existen varias sesiones el mismo día,
                   debe haber al menos 15 minutos
                   de descanso entre ellas.

                8. Una sesión no puede cruzar
                   de un día al siguiente.

                9. Distribuí el estudio durante
                   todo el período disponible.

                10. Evitá concentrar todas las sesiones
                    durante los primeros días.

                11. Evitá dejar todo el estudio
                    inmediatamente antes del examen.

                12. Intercalá temas diferentes
                    cuando sea pedagógicamente razonable.

                13. Los temas que necesitan más refuerzo
                    deberían comenzar a trabajarse
                    con suficiente anticipación.

                ==================================================
                SESIONES STUDY
                ==================================================

                Para una sesión de tipo STUDY:

                - type debe ser exactamente STUDY;

                - topicIndex debe corresponder
                  al índice de un tema existente;

                - partNumber debe ser un entero
                  mayor o igual a 1.

                Si un mismo tema tiene varias sesiones:

                partNumber debe reflejar su orden.

                Ejemplo:

                Parte 1 -> partNumber = 1
                Parte 2 -> partNumber = 2
                Parte 3 -> partNumber = 3

                El título de la sesión debe ser claro
                y relacionado con el tema.

                ==================================================
                REPASOS Y SIMULACRO
                ==================================================

                reviewSessions representa la cantidad TOTAL
                de sesiones destinadas a repaso.

                Si reviewSessions = 0:

                - no generes REVIEW;
                - no generes MOCK_EXAM.

                Si reviewSessions > 0:

                - debe existir exactamente un MOCK_EXAM;

                - las restantes sesiones deben ser REVIEW;

                - el MOCK_EXAM debe ser la última
                  sesión de repaso cronológicamente;

                - distribuí los REVIEW durante el período
                  en lugar de colocarlos todos juntos.

                Para sesiones REVIEW y MOCK_EXAM:

                - topicIndex debe ser null;
                - partNumber debe ser null.

                Los repasos pueden integrar varios temas,
                especialmente aquellos con mayor necesidad.

                ==================================================
                TIPOS DE SESIÓN
                ==================================================

                Los únicos valores válidos para type son:

                STUDY
                REVIEW
                MOCK_EXAM

                No utilices ningún otro valor.

                ==================================================
                FECHAS Y HORARIOS
                ==================================================

                startDateTime y endDateTime deben utilizar
                formato ISO local:

                yyyy-MM-dd'T'HH:mm:ss

                Ejemplo:

                2026-10-24T18:00:00

                ==================================================
                PLAN IMPOSIBLE
                ==================================================

                Antes de declarar que el plan es imposible,
                intentá distribuir razonablemente el estudio
                utilizando toda la disponibilidad existente.

                Si realmente no existe capacidad suficiente
                para generar un calendario válido:

                - canBeScheduled = false;
                - sessions = [];
                - topicPlans puede ser [];
                - message debe explicar brevemente
                  por qué el plan no puede realizarse.

                No elimines silenciosamente temas que
                el usuario seleccionó.

                No reduzcas arbitrariamente el contenido
                únicamente para forzar que el plan entre.

                ==================================================
                PLAN POSIBLE
                ==================================================

                Si el calendario puede generarse:

                - canBeScheduled = true;

                - topicPlans debe contener exactamente
                  una decisión para cada tema;

                - sessions debe contener el calendario
                  completo;

                - todas las decisiones de plannedMinutes
                  deben verse reflejadas en las sesiones;

                - message debe contener un resumen breve
                  y útil del criterio general utilizado.

                ==================================================
                OBJETIVO
                ==================================================

                El resultado debe ser:

                - personalizado;
                - realista;
                - pedagógicamente razonable;
                - coherente con el progreso registrado;
                - compatible con la disponibilidad;
                - distribuido en el tiempo;
                - priorizado según las necesidades
                  reales del estudiante.

                No trates todos los temas de manera uniforme.

                El progreso del estudiante debe afectar
                claramente las decisiones del plan.
                """;
    }

    /*
     * ========================================================
     * MAPEO AL RESPONSE DEL FRONTEND
     * ========================================================
     */
    private GeneratedStudyPlan mapResponse(
            GenerateAiStudyPlanRequest request,
            GeminiStudyPlanResponse aiResponse
    ) {

        /*
         * Ahora requiredMinutes YA NO viene
         * de Java ni del frontend.
         *
         * Es la suma de los minutos que
         * decidió Gemini.
         */
        int requiredMinutes
                = aiResponse.topicPlans() == null
                ? 0
                : aiResponse.topicPlans()
                        .stream()
                        .mapToInt(
                                GeminiTopicPlan::plannedMinutes
                        )
                        .sum();

        int availableMinutes
                = calculateAvailableMinutes(
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
                    aiResponse.message()
                    == null
                    || aiResponse.message()
                            .isBlank()
                            ? "Gemini determinó que no es posible generar el calendario con la disponibilidad indicada."
                            : aiResponse.message()
            );
        }

        List<GeminiStudySession> aiSessions
                = aiResponse.sessions() == null
                ? List.of()
                : aiResponse.sessions();

        List<StudySession> sessions
                = new ArrayList<>();

        for (GeminiStudySession aiSession
                : aiSessions) {

            LocalDateTime start
                    = LocalDateTime.parse(
                            aiSession.startDateTime()
                    );

            LocalDateTime end
                    = LocalDateTime.parse(
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

        /*
         * Aunque Gemini debería devolverlas
         * cronológicamente, ordenamos el response
         * final por seguridad/presentación.
         *
         * Esto NO genera ni modifica las sesiones.
         */
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
                aiResponse.message()
                == null
                || aiResponse.message()
                        .isBlank()
                        ? "El calendario personalizado fue generado por Gemini."
                        : aiResponse.message()
        );
    }

    /*
     * ========================================================
     * CÁLCULO DE CAPACIDAD
     * ========================================================
     *
     * Esto NO decide cuánto estudiar.
     *
     * Solamente calcula cuántos minutos máximos
     * existen en la disponibilidad ingresada,
     * para mostrar la métrica al usuario.
     */
    private int calculateAvailableMinutes(
            GenerateAiStudyPlanRequest request
    ) {

        int totalMinutes = 0;

        LocalDate current
                = request.startDate();

        while (current.isBefore(
                request.examDate()
        )) {

            final LocalDate date
                    = current;

            int dayMinutes
                    = request.availability()
                            .stream()
                            .filter(
                                    availability
                                    -> availability.dayOfWeek()
                                    == date.getDayOfWeek()
                            )
                            .mapToInt(
                                    availability
                                    -> Math.toIntExact(
                                            Duration.between(
                                                    availability.startTime(),
                                                    availability.endTime()
                                            ).toMinutes()
                                    )
                            )
                            .sum();

            /*
             * El prompt impone 120 min diarios,
             * así que la capacidad mostrada sigue
             * la misma restricción.
             */
            totalMinutes
                    += Math.min(
                            dayMinutes,
                            MAX_DAILY_MINUTES
                    );

            current
                    = current.plusDays(1);
        }

        return totalMinutes;
    }

    /*
     * ========================================================
     * SERIALIZACIÓN
     * ========================================================
     */
    private String serialize(
            Object value
    ) {

        try {

            return objectMapper
                    .writeValueAsString(
                            value
                    );

        } catch (JsonProcessingException exception) {

            throw new IllegalStateException(
                    "No se pudo serializar información para Gemini.",
                    exception
            );
        }
    }

    /*
     * ========================================================
     * VALIDACIÓN DEL REQUEST
     * ========================================================
     *
     * Solamente validamos la entrada.
     *
     * NO calculamos prioridades ni minutos.
     */
    private void validateRequest(
            GenerateAiStudyPlanRequest request
    ) {

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
                .isBefore(
                        request.examDate()
                )) {
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

        for (AiStudyTopic topic
                : request.topics()) {

            if (topic == null) {
                throw new IllegalArgumentException(
                        "Los temas no pueden ser null."
                );
            }

            if (topic.title() == null
                    || topic.title()
                            .isBlank()) {
                throw new IllegalArgumentException(
                        "Todos los temas deben tener nombre."
                );
            }

            if (topic.masteryScore() != null
                    && (topic.masteryScore() < 0
                    || topic.masteryScore() > 1)) {
                throw new IllegalArgumentException(
                        "masteryScore debe estar entre 0 y 1."
                );
            }

            if (topic.coverage() != null
                    && (topic.coverage() < 0
                    || topic.coverage() > 1)) {
                throw new IllegalArgumentException(
                        "coverage debe estar entre 0 y 1."
                );
            }

            if (topic.totalResources() < 0) {
                throw new IllegalArgumentException(
                        "totalResources no puede ser negativo."
                );
            }

            if (topic.totalAttempts() < 0) {
                throw new IllegalArgumentException(
                        "totalAttempts no puede ser negativo."
                );
            }
        }

        if (request.availability() == null
                || request.availability()
                        .isEmpty()) {
            throw new IllegalArgumentException(
                    "Debe existir al menos una franja de disponibilidad."
            );
        }

        for (StudyAvailability availability
                : request.availability()) {

            if (availability == null
                    || availability.dayOfWeek()
                    == null
                    || availability.startTime()
                    == null
                    || availability.endTime()
                    == null) {
                throw new IllegalArgumentException(
                        "La disponibilidad contiene datos incompletos."
                );
            }

            if (!availability.startTime()
                    .isBefore(
                            availability.endTime()
                    )) {
                throw new IllegalArgumentException(
                        "El inicio de una franja debe ser anterior a su fin."
                );
            }
        }

        if (request.reviewSessions()
                < 0) {
            throw new IllegalArgumentException(
                    "La cantidad de repasos no puede ser negativa."
            );
        }
    }
}
