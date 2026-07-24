import { useMemo, useState } from "react";

import WizardProgress from "./components/WizardProgress";
import "./StudyPlanWizard.css";
import MilestoneTopicsStep from "./components/MilestoneTopicsStep";
import DatesStep from "./components/DatesStep";
import AvailabilityStep from "./components/AvailabilityStep";
import PlanSummaryStep from "./components/PlanSummaryStep";
import GeneratedPlanView from "./components/GeneratedPlanView";
import { generateStudyPlan } from "../../services/studyPlanService";
import { buildStudyMarkdown } from "./utils/buildStudyMarkdown";

const STEP_CONTENT = {
    1: {
        title: "Hito y temas",
        description:
            "En este paso vas a definir qué querés alcanzar y qué temas forman parte de ese objetivo.",
    },

    2: {
        title: "Fechas del plan",
        description:
            "Configurá cuándo querés comenzar y cuál es la fecha del examen, entrega o hito.",
        placeholder:
            "Próximamente: fecha inicial, fecha objetivo y sesiones de repaso.",
    },

    3: {
        title: "Disponibilidad y sesiones",
        description:
            "Indicá qué días y horarios tenés disponibles para estudiar.",
        placeholder:
            "Próximamente: vista semanal, duración de sesiones, descansos y Pomodoro.",
    },

    4: {
        title: "Resumen",
        description:
            "Revisá toda la configuración antes de generar el plan.",
        placeholder:
            "Próximamente: resumen de temas, fechas, disponibilidad y preferencias.",
    },
};

