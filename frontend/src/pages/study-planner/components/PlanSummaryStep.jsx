const MILESTONE_LABELS = {
  EXAM: "Examen",
  MIDTERM: "Parcial",
  FINAL_EXAM: "Final",
  ASSIGNMENT: "Trabajo práctico",
  DELIVERY: "Entrega",
  OTHER: "Otro",
};

const DAY_LABELS = {
  MONDAY: "Lunes",
  TUESDAY: "Martes",
  WEDNESDAY: "Miércoles",
  THURSDAY: "Jueves",
  FRIDAY: "Viernes",
  SATURDAY: "Sábado",
  SUNDAY: "Domingo",
};

const LEVEL_LABELS = {
  weak: "Necesita refuerzo",
  progressing: "En progreso",
  strong: "Dominado",
};

const formatDate = (dateValue) => {
  if (!dateValue) {
    return "No definida";
  }

  const [year, month, day] = dateValue
    .split("-")
    .map(Number);

  return new Intl.DateTimeFormat("es-AR", {
    day: "2-digit",
    month: "long",
    year: "numeric",
  }).format(new Date(year, month - 1, day));
};

export default function PlanSummaryStep({
  milestone,
  topics,
  startDate,
  examDate,
  reviewSessions,
  availability,
  onEditStep,
}) {
  return (
    <div className="plan-summary-step">
      <section className="summary-section-card">
        <div className="summary-section-header">
          <div>
            <span className="summary-section-eyebrow">
              Objetivo académico
            </span>

            <h3>Hito y temas</h3>
          </div>

          <button
            type="button"
            className="summary-edit-button"
            onClick={() => onEditStep(1)}
          >
            Editar
          </button>
        </div>

        <div className="summary-milestone">
          <div>
            <span>Tipo</span>

            <strong>
              {MILESTONE_LABELS[
                milestone.type
              ] || "No especificado"}
            </strong>
          </div>

          <div>
            <span>Nombre</span>

            <strong>
              {milestone.title}
            </strong>
          </div>
        </div>

        {milestone.description && (
          <p className="summary-description">
            {milestone.description}
          </p>
        )}

        <div className="summary-topics-heading">
          <strong>
            {topics.length === 1
              ? "1 tema"
              : `${topics.length} temas`}
          </strong>
        </div>

        <div className="summary-topics-list">
          {topics.map((topic) => {
            const knownLevel =
              Boolean(LEVEL_LABELS[topic.level]);
            const levelClass = knownLevel
              ? `level-${topic.level}`
              : "level-none";
            const levelLabel = topic.level
              ? LEVEL_LABELS[topic.level] ?? topic.level
              : "Sin empezar";
            const resourceCount = Math.max(
              0,
              Number(topic.totalResources) || 0
            );
            const resourceLabel =
              resourceCount === 1
                ? "1 recurso"
                : `${resourceCount} recursos`;

            return (
              <article
                key={topic.id}
                className="summary-topic-card"
              >
                <div className="milestone-topic-content">
                  <strong className="milestone-topic-name">
                    {topic.title}
                  </strong>

                  <div className="milestone-topic-meta">
                    <span
                      className={`milestone-topic-status ${levelClass}`}
                    >
                      {levelLabel}
                    </span>

                    <span className="milestone-topic-resources">
                      {resourceLabel}
                    </span>
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      </section>

      <section className="summary-section-card">
        <div className="summary-section-header">
          <div>
            <span className="summary-section-eyebrow">
              Período
            </span>

            <h3>Fechas del plan</h3>
          </div>

          <button
            type="button"
            className="summary-edit-button"
            onClick={() => onEditStep(2)}
          >
            Editar
          </button>
        </div>

        <div className="summary-dates-grid">
          <div>
            <span>Fecha de inicio</span>

            <strong>
              {formatDate(startDate)}
            </strong>
          </div>

          <div>
            <span>Fecha del hito</span>

            <strong>
              {formatDate(examDate)}
            </strong>
          </div>

          <div>
            <span>Sesiones de repaso</span>

            <strong>
              {reviewSessions}
            </strong>
          </div>
        </div>
      </section>

      <section className="summary-section-card">
        <div className="summary-section-header">
          <div>
            <span className="summary-section-eyebrow">
              Semana
            </span>

            <h3>Disponibilidad</h3>
          </div>

          <button
            type="button"
            className="summary-edit-button"
            onClick={() => onEditStep(3)}
          >
            Editar
          </button>
        </div>

        <div className="summary-availability-list">
          {availability.map((slot) => (
            <div
              key={slot.id}
              className="summary-availability-item"
            >
              <strong>
                {DAY_LABELS[
                  slot.dayOfWeek
                ] || slot.dayOfWeek}
              </strong>

              <span>
                {slot.startTime}–{slot.endTime}
              </span>
            </div>
          ))}
        </div>
      </section>

      <div className="summary-confirmation-message">
        <strong>
          Todo listo para generar el plan
        </strong>

        <p>
          Revisá la información antes de
          confirmar. Después podrás modificar
          la configuración y generar nuevamente.
        </p>
      </div>
    </div>
  );
}
