import { useState } from "react";

import WizardProgress from "./components/WizardProgress";

const STEP_CONTENT = {
  1: {
    title: "Hito y temas",
    description:
      "En este paso vas a definir qué querés alcanzar y qué temas forman parte de ese objetivo.",
    placeholder:
      "Próximamente: carga del archivo Markdown, análisis con IA y aprobación de temas.",
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

  const goToPreviousStep = () => {
    setCompletedMessage("");

    setCurrentStep((step) =>
      Math.max(1, step - 1)
    );
  };

  const goToNextStep = () => {
    setCompletedMessage("");

    setCurrentStep((step) =>
      Math.min(4, step + 1)
    );
  };

  const confirmWizard = () => {
    setCompletedMessage(
      "La navegación del nuevo flujo funciona correctamente. En la próxima etapa conectaremos los datos reales."
    );
  };

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
          🧭
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

        <div className="wizard-placeholder">
          <span aria-hidden="true">🛠️</span>

          <p>
            {currentContent.placeholder}
          </p>
        </div>

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
            >
              Confirmar prueba
            </button>
          )}
        </div>
      </section>
    </main>
  );
}