export default function StudyPlanWizard({
    subject,
    onExit,
}) {
    const [currentStep, setCurrentStep] =
        useState(1);

    const [completedMessage, setCompletedMessage] =
        useState("");

    const currentContent =
        STEP_CONTENT[currentStep];

    const [planResult, setPlanResult] =
        useState(null);

    const [generatingPlan, setGeneratingPlan] =
        useState(false);

    const [generationError, setGenerationError] =
        useState("");

    const updateMilestone = (field, value) => {
        setMilestone((currentMilestone) => ({
            ...currentMilestone,
            [field]: value,
        }));

        setStepError("");
    };
    const addTopic = () => {
        setTopics((currentTopics) => [
            ...currentTopics,
            {
                id: crypto.randomUUID(),
                title: "",
                scope: "",
                estimatedMinutes: 60,
            },
        ]);

        setStepError("");
    };

    const updateTopic = (
        topicId,
        field,
        value
    ) => {
        setTopics((currentTopics) =>
            currentTopics.map((topic) =>
                topic.id === topicId
                    ? {
                        ...topic,
                        [field]:
                            field === "estimatedMinutes"
                                ? Number(value)
                                : value,
                    }
                    : topic
            )
        );

        setStepError("");
    };

    const removeTopic = (topicId) => {
        setTopics((currentTopics) =>
            currentTopics.filter(
                (topic) => topic.id !== topicId
            )
        );

        setStepError("");
    };

    const validateFirstStep = () => {
        if (!milestone.type) {
            return "Seleccioná el tipo de hito.";
        }

        if (!milestone.title.trim()) {
            return "Ingresá el nombre del hito.";
        }

        if (topics.length === 0) {
            return "Agregá al menos un tema.";
        }

        const hasInvalidTopic = topics.some(
            (topic) =>
                !topic.title.trim() ||
                !Number.isFinite(
                    topic.estimatedMinutes
                ) ||
                topic.estimatedMinutes <= 0
        );

        if (hasInvalidTopic) {
            return (
                "Todos los temas deben tener un nombre " +
                "y una duración mayor que cero."
            );
        }

        return null;
    };

    const validateSecondStep = () => {
        if (!startDate) {
            return "Seleccioná la fecha de inicio.";
        }

        if (!examDate) {
            return "Seleccioná la fecha del hito.";
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

    const validateThirdStep = () => {
        if (availability.length === 0) {
            return (
                "Seleccioná al menos una franja " +
                "de disponibilidad semanal."
            );
        }

        const hasInvalidSlot = availability.some(
            (slot) =>
                !slot.dayOfWeek ||
                !slot.startTime ||
                !slot.endTime ||
                slot.startTime >= slot.endTime
        );

        if (hasInvalidSlot) {
            return (
                "Todas las franjas deben tener " +
                "un día y horarios válidos."
            );
        }

        return null;
    };

    const updateStartDate = (value) => {
        setStartDate(value);
        setStepError("");

        if (examDate && value >= examDate) {
            setExamDate("");
        }
    };

    const updateExamDate = (value) => {
        setExamDate(value);
        setStepError("");
    };

    const updateReviewSessions = (value) => {
        setReviewSessions(value);
        setStepError("");
    };

    const goToPreviousStep = () => {
        setCompletedMessage("");

        setCurrentStep((step) =>
            Math.max(1, step - 1)
        );
    };

    const goToNextStep = () => {
        setCompletedMessage("");

        let validationError = null;

        if (currentStep === 1) {
            validationError = validateFirstStep();
        }

        if (currentStep === 2) {
            validationError = validateSecondStep();
        }

        if (currentStep === 3) {
            validationError = validateThirdStep();
        }

        if (validationError) {
            setStepError(validationError);
            return;
        }

        setStepError("");

        setCurrentStep((step) =>
            Math.min(4, step + 1)
        );
    };

    const editStep = (stepNumber) => {
        setStepError("");
        setCompletedMessage("");
        setCurrentStep(stepNumber);
    };

    const confirmWizard = async () => {
        setGeneratingPlan(true);
        setGenerationError("");
        setCompletedMessage("");

        const request = {
            startDate,
            examDate,
            reviewSessions: Number(reviewSessions),

            topics: topics.map((topic) => ({
                title: topic.title,
                estimatedMinutes:
                    Number(topic.estimatedMinutes),
            })),

            availability: availability.map(
                (slot) => ({
                    dayOfWeek: slot.dayOfWeek,
                    startTime: slot.startTime,
                    endTime: slot.endTime,
                })
            ),
        };

        try {
            const generatedPlan =
                await generateStudyPlan(request);

            setPlanResult(generatedPlan);
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

    const [milestone, setMilestone] = useState({
        type: "",
        title: "",
        description: "",
    });


    const [topics, setTopics] = useState([]);

    const [showMarkdownPreview, setShowMarkdownPreview] =
        useState(false);

    const [stepError, setStepError] = useState("");

    const [startDate, setStartDate] =
        useState("");

    const [examDate, setExamDate] =
        useState("");

    const [reviewSessions, setReviewSessions] =
        useState(2);

    const [availability, setAvailability] =
        useState([]);

    const markdownPreview = useMemo(
        () =>
            buildStudyMarkdown({
                milestone,
                topics,
            }),
        [milestone, topics]
    );

    const addAvailability = (newAvailability) => {
        setAvailability((currentAvailability) => [
            ...currentAvailability,
            newAvailability,
        ]);

        setStepError("");
    };

    const updateAvailability = (
        availabilityId,
        updatedValues
    ) => {
        setAvailability((currentAvailability) =>
            currentAvailability.map((slot) =>
                slot.id === availabilityId
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
        setAvailability((currentAvailability) =>
            currentAvailability.filter(
                (slot) =>
                    slot.id !== availabilityId
            )
        );

        setStepError("");
    };

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
            />
        );
    }

    return (
        <main className="study-plan-wizard">
            <header className="wizard-header">
                <div>
                    <button
                        type="button"
                        className="btn-back"
                        onClick={onExit}
                    >
                        ← Volver al planificador actual
                    </button>

                    <h1 className="wizard-title">
                        Nuevo plan de estudio
                    </h1>

                    <p className="wizard-subtitle">
                        {subject?.name ||
                            subject?.title ||
                            subject?.nombre ||
                            "Materia seleccionada"}
                    </p>
                </div>

                <div
                    className="wizard-header-icon"
                    aria-hidden="true"
                >
                    ALGO QUE NO SEA UN EMOJI
                </div>
            </header>

            <WizardProgress
                currentStep={currentStep}
            />

            <section className="wizard-step-card">
                <div className="wizard-step-heading">
                    <span className="wizard-step-eyebrow">
                        Paso {currentStep} de 4
                    </span>

                    <h2>{currentContent.title}</h2>

                    <p>{currentContent.description}</p>
                </div>

                {currentStep === 1 && (
                    <MilestoneTopicsStep
                        milestone={milestone}
                        topics={topics}
                        markdownPreview={markdownPreview}
                        showMarkdownPreview={
                            showMarkdownPreview
                        }
                        error={stepError}
                        onMilestoneChange={updateMilestone}
                        onTopicChange={updateTopic}
                        onAddTopic={addTopic}
                        onRemoveTopic={removeTopic}
                        onToggleMarkdownPreview={() =>
                            setShowMarkdownPreview(
                                (currentValue) => !currentValue
                            )
                        }
                    />
                )}

                {currentStep === 2 && (
                    <DatesStep
                        startDate={startDate}
                        examDate={examDate}
                        reviewSessions={reviewSessions}
                        error={stepError}
                        onStartDateChange={updateStartDate}
                        onExamDateChange={updateExamDate}
                        onReviewSessionsChange={
                            updateReviewSessions
                        }
                    />
                )}

                {currentStep === 3 && (
                    <AvailabilityStep
                        availability={availability}
                        error={stepError}
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
                        milestone={milestone}
                        topics={topics}
                        startDate={startDate}
                        examDate={examDate}
                        reviewSessions={reviewSessions}
                        availability={availability}
                        onEditStep={editStep}
                    />
                )}

                {currentStep === 4 &&
                    generationError && (
                        <div
                            className="wizard-validation-error"
                            role="alert"
                        >
                            {generationError}
                        </div>
                    )}

                {completedMessage && (
                    <div
                        className="wizard-success"
                        role="status"
                    >
                        {completedMessage}
                    </div>
                )}

                <div className="wizard-actions">
                    <button
                        type="button"
                        className="wizard-secondary-button"
                        onClick={goToPreviousStep}
                        disabled={currentStep === 1}
                    >
                        ← Anterior
                    </button>

                    {currentStep < 4 ? (
                        <button
                            type="button"
                            className="wizard-primary-button"
                            onClick={goToNextStep}
                        >
                            Siguiente →
                        </button>
                    ) : (
                        <button
                            type="button"
                            className="wizard-primary-button"
                            onClick={confirmWizard}
                            disabled={generatingPlan}
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