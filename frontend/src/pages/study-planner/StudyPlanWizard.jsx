import {
    useEffect,
    useMemo,
    useState,
} from "react";

import WizardProgress from "./components/WizardProgress";
import "./StudyPlanWizard.css";

import MilestoneTopicsStep from "./components/MilestoneTopicsStep";
import DatesStep from "./components/DatesStep";
import AvailabilityStep from "./components/AvailabilityStep";
import PlanSummaryStep from "./components/PlanSummaryStep";
import GeneratedPlanView from "./components/GeneratedPlanView";

import {
    generateStudyPlan,
    getStudyPlanTopics,
} from "../../services/studyPlanService";

import dinaStudyPlanner from "../../assets/dina/dina-study-planner.png";

const STEP_CONTENT = {
    1: {
        title: "Hito y temas",
        description:
            "Definí qué querés alcanzar y seleccioná los temas de la materia que forman parte de ese objetivo.",
    },

    2: {
        title: "Fechas del plan",
        description:
            "Configurá cuándo querés comenzar y cuál es la fecha del examen, entrega o hito.",
    },

    3: {
        title: "Disponibilidad y sesiones",
        description:
            "Indicá qué días y horarios tenés disponibles para estudiar.",
    },

    4: {
        title: "Resumen",
        description:
            "Revisá toda la configuración antes de generar el plan.",
    },
};

