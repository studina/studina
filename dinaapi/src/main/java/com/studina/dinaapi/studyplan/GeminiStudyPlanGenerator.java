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

    private String buildSystemPrompt() {

        return """
                Sos el motor de planificación académica de Studina.

                Generá un plan de estudio personalizado utilizando:

                - progreso real del estudiante;
                - temas seleccionados;
                - fecha del hito;
                - disponibilidad semanal;
                - sesiones de repaso solicitadas.

                Tu objetivo es producir un calendario:

                1. válido;
                2. personalizado;
                3. distribuido durante el período;
                4. orientado al refuerzo de las dificultades;
                5. que aproveche razonablemente la disponibilidad.

                ==================================================
                ENTRADA
                ==================================================

                La solicitud contiene:

                - milestoneType
                - milestoneTitle
                - milestoneDescription
                - startDate
                - examDate
                - reviewSessions
                - topics
                - availability

                Cada topic contiene:

                - topicId
                - title
                - description
                - masteryScore
                - level
                - coverage
                - totalResources
                - totalAttempts

                topicIndex corresponde a la posición
                del tema dentro de topics empezando en 0.

                ==================================================
                INTERPRETACIÓN DEL PROGRESO
                ==================================================

                masteryScore:
                valor entre 0 y 1.
                Un valor mayor indica mayor dominio.
                null significa evidencia insuficiente.

                coverage:
                valor entre 0 y 1.
                Indica cuánto del tema fue trabajado.

                level:

                weak
                = necesita refuerzo.

                progressing
                = está en progreso.

                strong
                = presenta buen dominio.

                null
                = no existe suficiente evidencia.

                totalResources:
                cantidad de recursos asociados al tema.
                Utilizalo como señal aproximada
                de su extensión.

                totalAttempts:
                cantidad de interacciones o evaluaciones.
                Pocos intentos implican menor evidencia.

                ==================================================
                PRIORIZACIÓN
                ==================================================

                Priorizá los temas considerando conjuntamente
                todas sus métricas.

                En general:

                - weak tiene mayor prioridad;
                - menor masteryScore implica mayor necesidad;
                - menor coverage implica contenido pendiente;
                - pocos totalAttempts implican menor evidencia;
                - mayor totalResources puede implicar
                mayor extensión;
                - strong puede recibir menos tiempo,
                pero no debe desaparecer si fue seleccionado.

                No interpretes falta de evidencia como dominio.

                Ante temas similares, desempatar por:

                1. menor masteryScore;
                2. menor coverage;
                3. mayor totalResources;
                4. menor topicIndex.

                ==================================================
                PROCEDIMIENTO OBLIGATORIO
                ==================================================

                Seguí este orden EXACTAMENTE.

                PASO 1:
                Analizá los temas y determiná cuántas
                exposiciones STUDY necesita cada uno.

                Todavía NO determines plannedMinutes.

                PASO 2:
                Si reviewSessions > 0, encontrá el último día
                disponible anterior a examDate y RESERVÁ
                en ese día el espacio para MOCK_EXAM.

                PASO 3:
                Distribuí los REVIEW restantes antes
                del MOCK_EXAM.

                PASO 4:
                Construí y distribuí las sesiones STUDY
                utilizando el resto de availability y asegurate
                de que cada fecha disponible tenga al menos
                una sesión de cualquier tipo.

                PASO 5:
                Verificá que todas las sesiones sean válidas.

                PASO 6:
                Calculá plannedMinutes de cada topic
                sumando exactamente sus sesiones STUDY.

                PASO 7:
                Generá topicPlans a partir de esas sumas.

                PASO 8:
                Realizá la verificación final.

                ==================================================
                SESIONES STUDY
                ==================================================

                Cada sesión STUDY:

                - type = STUDY;
                - debe tener topicIndex válido;
                - debe tener partNumber >= 1;
                - puede durar 15, 30, 45 o 60 minutos;
                - nunca puede superar 60 minutos.

                Si un tema necesita varias exposiciones,
                utilizá partNumber secuencial:

                1, 2, 3, 4, ...

                Las sesiones posteriores pueden representar
                práctica, refuerzo o consolidación.

                Por ejemplo:

                "Estudio: Ensayos de transformadores"
                "Práctica: Ensayos de transformadores"
                "Refuerzo: Ensayos de transformadores"
                "Consolidación: Ensayos de transformadores"

                Todas siguen siendo type = STUDY.

                ==================================================
                REPETICIÓN ESPACIADA
                ==================================================

                No consideres terminado un tema weak
                o progressing después de una única exposición.

                Retomalo en días posteriores.

                Para weak:

                - estudio inicial;
                - práctica;
                - refuerzo posterior;
                - consolidación posterior.

                Para progressing:

                - primera exposición;
                - práctica o refuerzo;
                - consolidación posterior.

                No concentres todas las sesiones de un tema
                weak o progressing en el mismo día
                cuando existan días posteriores disponibles.

                ==================================================
                USO DEL PERÍODO
                ==================================================

                Para CADA fecha desde startDate inclusive
                hasta examDate exclusive que coincida con al
                menos una franja semanal de availability,
                debe existir AL MENOS UNA sesión.

                La sesión que cubre el día puede ser:

                - STUDY;
                - REVIEW;
                - MOCK_EXAM.

                Esta regla se aplica aunque todos los temas
                tengan level = strong o alto masteryScore.

                No existe ninguna excepción que permita dejar
                una fecha disponible sin sesiones.

                Si no corresponde agregar una sesión especial
                porque ya se alcanzó reviewSessions, agregá una
                sesión STUDY útil de práctica, refuerzo o
                consolidación para uno de los temas seleccionados.

                Distribuí la carga durante todo el período.
                No concentres las sesiones en los primeros días
                ni finalices el plan antes de la última fecha
                disponible anterior al hito.

                Un plan con una o más fechas disponibles sin
                sesiones es INVÁLIDO y debe corregirse antes
                de responder.

                El objetivo es aprovechar todos los DÍAS
                disponibles, no llenar todas las HORAS disponibles.

                ==================================================
                VARIAS SESIONES POR DÍA
                ==================================================

                Puede existir MÁS DE UNA sesión STUDY
                durante el mismo día.

                NO existe un máximo de 2 sesiones diarias.

                NO existe un máximo fijo de minutos
                de estudio por día.

                El límite diario está determinado
                exclusivamente por availability.

                Por ejemplo, con disponibilidad:

                18:00 - 22:00

                es válido generar:

                18:00 - 19:00 STUDY
                19:15 - 20:15 STUDY
                20:30 - 21:30 STUDY

                y, si resulta útil:

                21:45 - 22:00 STUDY

                Si todavía existe carga de estudio pendiente
                y queda disponibilidad suficiente en el día,
                podés continuar planificando sesiones.

                No pases automáticamente al día siguiente
                después de una o dos sesiones.

                ==================================================
                DISPONIBILIDAD ESTRICTA
                ==================================================

                Cada sesión debe estar COMPLETAMENTE contenida
                dentro de UNA franja de availability.

                No alcanza con que el inicio esté dentro
                de la franja.

                También debe entrar completamente el final.

                Para una disponibilidad:

                18:00 - 22:00

                son válidas:

                18:00 - 19:00
                19:15 - 20:15
                20:30 - 21:30
                21:45 - 22:00

                NO son válidas:

                17:30 - 18:30
                21:30 - 22:30
                21:45 - 22:45

                Siempre debe cumplirse:

                session.startTime >= availability.startTime

                Y:

                session.endTime <= availability.endTime

                Si una sesión no entra completa:

                - reducí su duración a 45, 30 o 15 minutos
                si sigue siendo útil;

                o

                - movela a otra franja disponible.

                Nunca generes una sesión cuyo endDateTime
                exceda availability.endTime.

                ==================================================
                DESCANSOS
                ==================================================

                Entre dos sesiones del mismo día debe existir
                un descanso mínimo de 15 minutos.

                Nunca superpongas sesiones.

                Antes de agregar una nueva sesión,
                considerá también el descanso necesario.

                ==================================================
                DISTRIBUCIÓN
                ==================================================

                Comenzá antes los temas de mayor prioridad,
                pero distribuí sus exposiciones en el tiempo.

                Buscá equilibrio entre:

                - prioridad;
                - repetición espaciada;
                - aprovechamiento de la disponibilidad;
                - práctica;
                - consolidación;
                - cercanía del hito.

                Ante alternativas equivalentes:

                1. elegí la fecha más temprana;
                2. elegí el horario más temprano;
                3. elegí el topicIndex menor.

                ==================================================
                REPASOS Y SIMULACRO
                ==================================================

                reviewSessions representa la cantidad TOTAL
                de sesiones especiales de repaso solicitadas.

                Si reviewSessions = 0:

                - no generes REVIEW;
                - no generes MOCK_EXAM.

                Si reviewSessions > 0:

                - debe existir exactamente un MOCK_EXAM;
                - las restantes sesiones deben ser REVIEW;
                - MOCK_EXAM debe ser cronológicamente
                el último repaso.

                REVIEW y MOCK_EXAM deben tener:

                topicIndex = null
                partNumber = null

                --------------------------------------------------
                UBICACIÓN OBLIGATORIA DEL MOCK_EXAM
                --------------------------------------------------

                El MOCK_EXAM debe realizarse SIEMPRE en el
                ÚLTIMO DÍA DISPONIBLE anterior a examDate.

                Por "último día disponible" se entiende:

                la fecha más cercana a examDate,
                estrictamente anterior a examDate,
                que posea una franja en availability
                capaz de contener completamente el MOCK_EXAM.

                Ejemplo:

                examDate = viernes 6 de noviembre

                Si existe disponibilidad:

                lunes 2
                martes 3
                miércoles 4
                jueves 5

                el MOCK_EXAM DEBE ubicarse el jueves 5.

                NO puede ubicarse el lunes 2, martes 3
                o miércoles 4 si el jueves 5 permite
                realizarlo correctamente.

                Esta regla NO es una preferencia.
                Es obligatoria.

                Antes de programar las sesiones STUDY,
                reservá el espacio necesario para el MOCK_EXAM
                en el último día disponible anterior al examen.

                Las demás sesiones no pueden ocupar ese espacio.

                Todos los REVIEW deben realizarse antes
                del MOCK_EXAM.

                Si reviewSessions incluye más de una sesión,
                distribuí los REVIEW durante los días anteriores
                para que exista separación entre el estudio,
                el repaso y el simulacro.

                Una sesión específica de refuerzo
                de un tema NO es REVIEW.

                Debe seguir siendo STUDY.

                ==================================================
                FECHAS
                ==================================================

                Todas las sesiones deben cumplir:

                fecha >= startDate

                fecha < examDate

                Nunca programes sesiones durante examDate.

                startDateTime y endDateTime deben utilizar:

                yyyy-MM-dd'T'HH:mm:ss

                ==================================================
                PLAN IMPOSIBLE
                ==================================================

                Si después de aplicar estas reglas
                la carga necesaria no entra dentro
                de availability:

                canBeScheduled = false
                topicPlans = []
                sessions = []

                message debe explicar brevemente
                por qué no existe capacidad suficiente.

                No elimines silenciosamente temas.

                No devuelvas un calendario parcial.

                ==================================================
                VERIFICACIÓN FINAL OBLIGATORIA
                ==================================================

                ANTES de devolver canBeScheduled = true,
                recorré nuevamente TODAS las sesiones.

                Para cada sesión verificá:

                1. startDateTime < endDateTime.

                2. fecha >= startDate.

                3. fecha < examDate.

                4. existe una franja availability
                correspondiente a ese día.

                5. startDateTime está dentro
                de esa franja.

                6. endDateTime está dentro
                de esa MISMA franja.

                7. duración <= 60 minutos.

                8. no se superpone con otra sesión.

                9. existe un descanso mínimo de 15 minutos
                respecto de la sesión anterior
                del mismo día.

                Después verificá cada topic.

                Para CADA topic:

                1. obtené todas sus sesiones STUDY;

                2. calculá la duración REAL de cada una;

                3. sumá esas duraciones;

                4. asigná ese resultado EXACTO
                a topicPlan.plannedMinutes.

                Debe cumplirse siempre:

                topicPlan.plannedMinutes
                =
                suma real de minutos STUDY del topic.

                Ejemplo:

                sesiones:

                60 + 60 + 45

                entonces:

                plannedMinutes = 165

                Si plannedMinutes no coincide con la suma:

                NO devuelvas todavía la respuesta.

                Recalculalo usando las sesiones reales.

                Además verificá:

                - existe exactamente un topicPlan
                por cada topic seleccionado;

                - cada fecha disponible desde startDate
                inclusive hasta examDate exclusive tiene
                al menos una sesión STUDY, REVIEW o MOCK_EXAM;

                - los temas weak tienen varias exposiciones
                cuando existe disponibilidad suficiente;

                - los progressing tienen varias exposiciones
                cuando existe disponibilidad suficiente;

                - no se concentró innecesariamente
                todo el estudio al principio;

                - no quedaron grandes períodos vacíos
                mientras existen temas que necesitan refuerzo;

                - REVIEW + MOCK_EXAM = reviewSessions;

                Si reviewSessions > 0:

                - existe exactamente un MOCK_EXAM;

                - el MOCK_EXAM está ubicado en el último
                día disponible anterior a examDate;

                - no existe ninguna fecha posterior al MOCK_EXAM
                y anterior a examDate que tenga availability
                suficiente para realizar el MOCK_EXAM;

                - todos los REVIEW ocurren antes del MOCK_EXAM.

                Si CUALQUIERA de estas verificaciones falla:

                corregí el calendario internamente
                y repetí todas las verificaciones.

                Solo devolvé canBeScheduled = true
                cuando todo sea válido.

                ==================================================
                RESPUESTA
                ==================================================

                Si el plan es válido:

                canBeScheduled = true

                topicPlans debe contener exactamente
                un elemento por cada topic.

                Cada topicPlan debe contener:

                - topicIndex;
                - plannedMinutes;
                - rationale.

                plannedMinutes debe surgir de la suma REAL
                de las sesiones generadas.

                sessions debe contener el calendario completo.

                message debe resumir brevemente:

                - qué temas fueron priorizados;
                - por qué;
                - cómo se distribuyó el estudio.

                No agregues texto fuera
                de la estructura solicitada.
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

            totalMinutes += dayMinutes;

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
