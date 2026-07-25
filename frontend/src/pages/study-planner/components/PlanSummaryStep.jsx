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

const getTotalTopicMinutes = (topics) =>
  topics.reduce(
    (total, topic) =>
      total + Number(topic.estimatedMinutes || 0),
    0
  );

export default function PlanSummaryStep({
  milestone,
  topics,
  startDate,
  examDate,
  reviewSessions,
  availability,
  onEditStep,
}) {
  const totalTopicMinutes =
    getTotalTopicMinutes(topics);

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

          <span>
            {totalTopicMinutes} minutos estimados
          </span>
        </div>

        <div className="summary-topics-list">
          {topics.map((topic, index) => (
            <article
              key={topic.id}
              className="summary-topic-item"
            >
              <div>
                <span>
                  Tema {index + 1}
                </span>

                <strong>
                  {topic.title}
                </strong>
              </div>

              <span className="summary-topic-duration">
                {topic.estimatedMinutes} min
              </span>

              {topic.scope && (
                <p>{topic.scope}</p>
              )}
            </article>
          ))}
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