export default function StudyPlanWizard({
    subject,
    onExit,
}) {
    const subjectId =
        subject?.id ??
        subject?.subjectId ??
        subject?.subject_id;

    /*
     * Navegación del wizard
     */
    const [currentStep, setCurrentStep] =
        useState(1);

    const currentContent =
        STEP_CONTENT[currentStep];

    /*
     * Hito
     */
    const [milestone, setMilestone] =
        useState({
            type: "",
            title: "",
            description: "",
        });

    /*
     * Topics reales de la materia.
     */
    const [topics, setTopics] =
        useState([]);

    const [loadingTopics, setLoadingTopics] =
        useState(false);

    const [
        topicsLoadError,
        setTopicsLoadError,
    ] = useState("");

    /*
     * Fechas
     */
    const [startDate, setStartDate] =
        useState("");

    const [examDate, setExamDate] =
        useState("");

    /*
     * TEMPORAL:
     *
     * todavía existe en el flujo actual.
     * Más adelante podemos hacer que Gemini
     * decida también automáticamente los repasos.
     */
    const [
        reviewSessions,
        setReviewSessions,
    ] = useState(2);

    /*
     * Disponibilidad
     */
    const [availability, setAvailability] =
        useState([]);

    /*
     * Errores
     */
    const [stepError, setStepError] =
        useState("");

    const [
        generationError,
        setGenerationError,
    ] = useState("");

    /*
     * Resultado
     */
    const [planResult, setPlanResult] =
        useState(null);

    const [
        generatingPlan,
        setGeneratingPlan,
    ] = useState(false);

    /*
     * Topics efectivamente elegidos
     * para este hito.
     */
    const selectedTopics =
        useMemo(
            () =>
                topics.filter(
                    (topic) =>
                        topic.selected
                ),
            [topics]
        );

    /*
     * Carga automática de topics y métricas
     * desde el progreso de la materia.
     */
    useEffect(() => {
        if (!subjectId) {
            return;
        }

        let cancelled = false;

        const loadTopics = async () => {
            setLoadingTopics(true);
            setTopicsLoadError("");

            try {
                const overview =
                    await getStudyPlanTopics(
                        subjectId
                    );

                if (cancelled) {
                    return;
                }

                const loadedTopics =
                    (
                        overview?.topics ?? []
                    ).map(
                        (topic) => ({
                            id:
                                topic.topic_id,

                            title:
                                topic.topic_name ??
                                "Tema sin nombre",

                            description:
                                topic.description ??
                                "",

                            masteryScore:
                                topic.mastery_score ??
                                null,

                            level:
                                topic.level ??
                                null,

                            coverage:
                                topic.coverage ??
                                0,

                            totalResources:
                                topic.total_resources ??
                                0,

                            totalAttempts:
                                topic.total_attempts ??
                                0,

                            /*
                             * Todos entran inicialmente
                             * en el hito.
                             */
                            selected: true,

                            /*
                             * COMPATIBILIDAD TEMPORAL.
                             *
                             * El backend actual todavía
                             * espera estimatedMinutes.
                             *
                             * El usuario ya NO decide
                             * este valor.
                             *
                             * En el siguiente cambio
                             * backend lo eliminamos y
                             * Gemini va a decidir los
                             * minutos según las métricas.
                             */
                            estimatedMinutes: 60,
                        })
                    );

                setTopics(
                    loadedTopics
                );

            } catch (exception) {

                if (cancelled) {
                    return;
                }

                setTopicsLoadError(
                    exception instanceof Error
                        ? exception.message
                        : "No se pudieron cargar los temas de la materia."
                );

            } finally {

                if (!cancelled) {
                    setLoadingTopics(false);
                }
            }
        };

        loadTopics();

        return () => {
            cancelled = true;
        };

    }, [subjectId]);

    /*
     * Hito
     */
    const updateMilestone = (
        field,
        value
    ) => {
        setMilestone(
            (currentMilestone) => ({
                ...currentMilestone,
                [field]: value,
            })
        );

        setStepError("");
    };

    /*
     * Selección de topics.
     *
     * Ya no se crean o eliminan manualmente.
     */
    const toggleTopic = (topicId) => {

        setTopics(
            (currentTopics) =>
                currentTopics.map(
                    (topic) =>
                        topic.id === topicId
                            ? {
                                ...topic,
                                selected:
                                    !topic.selected,
                            }
                            : topic
                )
        );

        setStepError("");
    };

    /*
     * Validación paso 1
     */
    const validateFirstStep = () => {

        if (!milestone.type) {
            return (
                "Seleccioná el tipo de hito."
            );
        }

        if (!milestone.title.trim()) {
            return (
                "Ingresá el nombre del hito."
            );
        }

        if (loadingTopics) {
            return (
                "Esperá a que terminen de cargar los temas."
            );
        }

        if (topicsLoadError) {
            return topicsLoadError;
        }

        if (topics.length === 0) {
            return (
                "La materia todavía no tiene temas detectados."
            );
        }

        if (
            selectedTopics.length === 0
        ) {
            return (
                "Seleccioná al menos un tema para este hito."
            );
        }

        return null;
    };

    /*
     * Validación paso 2
     */
    const validateSecondStep = () => {

        if (!startDate) {
            return (
                "Seleccioná la fecha de inicio."
            );
        }

        if (!examDate) {
            return (
                "Seleccioná la fecha del hito."
            );
        }

        if (startDate >= examDate) {
            return (
                "La fecha de inicio debe ser anterior " +
                "a la fecha del hito."
            );
        }

        if (
            !Number.isInteger(
                Number(reviewSessions)
            ) ||
            Number(reviewSessions) < 0
        ) {
            return (
                "La cantidad de sesiones de repaso " +
                "debe ser cero o mayor."
            );
        }

        return null;
    };

    /*
     * Validación paso 3
     */
    const validateThirdStep = () => {

        if (
            availability.length === 0
        ) {
            return (
                "Seleccioná al menos una franja " +
                "de disponibilidad semanal."
            );
        }

        const hasInvalidSlot =
            availability.some(
                (slot) =>
                    !slot.dayOfWeek ||
                    !slot.startTime ||
                    !slot.endTime ||
                    slot.startTime >=
                    slot.endTime
            );

        if (hasInvalidSlot) {
            return (
                "Todas las franjas deben tener " +
                "un día y horarios válidos."
            );
        }

        return null;
    };

    /*
     * Fechas
     */
    const updateStartDate = (
        value
    ) => {
        setStartDate(value);
        setStepError("");

        if (
            examDate &&
            value >= examDate
        ) {
            setExamDate("");
        }
    };

    const updateExamDate = (
        value
    ) => {
        setExamDate(value);
        setStepError("");
    };

    const updateReviewSessions = (
        value
    ) => {
        setReviewSessions(value);
        setStepError("");
    };

    /*
     * Disponibilidad
     */
    const addAvailability = (
        newAvailability
    ) => {

        setAvailability(
            (currentAvailability) => [
                ...currentAvailability,
                newAvailability,
            ]
        );

        setStepError("");
    };

    const updateAvailability = (
        availabilityId,
        updatedValues
    ) => {

        setAvailability(
            (currentAvailability) =>
                currentAvailability.map(
                    (slot) =>
                        slot.id ===
                            availabilityId
                            ? {
                                ...slot,
                                ...updatedValues,
                            }
                            : slot
                )
        );

        setStepError("");
    };

    const removeAvailability = (
        availabilityId
    ) => {

        setAvailability(
            (currentAvailability) =>
                currentAvailability.filter(
                    (slot) =>
                        slot.id !==
                        availabilityId
                )
        );

        setStepError("");
    };

    /*
     * Navegación
     */
    const goToPreviousStep = () => {

        setStepError("");
        setGenerationError("");

        setCurrentStep(
            (step) =>
                Math.max(
                    1,
                    step - 1
                )
        );
    };

    const goToNextStep = () => {

        let validationError =
            null;

        if (currentStep === 1) {
            validationError =
                validateFirstStep();
        }

        if (currentStep === 2) {
            validationError =
                validateSecondStep();
        }

        if (currentStep === 3) {
            validationError =
                validateThirdStep();
        }

        if (validationError) {
            setStepError(
                validationError
            );
            return;
        }

        setStepError("");

        setCurrentStep(
            (step) =>
                Math.min(
                    4,
                    step + 1
                )
        );
    };

    const editStep = (
        stepNumber
    ) => {
        setStepError("");
        setGenerationError("");
        setCurrentStep(
            stepNumber
        );
    };

    /*
     * Generación del plan.
     *
     * IMPORTANTE:
     *
     * topics mantiene por ahora
     * estimatedMinutes=60 para que
     * el backend actual siga funcionando.
     *
     * topicProgress ya contiene toda
     * la información que en el siguiente
     * cambio vamos a pasarle a Gemini.
     */
    const confirmWizard = async () => {

        setGeneratingPlan(true);
        setGenerationError("");

        const request = {
            /*
             * Contexto del hito.
             *
             * El backend viejo puede ignorarlo.
             * El nuevo generador IA lo utilizará.
             */
            milestoneType:
                milestone.type,

            milestoneTitle:
                milestone.title,

            milestoneDescription:
                milestone.description,

            startDate,

            examDate,

            reviewSessions:
                Number(
                    reviewSessions
                ),

            /*
             * Compatibilidad temporal
             * con GenerateStudyPlanRequest.
             */
            topics:
                selectedTopics.map(
                    (topic) => ({
                        title:
                            topic.title,

                        estimatedMinutes:
                            topic.estimatedMinutes,
                    })
                ),

            /*
             * NUEVO CONTEXTO PARA GEMINI.
             *
             * Acá viajan las métricas
             * reales del estudiante.
             */
            topicProgress:
                selectedTopics.map(
                    (topic) => ({
                        topicId:
                            topic.id,

                        title:
                            topic.title,

                        description:
                            topic.description,

                        masteryScore:
                            topic.masteryScore,

                        level:
                            topic.level,

                        coverage:
                            topic.coverage,

                        totalResources:
                            topic.totalResources,

                        totalAttempts:
                            topic.totalAttempts,
                    })
                ),

            availability:
                availability.map(
                    (slot) => ({
                        dayOfWeek:
                            slot.dayOfWeek,

                        startTime:
                            slot.startTime,

                        endTime:
                            slot.endTime,
                    })
                ),
        };

        try {

            const generatedPlan =
                await generateStudyPlan(
                    request
                );

            setPlanResult(
                generatedPlan
            );

        } catch (exception) {

            setGenerationError(
                exception instanceof Error
                    ? exception.message
                    : "Ocurrió un error inesperado."
            );

        } finally {

            setGeneratingPlan(false);
        }
    };

    /*
     * Una vez generado el calendario,
     * usamos únicamente los topics que
     * participaron del hito.
     *
     * Esto es importante porque topicIndex
     * se calcula sobre selectedTopics.
     */
    if (planResult) {
        return (
            <GeneratedPlanView
                subject={subject}
                planResult={planResult}
                onEdit={() => {
                    setPlanResult(null);
                    setGenerationError("");
                    setCurrentStep(4);
                }}
                onBack={onExit}
                topics={selectedTopics}
                examDate={examDate}
            />
        );
    }

    return (
        <main className="study-plan-wizard">
            <header className="wizard-header">
                <div className="wizard-header-content">
                    <button
                        type="button"
                        className="btn-back"
                        onClick={onExit}
                    >
                        ← Volver
                    </button>

                    <span className="wizard-header-eyebrow">
                        Planificador inteligente
                    </span>

                    <h1 className="wizard-title">
                        Nuevo plan de estudio
                    </h1>

                    <p className="wizard-subtitle">
                        Organizá tus objetivos y llegá preparado a{" "}
                        <strong>
                            {subject?.name ||
                                subject?.title ||
                                subject?.nombre ||
                                "Materia seleccionada"}
                        </strong>
                    </p>
                </div>

                <img
                    className="wizard-header-illustration"
                    src={dinaStudyPlanner}
                    alt=""
                    aria-hidden="true"
                />
            </header>

            <WizardProgress
                currentStep={
                    currentStep
                }
            />

            <section className="wizard-step-card">
                <div className="wizard-step-heading">
                    <span className="wizard-step-eyebrow">
                        Paso {currentStep} de 4
                    </span>

                    <h2>
                        {currentContent.title}
                    </h2>

                    <p>
                        {
                            currentContent.description
                        }
                    </p>
                </div>

                {currentStep === 1 && (
                    <MilestoneTopicsStep
                        milestone={
                            milestone
                        }
                        topics={
                            topics
                        }
                        error={
                            stepError
                        }
                        loadingTopics={
                            loadingTopics
                        }
                        topicsLoadError={
                            topicsLoadError
                        }
                        onMilestoneChange={
                            updateMilestone
                        }
                        onToggleTopic={
                            toggleTopic
                        }
                    />
                )}

                {currentStep === 2 && (
                    <DatesStep
                        startDate={
                            startDate
                        }
                        examDate={
                            examDate
                        }
                        reviewSessions={
                            reviewSessions
                        }
                        error={
                            stepError
                        }
                        onStartDateChange={
                            updateStartDate
                        }
                        onExamDateChange={
                            updateExamDate
                        }
                        onReviewSessionsChange={
                            updateReviewSessions
                        }
                    />
                )}

                {currentStep === 3 && (
                    <AvailabilityStep
                        availability={
                            availability
                        }
                        error={
                            stepError
                        }
                        onAddAvailability={
                            addAvailability
                        }
                        onUpdateAvailability={
                            updateAvailability
                        }
                        onRemoveAvailability={
                            removeAvailability
                        }
                    />
                )}

                {currentStep === 4 && (
                    <PlanSummaryStep
                        milestone={
                            milestone
                        }
                        topics={
                            selectedTopics
                        }
                        startDate={
                            startDate
                        }
                        examDate={
                            examDate
                        }
                        reviewSessions={
                            reviewSessions
                        }
                        availability={
                            availability
                        }
                        onEditStep={
                            editStep
                        }
                    />
                )}

                {currentStep === 4 &&
                    generationError && (
                        <div
                            className="wizard-validation-error"
                            role="alert"
                        >
                            {
                                generationError
                            }
                        </div>
                    )}

                <div className="wizard-actions">
                    <button
                        type="button"
                        className="wizard-secondary-button"
                        onClick={
                            goToPreviousStep
                        }
                        disabled={
                            currentStep === 1
                        }
                    >
                        ← Anterior
                    </button>

                    {currentStep < 4 ? (
                        <button
                            type="button"
                            className="wizard-primary-button"
                            onClick={
                                goToNextStep
                            }
                            disabled={
                                currentStep === 1 &&
                                loadingTopics
                            }
                        >
                            {currentStep === 1 &&
                                loadingTopics
                                ? "Cargando temas..."
                                : "Siguiente →"}
                        </button>
                    ) : (
                        <button
                            type="button"
                            className="wizard-primary-button"
                            onClick={
                                confirmWizard
                            }
                            disabled={
                                generatingPlan
                            }
                        >
                            {generatingPlan
                                ? "Generando plan..."
                                : "Generar plan"}
                        </button>
                    )}
                </div>
            </section>
        </main>
    );
}