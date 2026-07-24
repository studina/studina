import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";

export default function GeneratedPlanView({
  subject,
  planResult,
  onEdit,
  onBack,
}) {
  const subjectName =
    subject?.name ||
    subject?.title ||
    subject?.nombre ||
    "Materia seleccionada";

  const calendarEvents =
    planResult.sessions.map(
      (session, index) => ({
        id: String(index),
        title: session.title,
        start: session.startDateTime,
        end: session.endDateTime,
        extendedProps: {
          type: session.type,
          topicIndex: session.topicIndex,
          partNumber: session.partNumber,
        },
      })
    );

  return (
    <main className="generated-plan-view">
      <header className="generated-plan-header">
        <div>
          {onBack && (
            <button
              type="button"
              className="btn-back"
              onClick={onBack}
            >
              ← Volver al planificador
            </button>
          )}

          <h1>Tu plan de estudio</h1>

          <p>{subjectName}</p>
        </div>

        <button
          type="button"
          className="generated-plan-edit-button"
          onClick={onEdit}
        >
          Editar configuración
        </button>
      </header>

      <section className="generated-plan-stats">
        <div>
          <span>Tiempo necesario</span>

          <strong>
            {planResult.requiredMinutes} min
          </strong>
        </div>

        <div>
          <span>Tiempo disponible</span>

          <strong>
            {planResult.availableMinutes} min
          </strong>
        </div>

        <div>
          <span>Sesiones generadas</span>

          <strong>
            {planResult.sessions.length}
          </strong>
        </div>
      </section>

      {planResult.message && (
        <p className="generated-plan-message">
          {planResult.message}
        </p>
      )}

      <section className="generated-plan-calendar">
        <FullCalendar
          plugins={[
            dayGridPlugin,
            timeGridPlugin,
            interactionPlugin,
          ]}
          initialView="dayGridMonth"
          events={calendarEvents}
          firstDay={1}
          locale="es"
          height="auto"
          nowIndicator
          allDaySlot={false}
          slotMinTime="06:00:00"
          slotMaxTime="23:30:00"
          headerToolbar={{
            left: "prev,next today",
            center: "title",
            right:
              "dayGridMonth,timeGridWeek,timeGridDay",
          }}
          buttonText={{
            today: "Hoy",
            month: "Mes",
            week: "Semana",
            day: "Día",
          }}
          noEventsContent="No se generaron sesiones."
        />
      </section>
    </main>
  );
}