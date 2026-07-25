export default function DatesStep({
  startDate,
  examDate,
  reviewSessions,
  error,
  onStartDateChange,
  onExamDateChange,
  onReviewSessionsChange,
}) {
  const today = new Date()
    .toISOString()
    .split("T")[0];

  return (
    <div className="dates-step">
      <section className="wizard-form-section">
        <div className="wizard-section-heading">
          <h3>Período de planificación</h3>

          <p>
            Indicá desde cuándo querés comenzar a
            estudiar y la fecha del hito.
          </p>
        </div>

        <div className="dates-fields">
          <label>
            Fecha de inicio

            <input
              type="date"
              min={today}
              max={examDate || undefined}
              value={startDate}
              onChange={(event) =>
                onStartDateChange(
                  event.target.value
                )
              }
            />

            <span className="wizard-field-help">
              Día desde el que Studina puede
              comenzar a asignar sesiones.
            </span>
          </label>

          <label>
            Fecha del hito

            <input
              type="date"
              min={startDate || today}
              value={examDate}
              onChange={(event) =>
                onExamDateChange(
                  event.target.value
                )
              }
            />

            <span className="wizard-field-help">
              Fecha del examen, final, entrega o
              trabajo práctico.
            </span>
          </label>
        </div>
      </section>

      <section className="wizard-form-section">
        <div className="wizard-section-heading">
          <h3>Sesiones de repaso</h3>

          <p>
            Reservá sesiones adicionales para
            revisar los contenidos antes del hito.
          </p>
        </div>

        <label className="review-sessions-field">
          Cantidad de sesiones

          <input
            type="number"
            min="0"
            step="1"
            value={reviewSessions}
            onChange={(event) =>
              onReviewSessionsChange(
                Number(event.target.value)
              )
            }
          />

          <span className="wizard-field-help">
            Podés ingresar 0 si no querés reservar
            sesiones específicas de repaso.
          </span>
        </label>
      </section>

      {error && (
        <div
          className="wizard-validation-error"
          role="alert"
        >
          {error}
        </div>
      )}
    </div>
  );
}