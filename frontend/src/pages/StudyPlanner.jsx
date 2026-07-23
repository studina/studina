import { useState } from "react";

import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";

import "./StudyPlanner.css";

const DAYS = [
  { value: "MONDAY", label: "Lunes" },
  { value: "TUESDAY", label: "Martes" },
  { value: "WEDNESDAY", label: "Miércoles" },
  { value: "THURSDAY", label: "Jueves" },
  { value: "FRIDAY", label: "Viernes" },
  { value: "SATURDAY", label: "Sábado" },
  { value: "SUNDAY", label: "Domingo" },
];

const TIME_OPTIONS = Array.from(
  { length: 35 },
  (_, index) => {
    const totalMinutes = 6 * 60 + index * 30;
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;

    const value =
      `${String(hours).padStart(2, "0")}:` +
      `${String(minutes).padStart(2, "0")}`;

    return {
      value,
      label: value,
    };
  }
);

const timeToMinutes = (time) => {
  if (!time) {
    return 0;
  }

  const [hours, minutes] = time
    .split(":")
    .map(Number);

  return hours * 60 + minutes;
};

export default function StudyPlanner({ subject, onBack }) {
  const [startDate, setStartDate] = useState("");
  const [examDate, setExamDate] = useState("");
  const [reviewSessions, setReviewSessions] = useState(2);

  const [topics, setTopics] = useState([]);
  const [availability, setAvailability] = useState([]);

  const [events, setEvents] = useState([]);
  const [planResult, setPlanResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const subjectName =
    subject?.name ||
    subject?.title ||
    subject?.nombre ||
    "Materia seleccionada";

  const updateTopic = (index, field, value) => {
    setTopics((currentTopics) =>
      currentTopics.map((topic, currentIndex) =>
        currentIndex === index
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

    setError("");
  };

  const addTopic = () => {
    setTopics((currentTopics) => [
      ...currentTopics,
      {
        title: "",
        estimatedMinutes: 60,
      },
    ]);

    setError("");
  };

  const removeTopic = (index) => {
    setTopics((currentTopics) =>
      currentTopics.filter(
        (_, currentIndex) => currentIndex !== index
      )
    );

    setError("");
  };

  const updateAvailability = (index, field, value) => {
    setAvailability((currentAvailability) =>
      currentAvailability.map((slot, currentIndex) => {
        if (currentIndex !== index) {
          return slot;
        }

        if (field === "startTime") {
          const nextValidEndTime = TIME_OPTIONS.find(
            (time) => time.value > value
          )?.value;

          return {
            ...slot,
            startTime: value,
            endTime:
              timeToMinutes(slot.endTime) >
              timeToMinutes(value)
                ? slot.endTime
                : nextValidEndTime || "",
          };
        }

        return {
          ...slot,
          [field]: value,
        };
      })
    );

    setError("");
  };

  const addAvailability = () => {
    setAvailability((currentAvailability) => [
      ...currentAvailability,
      {
        dayOfWeek: "MONDAY",
        startTime: "18:00",
        endTime: "19:00",
      },
    ]);

    setError("");
  };

  const removeAvailability = (index) => {
    setAvailability((currentAvailability) =>
      currentAvailability.filter(
        (_, currentIndex) => currentIndex !== index
      )
    );

    setError("");
  };

  const validateForm = () => {
    if (!startDate || !examDate) {
      return "Completá la fecha de inicio y la fecha de examen.";
    }

    if (startDate >= examDate) {
      return "La fecha de inicio debe ser anterior a la fecha de examen.";
    }

    if (topics.length === 0) {
      return "Agregá al menos un tema.";
    }

    const hasInvalidTopic = topics.some(
      (topic) =>
        !topic.title.trim() ||
        !Number.isFinite(topic.estimatedMinutes) ||
        topic.estimatedMinutes <= 0
    );

    if (hasInvalidTopic) {
      return "Todos los temas deben tener un nombre y una duración válida.";
    }

    if (availability.length === 0) {
      return "Agregá al menos una franja de disponibilidad.";
    }

    const hasInvalidAvailability = availability.some(
      (slot) =>
        !slot.dayOfWeek ||
        !slot.startTime ||
        !slot.endTime ||
        timeToMinutes(slot.startTime) >=
          timeToMinutes(slot.endTime)
    );

    if (hasInvalidAvailability) {
      return "Todas las franjas deben tener horarios válidos.";
    }

    if (
      !Number.isInteger(Number(reviewSessions)) ||
      Number(reviewSessions) < 0
    ) {
      return "La cantidad de sesiones de repaso debe ser cero o mayor.";
    }

    return null;
  };

  const generatePlan = async (event) => {
    event.preventDefault();

    const validationError = validateForm();

    if (validationError) {
      setError(validationError);
      return;
    }

    setLoading(true);
    setError("");
    setPlanResult(null);
    setEvents([]);

    const request = {
      startDate,
      examDate,
      reviewSessions: Number(reviewSessions),
      topics,
      availability,
    };

    try {
      const response = await fetch(
        "/api/study-plans/generate",
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify(request),
        }
      );

      const body = await response.json();

      if (!response.ok) {
        throw new Error(
          body.message ||
            body.error ||
            "No se pudo generar el plan."
        );
      }

      const calendarEvents = body.sessions.map(
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

      setPlanResult(body);
      setEvents(calendarEvents);
    } catch (exception) {
      setError(
        exception instanceof Error
          ? exception.message
          : "Ocurrió un error inesperado."
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="study-planner">
      <div className="planner-header">
        <div>
          {onBack && (
            <button
              type="button"
              className="btn-back"
              onClick={onBack}
            >
              ← Volver
            </button>
          )}

          <h1 className="planner-title">
            Planificador de estudio
          </h1>

          <p className="planner-subtitle">
            {subjectName} · Organizá tus temas antes del examen
          </p>
        </div>

        <span
          className="planner-icon"
          role="img"
          aria-label="Calendario"
        >
          📅
        </span>
      </div>

      <div className="planner-layout">
        <section className="planner-form-column">
          <form
            className="planner-form"
            onSubmit={generatePlan}
          >
            <fieldset className="planner-card">
              <legend className="planner-card-title">
                Fechas
              </legend>

              <label>
                Fecha de inicio
                <input
                  type="date"
                  value={startDate}
                  onChange={(event) => {
                    setStartDate(event.target.value);
                    setError("");
                  }}
                />
              </label>

              <label>
                Fecha de examen
                <input
                  type="date"
                  value={examDate}
                  onChange={(event) => {
                    setExamDate(event.target.value);
                    setError("");
                  }}
                />
              </label>

              <label>
                Sesiones de repaso
                <input
                  type="number"
                  min="0"
                  step="1"
                  value={reviewSessions}
                  onChange={(event) => {
                    setReviewSessions(
                      Number(event.target.value)
                    );
                    setError("");
                  }}
                />
              </label>
            </fieldset>

            <fieldset className="planner-card">
              <legend className="planner-card-title">
                Temas
              </legend>

              {topics.length === 0 && (
                <p className="planner-empty-message">
                  Todavía no agregaste temas.
                </p>
              )}

              {topics.map((topic, index) => (
                <div
                  key={`topic-${index}`}
                  className="study-planner__row"
                >
                  <input
                    type="text"
                    aria-label={`Nombre del tema ${index + 1}`}
                    placeholder="Nombre del tema"
                    value={topic.title}
                    onChange={(event) =>
                      updateTopic(
                        index,
                        "title",
                        event.target.value
                      )
                    }
                  />

                  <input
                    type="number"
                    min="1"
                    aria-label={`Duración del tema ${index + 1}`}
                    placeholder="Minutos"
                    value={topic.estimatedMinutes}
                    onChange={(event) =>
                      updateTopic(
                        index,
                        "estimatedMinutes",
                        event.target.value
                      )
                    }
                  />

                  <button
                    type="button"
                    className="planner-delete-button"
                    aria-label={`Eliminar tema ${index + 1}`}
                    title="Eliminar tema"
                    onClick={() => removeTopic(index)}
                  >
                    ✕
                  </button>
                </div>
              ))}

              <button
                type="button"
                className="planner-secondary-button"
                onClick={addTopic}
              >
                ＋ Agregar tema
              </button>
            </fieldset>

            <fieldset className="planner-card">
              <legend className="planner-card-title">
                Disponibilidad semanal
              </legend>

              {availability.length === 0 && (
                <p className="planner-empty-message">
                  Todavía no agregaste disponibilidad.
                </p>
              )}

              {availability.map((slot, index) => (
                <div
                  key={`availability-${index}`}
                  className="study-planner__row"
                >
                  <select
                    aria-label={`Día disponible ${index + 1}`}
                    value={slot.dayOfWeek}
                    onChange={(event) =>
                      updateAvailability(
                        index,
                        "dayOfWeek",
                        event.target.value
                      )
                    }
                  >
                    {DAYS.map((day) => (
                      <option
                        key={day.value}
                        value={day.value}
                      >
                        {day.label}
                      </option>
                    ))}
                  </select>

                  <select
                    aria-label={`Hora inicial ${index + 1}`}
                    value={slot.startTime}
                    onChange={(event) =>
                      updateAvailability(
                        index,
                        "startTime",
                        event.target.value
                      )
                    }
                  >
                    {TIME_OPTIONS.map((time) => (
                      <option
                        key={`start-${index}-${time.value}`}
                        value={time.value}
                      >
                        {time.label}
                      </option>
                    ))}
                  </select>

                  <select
                    aria-label={`Hora final ${index + 1}`}
                    value={slot.endTime}
                    onChange={(event) =>
                      updateAvailability(
                        index,
                        "endTime",
                        event.target.value
                      )
                    }
                  >
                    {TIME_OPTIONS
                      .filter(
                        (time) =>
                          timeToMinutes(time.value) >
                          timeToMinutes(slot.startTime)
                      )
                      .map((time) => (
                        <option
                          key={`end-${index}-${time.value}`}
                          value={time.value}
                        >
                          {time.label}
                        </option>
                      ))}
                  </select>

                  <button
                    type="button"
                    className="planner-delete-button"
                    aria-label={`Eliminar disponibilidad ${index + 1}`}
                    title="Eliminar disponibilidad"
                    onClick={() =>
                      removeAvailability(index)
                    }
                  >
                    ✕
                  </button>
                </div>
              ))}

              <button
                type="button"
                className="planner-secondary-button"
                onClick={addAvailability}
              >
                ＋ Agregar disponibilidad
              </button>
            </fieldset>

            <button
              type="submit"
              className="planner-primary-button"
              disabled={loading}
            >
              {loading
                ? "Generando plan..."
                : "Generar plan"}
            </button>
          </form>

          {error && (
            <div
              role="alert"
              className="planner-error"
            >
              {error}
            </div>
          )}
        </section>

        <section className="planner-calendar-column">
          <section className="planner-calendar-card">
            <FullCalendar
              plugins={[
                dayGridPlugin,
                timeGridPlugin,
                interactionPlugin,
              ]}
              initialView="dayGridMonth"
              events={events}
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
              locale="es"
              firstDay={1}
              height="auto"
              nowIndicator
              allDaySlot={false}
              slotMinTime="06:00:00"
              slotMaxTime="23:30:00"
              noEventsContent="Todavía no hay sesiones generadas."
            />
          </section>

          {planResult && (
            <section className="planner-result">
              <div className="planner-summary">
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
              </div>

              <p className="planner-result-message">
                {planResult.message}
              </p>
            </section>
          )}
        </section>
      </div>
    </main>
  );
}