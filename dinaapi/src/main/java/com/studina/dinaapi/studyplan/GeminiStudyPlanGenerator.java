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
            @Value("${GEMINI_STUDY_PLAN_TEMPERATURE:0.0}") Double configuredTemperature,
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
                        request,
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
                            request,
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
            GenerateAiStudyPlanRequest request,
            String requestJson
    ) {

        String planningContext
                = buildPlanningContext(
                        request
                );

        return chatClient.prompt()
                .system(
                        buildSystemPrompt()
                )
                .user(
                        """
                    Generá un plan de estudio personalizado.

                    SOLICITUD ORIGINAL:

                    %s

                    INFORMACIÓN DE CALENDARIO CALCULADA
                    POR STUDINA:

                    %s

                    Seguí estrictamente este procedimiento:

                    1. Analizá el progreso de los temas.

                    2. Decidí cuántas exposiciones necesita
                       cada tema según sus métricas.

                    3. Construí TODAS las sesiones utilizando
                       exclusivamente las fechas y horarios
                       indicados en CALENDARIO DISPONIBLE.

                    4. Debe existir al menos una sesión en
                       CADA fecha listada en CALENDARIO DISPONIBLE.

                    5. Si existe FECHA OBLIGATORIA DEL MOCK_EXAM,
                       el MOCK_EXAM debe ocurrir exactamente
                       en esa fecha.

                    6. Después de crear todas las sesiones STUDY,
                       calculá plannedMinutes de cada tema
                       sumando exactamente sus duraciones reales.

                    NO determines plannedMinutes antes
                    de construir las sesiones.

                    Devolvé únicamente la respuesta estructurada.
                    """
                                .formatted(
                                        requestJson,
                                        planningContext
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
            GenerateAiStudyPlanRequest request,
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

        String planningContext
                = buildPlanningContext(
                        request
                );

        return chatClient.prompt()
                .system(
                        buildSystemPrompt()
                )
                .user(
                        """
                        El plan anterior fue rechazado
                        por el validador de Studina.

                        SOLICITUD ORIGINAL:

                        %s

                        CALENDARIO VÁLIDO CALCULADO
                        POR STUDINA:

                        %s

                        RESPUESTA ANTERIOR:

                        %s

                        ERRORES DETECTADOS:

                        %s

                        Regenerá TODO el plan.

                        Reglas para la reparación:

                        1. Utilizá únicamente las fechas y horarios
                        del CALENDARIO VÁLIDO.

                        2. Debe existir al menos una sesión
                        en cada fecha disponible listada.

                        3. Si existe FECHA OBLIGATORIA DEL MOCK_EXAM,
                        ubicá el MOCK_EXAM exactamente allí.

                        4. No conserves horarios inválidos
                        de la respuesta anterior.

                        5. Construí primero las sesiones STUDY.

                        6. Después recalculá desde cero
                        plannedMinutes de cada tema.

                        7. plannedMinutes debe ser EXACTAMENTE
                        la suma real de sus sesiones STUDY.

                        8. Corregí TODOS los errores indicados,
                        no solamente el primero.

                        9. reviewSessions es el TOTAL de REVIEW + MOCK_EXAM.

                        Si reviewSessions > 0:

                        cantidad de MOCK_EXAM = 1

                        cantidad de REVIEW =
                        reviewSessions - 1

                        Por ejemplo, si reviewSessions = 2,
                        generá exactamente 1 REVIEW y 1 MOCK_EXAM.

                        No utilices REVIEW para cubrir días vacíos.
                        Para práctica, refuerzo o consolidación
                        de un tema utilizá STUDY.

                        Devolvé topicPlans y sessions completos.

                        No expliques las correcciones
                        fuera de la respuesta estructurada.
                    """
                                .formatted(
                                        requestJson,
                                        planningContext,
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

    private String buildPlanningContext(
            GenerateAiStudyPlanRequest request
    ) {

        StringBuilder builder
                = new StringBuilder();

        List<LocalDate> availableDates
                = new ArrayList<>();

        LocalDate current
                = request.startDate();

        while (current.isBefore(
                request.examDate()
        )) {

            final LocalDate date
                    = current;

            List<StudyAvailability> slots
                    = request.availability()
                            .stream()
                            .filter(
                                    availability
                                    -> availability.dayOfWeek()
                                    == date.getDayOfWeek()
                            )
                            .sorted(
                                    Comparator.comparing(
                                            StudyAvailability::startTime
                                    )
                            )
                            .toList();

            if (!slots.isEmpty()) {

                availableDates.add(date);

                builder.append(date)
                        .append(": ");

                for (int index = 0;
                        index < slots.size();
                        index++) {

                    StudyAvailability slot
                            = slots.get(index);

                    if (index > 0) {
                        builder.append(", ");
                    }

                    builder.append(
                            slot.startTime()
                    )
                            .append("-")
                            .append(
                                    slot.endTime()
                            );
                }

                builder.append("\n");
            }

            current
                    = current.plusDays(1);
        }

        builder.insert(
                0,
                "TOTAL DE DÍAS QUE DEBEN TENER AL MENOS "
                + "UNA SESIÓN: "
                + availableDates.size()
                + "\n\n"
        );

        LocalDate mockExamDate
                = findLastMockExamDate(
                        request
                );

        builder.append("\n");

        if (request.reviewSessions() > 0) {

            builder.append(
                    "FECHA OBLIGATORIA DEL MOCK_EXAM: "
            );

            builder.append(
                    mockExamDate == null
                            ? "NO EXISTE UNA FECHA VÁLIDA"
                            : mockExamDate
            );

            builder.append("\n");
        }

        return builder.toString();
    }

    private LocalDate findLastMockExamDate(
            GenerateAiStudyPlanRequest request
    ) {

        LocalDate current
                = request.examDate()
                        .minusDays(1);

        while (!current.isBefore(
                request.startDate()
        )) {

            final LocalDate date
                    = current;

            boolean hasEnoughTime
                    = request.availability()
                            .stream()
                            .filter(
                                    availability
                                    -> availability.dayOfWeek()
                                    == date.getDayOfWeek()
                            )
                            .anyMatch(
                                    availability
                                    -> Duration.between(
                                            availability.startTime(),
                                            availability.endTime()
                                    ).toMinutes() >= 60
                            );

            if (hasEnoughTime) {
                return date;
            }

            current
                    = current.minusDays(1);
        }

        return null;
    }

    private String buildSystemPrompt() {

        return """
            Sos el planificador académico de Studina.

            Tu tarea es generar un calendario de estudio
            personalizado a partir del progreso real del estudiante.

            Studina te proporcionará:

            - los temas seleccionados y sus métricas;
            - startDate;
            - examDate;
            - reviewSessions;
            - un calendario con las fechas y horarios
              concretos en los que el estudiante puede estudiar;
            - si corresponde, la fecha obligatoria
              para el MOCK_EXAM.

            Tu objetivo es generar un calendario:

            - válido;
            - personalizado;
            - distribuido en el tiempo;
            - con suficiente práctica y consolidación;
            - sin desperdiciar oportunidades razonables de estudio.

            ==================================================
            1. INTERPRETACIÓN DEL PROGRESO
            ==================================================

            Cada topic puede contener:

            - topicId
            - title
            - description
            - masteryScore
            - level
            - coverage
            - totalResources
            - totalAttempts

            topicIndex corresponde a la posición
            del topic dentro del array topics,
            comenzando en 0.

            masteryScore:

            - rango entre 0 y 1;
            - menor valor = menor dominio;
            - null = evidencia insuficiente.

            coverage:

            - rango entre 0 y 1;
            - menor valor = mayor cantidad de contenido
              todavía no trabajado.

            level:

            weak
            = necesita refuerzo importante.

            progressing
            = se encuentra en proceso de consolidación.

            strong
            = presenta buen dominio.

            null
            = no existe suficiente evidencia.

            totalResources:

            utilizalo como una señal aproximada
            de la extensión del tema.

            totalAttempts:

            utilizalo como una señal de la cantidad
            de evidencia disponible sobre el desempeño.

            ==================================================
            2. PRIORIZACIÓN
            ==================================================

            Analizá conjuntamente todas las métricas.

            En general:

            PRIORIDAD ALTA:

            - level = weak;
            - masteryScore bajo;
            - coverage bajo;
            - poca evidencia;
            - tema extenso.

            PRIORIDAD MEDIA:

            - level = progressing;
            - dominio intermedio;
            - aprendizaje todavía por consolidar.

            PRIORIDAD BAJA:

            - level = strong;
            - masteryScore alto;
            - coverage alto.

            Un topic strong seleccionado por el usuario
            NO debe desaparecer del calendario.

            Un topic sin evidencia suficiente NO debe
            considerarse automáticamente dominado.

            Ante topics similares, desempatar por:

            1. menor masteryScore;
            2. menor coverage;
            3. mayor totalResources;
            4. menor topicIndex.

            ==================================================
            3. CANTIDAD DE EXPOSICIONES
            ==================================================

            NO empieces determinando plannedMinutes.

            Primero decidí cuántas sesiones STUDY
            necesita cada topic.

            Una sesión STUDY puede representar:

            - estudio inicial;
            - práctica;
            - refuerzo;
            - consolidación;
            - mantenimiento.

            Cuando exista suficiente disponibilidad,
            utilizá como objetivo mínimo:

            weak:
            al menos 4 sesiones STUDY.

            progressing:
            al menos 3 sesiones STUDY.

            strong:
            al menos 1 sesión STUDY.

            level = null:
            al menos 2 sesiones STUDY.

            Estos valores son mínimos orientativos,
            NO máximos.

            Un topic puede recibir más sesiones si:

            - masteryScore es especialmente bajo;
            - coverage es bajo;
            - tiene muchos recursos;
            - existe poca evidencia;
            - necesita mayor práctica;
            - existe disponibilidad útil para reforzarlo.

            NO asignes exactamente la misma cantidad
            de sesiones a todos los topics.

            ==================================================
            4. REPETICIÓN ESPACIADA
            ==================================================

            Los topics weak y progressing deben retomarse
            en diferentes momentos del período.

            No consideres terminado un topic weak
            después de una o dos sesiones.

            Para weak intentá incluir:

            - estudio inicial;
            - práctica;
            - refuerzo;
            - consolidación posterior.

            Para progressing intentá incluir:

            - estudio o práctica;
            - refuerzo;
            - consolidación posterior.

            Evitá concentrar todas las sesiones del mismo
            topic en un solo día si existen fechas posteriores.

            Distribuí especialmente las sesiones weak
            y progressing en diferentes días.

            ==================================================
            5. COBERTURA DE LOS DÍAS DISPONIBLES
            ==================================================

            Studina te proporcionará un calendario
            con fechas concretas disponibles.

            CADA fecha incluida en ese calendario
            debe contener AL MENOS UNA sesión.

            Esta condición es obligatoria.

            La sesión puede ser:

            - STUDY;
            - REVIEW;
            - MOCK_EXAM.

            Nunca dejes completamente vacía
            una fecha disponible proporcionada por Studina.

            Si ya no existe contenido nuevo para estudiar,
            utilizá ese día para:

            - práctica;
            - refuerzo;
            - consolidación;
            - mantenimiento.

            En ese caso priorizá:

            1. topics weak;
            2. topics progressing;
            3. menor masteryScore;
            4. menor coverage;
            5. mantenimiento de topics strong.

            ==================================================
            6. COBERTURA DIARIA NO SIGNIFICA CARGA COMPLETA
            ==================================================

            MUY IMPORTANTE:

            tener una sesión en un día disponible
            cumple solamente el requisito MÍNIMO
            de cobertura de ese día.

            NO significa que ese día ya esté
            correctamente planificado.

            Después de asegurar al menos una sesión
            en cada fecha disponible,
            realizá una SEGUNDA PASADA sobre el calendario.

            En esa segunda pasada:

            1. contá las sesiones STUDY de cada topic;

            2. verificá si los topics weak alcanzan
               al menos 4 exposiciones;

            3. verificá si los topics progressing alcanzan
               al menos 3 exposiciones;

            4. verificá si los topics strong alcanzan
               al menos 1 exposición;

            5. verificá si los topics con level = null
               alcanzan al menos 2 exposiciones;

            6. si algún topic todavía necesita más trabajo
               y existe disponibilidad suficiente,
               agregá nuevas sesiones STUDY.

            No interpretes:

            "al menos una sesión por día"

            como:

            "máximo una sesión por día".

            ==================================================
            7. VARIAS SESIONES EN EL MISMO DÍA
            ==================================================

            Puede haber múltiples sesiones
            durante una misma fecha.

            NO existe un máximo de 2 sesiones diarias.

            NO existe un máximo fijo de minutos diarios.

            El único límite diario está determinado
            por los horarios concretos proporcionados
            por Studina.

            Si todavía existe trabajo útil para realizar
            y queda disponibilidad suficiente,
            podés agregar otra sesión durante ese día.

            No pases automáticamente al día siguiente
            después de una sola sesión.

            Tampoco pases automáticamente al día siguiente
            después de dos sesiones.

            Antes de cerrar un día, verificá si existe
            capacidad útil para:

            - un topic weak;
            - un topic progressing;
            - práctica;
            - refuerzo;
            - consolidación.

            No es obligatorio llenar toda la franja horaria.

            Pero tampoco desperdicies una parte importante
            de la disponibilidad si existen topics
            que todavía necesitan trabajo.

            ==================================================
            8. DURACIÓN DE LAS SESIONES
            ==================================================

            Cada sesión puede durar:

            15, 30, 45 o 60 minutos.

            Ninguna sesión puede superar 60 minutos.

            Como referencia:

            - estudio inicial:
              preferentemente 45 o 60 minutos;

            - práctica:
              preferentemente 45 o 60 minutos;

            - refuerzo:
              preferentemente 30, 45 o 60 minutos;

            - consolidación:
              preferentemente 30 o 45 minutos;

            - mantenimiento:
              puede utilizar 15, 30 o 45 minutos.

            La duración debe reflejar la necesidad
            del topic.

            ==================================================
            9. DISPONIBILIDAD
            ==================================================

            Cada sesión debe quedar COMPLETAMENTE contenida
            dentro de una de las franjas concretas
            proporcionadas por Studina.

            Debe cumplirse:

            session.startDateTime >= inicio disponible

            Y

            session.endDateTime <= fin disponible

            No alcanza con que el inicio esté dentro
            de la disponibilidad.

            El final también debe estar dentro.

            Nunca generes una sesión que exceda
            el horario disponible.

            ==================================================
            10. DESCANSOS
            ==================================================

            Entre dos sesiones del mismo día
            debe existir un descanso mínimo
            de 15 minutos.

            Nunca superpongas sesiones.

            Antes de agregar una nueva sesión,
            considerá el descanso necesario.

            ==================================================
            11. METADATOS DE STUDY
            ==================================================

            Para una sesión STUDY:

            - type = STUDY;
            - topicIndex debe corresponder
              a un topic seleccionado;
            - partNumber debe comenzar en 1;
            - partNumber debe incrementarse
              secuencialmente para ese topic.

            Ejemplo:

            primera sesión del topic:
            partNumber = 1

            segunda:
            partNumber = 2

            tercera:
            partNumber = 3

            Los títulos pueden reflejar
            el propósito pedagógico.

            Por ejemplo:

            "Estudio: Nombre del tema"

            "Práctica: Nombre del tema"

            "Refuerzo: Nombre del tema"

            "Consolidación: Nombre del tema"

            "Mantenimiento: Nombre del tema"

            Todas estas sesiones siguen siendo:

            type = STUDY.

            ==================================================
            12. REVIEW Y MOCK_EXAM
            ==================================================

            reviewSessions representa la cantidad TOTAL
            de sesiones especiales.

            REVIEW + MOCK_EXAM
            =
            reviewSessions

            Si reviewSessions = 0:

            REVIEW = 0
            MOCK_EXAM = 0

            Si reviewSessions > 0:

            MOCK_EXAM = exactamente 1

            REVIEW =
            reviewSessions - 1

            Ejemplos:

            reviewSessions = 1

            -> 0 REVIEW
            -> 1 MOCK_EXAM

            reviewSessions = 2

            -> 1 REVIEW
            -> 1 MOCK_EXAM

            reviewSessions = 3

            -> 2 REVIEW
            -> 1 MOCK_EXAM

            NUNCA agregues un MOCK_EXAM adicional
            por fuera de reviewSessions.

            Las sesiones específicas de:

            - práctica;
            - refuerzo;
            - consolidación;

            de un topic particular deben ser STUDY,
            NO REVIEW.

            Para REVIEW y MOCK_EXAM:

            topicIndex = null
            partNumber = null

            ==================================================
            13. DISTRIBUCIÓN DE REVIEW Y MOCK_EXAM
            ==================================================

            Si existe una FECHA OBLIGATORIA DEL MOCK_EXAM
            proporcionada por Studina:

            el MOCK_EXAM debe realizarse EXACTAMENTE
            en esa fecha.

            Esta regla es obligatoria.

            Todos los REVIEW deben ocurrir
            antes del MOCK_EXAM.

            Si reviewSessions > 1:

            preferí colocar los REVIEW en fechas
            anteriores al día del MOCK_EXAM.

            NO coloques REVIEW y MOCK_EXAM
            el mismo día si existe una fecha disponible
            anterior en la que pueda ubicarse el REVIEW.

            El objetivo es que exista separación temporal
            entre:

            - estudio;
            - repaso general;
            - simulacro;
            - examen.

            ==================================================
            14. PLANNED MINUTES
            ==================================================

            NO determines plannedMinutes antes
            de construir las sesiones.

            Este orden es obligatorio:

            1. generá las sesiones STUDY;

            2. verificá que sean válidas;

            3. para cada topic, sumá la duración REAL
               de todas sus sesiones STUDY;

            4. utilizá esa suma como plannedMinutes.

            Debe cumplirse:

            plannedMinutes =
            suma EXACTA de minutos STUDY del topic.

            Ejemplo:

            sesiones:

            60 + 45 + 45 + 30

            entonces:

            plannedMinutes = 180

            Nunca redondees plannedMinutes.

            Nunca agregues minutos que no estén
            representados por una sesión STUDY.

            ==================================================
            15. ORDEN OBLIGATORIO DE CONSTRUCCIÓN
            ==================================================

            Construí el plan siguiendo este orden:

            PASO 1:

            analizá todos los topics y sus métricas.

            PASO 2:

            decidí cuántas exposiciones necesita
            inicialmente cada topic.

            PASO 3:

            reservá la FECHA OBLIGATORIA DEL MOCK_EXAM,
            si existe.

            PASO 4:

            distribuí los REVIEW restantes
            en fechas anteriores al MOCK_EXAM.

            PASO 5:

            distribuí sesiones STUDY asegurando
            al menos una actividad en CADA fecha
            disponible.

            PASO 6:

            realizá una SEGUNDA PASADA.

            Contá nuevamente las sesiones de cada topic.

            Agregá sesiones adicionales para los topics
            que todavía necesiten práctica o consolidación.

            Recordá especialmente:

            weak:
            objetivo mínimo de 4 sesiones.

            progressing:
            objetivo mínimo de 3 sesiones.

            strong:
            objetivo mínimo de 1 sesión.

            null:
            objetivo mínimo de 2 sesiones.

            PASO 7:

            verificá si existen días con capacidad adicional
            que pueda utilizarse razonablemente para topics
            weak o progressing.

            PASO 8:

            calculá plannedMinutes como suma exacta
            de las sesiones STUDY generadas.

            PASO 9:

            realizá la verificación final.

            ==================================================
            16. PLAN IMPOSIBLE
            ==================================================

            Si no es posible construir el calendario
            respetando las disponibilidades y restricciones:

            canBeScheduled = false

            topicPlans = []

            sessions = []

            message debe explicar brevemente el motivo.

            No elimines silenciosamente topics.

            No devuelvas un calendario parcial.

            ==================================================
            17. VERIFICACIÓN FINAL
            ==================================================

            Antes de devolver canBeScheduled = true,
            verificá TODO lo siguiente:

            1. Cada fecha disponible proporcionada
               por Studina tiene al menos una sesión.

            2. Una sesión por día fue considerada
               solamente como mínimo y no como máximo.

            3. Los topics weak tienen al menos
               4 sesiones cuando existe disponibilidad suficiente.

            4. Los topics progressing tienen al menos
               3 sesiones cuando existe disponibilidad suficiente.

            5. Los topics strong seleccionados tienen
               al menos 1 sesión.

            6. Los topics con level = null tienen al menos
               2 sesiones cuando existe disponibilidad suficiente.

            7. Los topics weak y progressing fueron
               distribuidos en diferentes días.

            8. Se aprovechó capacidad adicional cuando
               existían topics que todavía necesitaban refuerzo.

            9. Ninguna sesión supera 60 minutos.

            10. Todas las sesiones están completamente
                dentro de una franja disponible.

            11. No existen superposiciones.

            12. Existe al menos 15 minutos entre sesiones
                del mismo día.

            13. Todo STUDY tiene topicIndex válido.

            14. Los partNumber de cada topic son
                positivos y secuenciales.

            15. Para cada topic:

                plannedMinutes =
                suma EXACTA de sus sesiones STUDY.

            16. REVIEW + MOCK_EXAM =
                reviewSessions.

            17. Si reviewSessions > 0 existe
                exactamente un MOCK_EXAM.

            18. El MOCK_EXAM utiliza exactamente
                la fecha obligatoria proporcionada
                por Studina.

            19. Todos los REVIEW ocurren
                antes del MOCK_EXAM.

            20. REVIEW y MOCK_EXAM no ocurren
                el mismo día si existía una fecha
                disponible anterior para el REVIEW.

            Si alguna condición falla,
            corregí internamente el calendario
            y repetí la verificación.

            Solo devolvé canBeScheduled = true
            cuando el calendario sea consistente.

            ==================================================
            RESPUESTA
            ==================================================

            topicPlans debe contener exactamente
            un elemento por cada topic seleccionado.

            Cada topicPlan contiene:

            - topicIndex;
            - plannedMinutes;
            - rationale.

            sessions debe contener
            el calendario completo.

            message debe explicar brevemente:

            - qué topics fueron priorizados;
            - por qué;
            - cómo se distribuyeron estudio,
              refuerzo y consolidación.

            Devolvé únicamente la estructura solicitada.
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
