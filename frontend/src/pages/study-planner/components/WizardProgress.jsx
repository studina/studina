const WIZARD_STEPS = [
  {
    number: 1,
    label: "Hito y temas",
  },
  {
    number: 2,
    label: "Fechas",
  },
  {
    number: 3,
    label: "Disponibilidad",
  },
  {
    number: 4,
    label: "Resumen",
  },
];

export default function WizardProgress({
  currentStep,
}) {
  return (
    <nav
      className="wizard-progress"
      aria-label="Progreso del plan de estudio"
    >
      {WIZARD_STEPS.map((step, index) => {
        const isActive =
          step.number === currentStep;

        const isCompleted =
          step.number < currentStep;

        return (
          <div
            key={step.number}
            className="wizard-progress-item"
          >
            <div
              className={[
                "wizard-progress-step",
                isActive ? "is-active" : "",
                isCompleted ? "is-completed" : "",
              ]
                .filter(Boolean)
                .join(" ")}
            >
              <span className="wizard-progress-number">
                {isCompleted ? "✓" : step.number}
              </span>

              <span className="wizard-progress-label">
                {step.label}
              </span>
            </div>

            {index < WIZARD_STEPS.length - 1 && (
              <span
                className={[
                  "wizard-progress-line",
                  isCompleted ? "is-completed" : "",
                ]
                  .filter(Boolean)
                  .join(" ")}
                aria-hidden="true"
              />
            )}
          </div>
        );
      })}
    </nav>
  );
}