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

const getDayLabel = (dayOfWeek) => {
  return (
    DAYS.find(
      (day) => day.value === dayOfWeek
    )?.label || dayOfWeek
  );
};

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

const findOverlappingAvailability = (availability) => {
  for (
    let firstIndex = 0;
    firstIndex < availability.length;
    firstIndex++
  ) {
    const firstSlot = availability[firstIndex];

    for (
      let secondIndex = firstIndex + 1;
      secondIndex < availability.length;
      secondIndex++
    ) {
      const secondSlot = availability[secondIndex];

      if (
        firstSlot.dayOfWeek !== secondSlot.dayOfWeek
      ) {
        continue;
      }

      const firstStart = timeToMinutes(
        firstSlot.startTime
      );

      const firstEnd = timeToMinutes(
        firstSlot.endTime
      );

      const secondStart = timeToMinutes(
        secondSlot.startTime
      );

      const secondEnd = timeToMinutes(
        secondSlot.endTime
      );

      const overlaps =
        firstStart < secondEnd &&
        secondStart < firstEnd;

      if (overlaps) {
        return {
          firstIndex,
          secondIndex,
          dayOfWeek: firstSlot.dayOfWeek,
        };
      }
    }
  }

  return null;
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
  const [selectedSession, setSelectedSession] = useState(null);

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

    const overlappingAvailability =
      findOverlappingAvailability(availability);

    if (overlappingAvailability) {
      const firstSlot =
        availability[
        overlappingAvailability.firstIndex
        ];

      const secondSlot =
        availability[
        overlappingAvailability.secondIndex
        ];

      const dayLabel = getDayLabel(
        overlappingAvailability.dayOfWeek
      );

      return (
        `Las disponibilidades del ${dayLabel} ` +
        `${firstSlot.startTime}–${firstSlot.endTime} y ` +
        `${secondSlot.startTime}–${secondSlot.endTime} ` +
        "se superponen."
      );
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

  const formatDate = (date) => {
    return new Intl.DateTimeFormat("es-AR", {
      weekday: "long",
      day: "2-digit",
      month: "long",
      year: "numeric",
    }).format(date);
  };

  const formatTime = (date) => {
    return new Intl.DateTimeFormat("es-AR", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(date);
  };

  const getSessionTypeLabel = (type) => {
    const labels = {
      STUDY: "Estudio",
      REVIEW: "Repaso",
      MOCK_EXAM: "Simulacro",
    };

    return labels[type] || type;
  };

  const handleEventClick = (clickInfo) => {
    const event = clickInfo.event;

    setSelectedSession({
      title: event.title,
      start: event.start,
      end: event.end,
      type: event.extendedProps.type,
      topicIndex: event.extendedProps.topicIndex,
      partNumber: event.extendedProps.partNumber,
    });
  };

  const closeSessionModal = () => {
    setSelectedSession(null);
  };

  const resetPlanner = () => {
    const hasPlannerData =
      startDate ||
      examDate ||
      topics.length > 0 ||
      availability.length > 0 ||
      planResult ||
      events.length > 0;

    if (hasPlannerData) {
      const confirmed = window.confirm(
        "¿Querés limpiar todo el plan? Se eliminarán las fechas, los temas, las disponibilidades y las sesiones generadas."
      );

      if (!confirmed) {
        return;
      }
    }

    setStartDate("");
    setExamDate("");
    setReviewSessions(2);

    setTopics([]);
    setAvailability([]);

    setEvents([]);
    setPlanResult(null);
    setSelectedSession(null);

    setError("");
    setLoading(false);
  };

  const escapeIcsText = (text = "") =>
    String(text)
      .replaceAll("\\", "\\\\")
      .replaceAll("\n", "\\n")
      .replaceAll(",", "\\,")
      .replaceAll(";", "\\;");

  const formatIcsDateTime = (dateTime) => {
    const date =
      dateTime instanceof Date
        ? dateTime
        : new Date(dateTime);

    const year = String(date.getFullYear());
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    const hours = String(date.getHours()).padStart(2, "0");
    const minutes = String(date.getMinutes()).padStart(2, "0");
    const seconds = String(date.getSeconds()).padStart(2, "0");

    return `${year}${month}${day}T${hours}${minutes}${seconds}`;
  };

  const formatIcsDate = (dateValue) => {
    const [year, month, day] = dateValue.split("-");

    return `${year}${month}${day}`;
  };

  const createCalendarFileName = () => {
    const safeSubjectName = subjectName
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/[^a-zA-Z0-9_-]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .toLowerCase();

    return `plan-estudio-${safeSubjectName || "studina"}.ics`;
  };

  const downloadStudyPlanCalendar = () => {
    if (!planResult?.sessions?.length) {
      setError("Primero generá un plan de estudio.");
      return;
    }

    const createdAt = formatIcsDateTime(new Date());

    const sessionEvents = planResult.sessions
      .map((session, index) => {
        const typeLabel =
          getSessionTypeLabel(session.type);

        const topic =
          session.topicIndex != null
            ? topics[session.topicIndex]?.title
            : null;

        const descriptionParts = [
          `Tipo: ${typeLabel}`,
          topic ? `Tema: ${topic}` : null,
          session.partNumber != null
            ? `Parte: ${session.partNumber}`
            : null,
          `Materia: ${subjectName}`,
          "Generado por Studina",
        ].filter(Boolean);

        const uid =
          `studina-${Date.now()}-${index}` +
          "@study-planner";

        return [
          "BEGIN:VEVENT",
          `UID:${uid}`,
          `DTSTAMP:${createdAt}`,
          `DTSTART:${formatIcsDateTime(
            session.startDateTime
          )}`,
          `DTEND:${formatIcsDateTime(
            session.endDateTime
          )}`,
          `SUMMARY:${escapeIcsText(session.title)}`,
          `DESCRIPTION:${escapeIcsText(
            descriptionParts.join("\n")
          )}`,
          "STATUS:CONFIRMED",
          "END:VEVENT",
        ].join("\r\n");
      })
      .join("\r\n");

    const examEvent = examDate
      ? [
        "BEGIN:VEVENT",
        `UID:studina-exam-${Date.now()}@study-planner`,
        `DTSTAMP:${createdAt}`,
        `DTSTART;VALUE=DATE:${formatIcsDate(examDate)}`,
        `DTEND;VALUE=DATE:${formatIcsDate(getNextDate(examDate))}`,
        `SUMMARY:${escapeIcsText(
          `Examen — ${subjectName}`
        )}`,
        `DESCRIPTION:${escapeIcsText(
          "Fecha de examen registrada en Studina"
        )}`,
        "STATUS:CONFIRMED",
        "END:VEVENT",
      ].join("\r\n")
      : "";

    const calendarContent = [
      "BEGIN:VCALENDAR",
      "VERSION:2.0",
      "PRODID:-//Studina//Planificador de estudio//ES",
      "CALSCALE:GREGORIAN",
      "METHOD:PUBLISH",
      sessionEvents,
      examEvent,
      "END:VCALENDAR",
    ]
      .filter(Boolean)
      .join("\r\n");

    const blob = new Blob([calendarContent], {
      type: "text/calendar;charset=utf-8",
    });

    const downloadUrl = URL.createObjectURL(blob);
    const link = document.createElement("a");

    link.href = downloadUrl;
    link.download = createCalendarFileName();

    document.body.appendChild(link);
    link.click();
    link.remove();

    URL.revokeObjectURL(downloadUrl);
    setError("");
  };

  const getNextDate = (dateValue) => {
    const [year, month, day] = dateValue
      .split("-")
      .map(Number);

    const date = new Date(year, month - 1, day);
    date.setDate(date.getDate() + 1);

    const nextYear = String(date.getFullYear());
    const nextMonth = String(
      date.getMonth() + 1
    ).padStart(2, "0");
    const nextDay = String(date.getDate()).padStart(2, "0");

    return `${nextYear}-${nextMonth}-${nextDay}`;
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
        <button
          type="button"
          className="planner-export-button"
          onClick={downloadStudyPlanCalendar}
        >
          📆 Descargar calendario
        </button>
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

            <div className="planner-form-actions">
              <button
                type="submit"
                className="planner-primary-button"
                disabled={loading}
              >
                {loading
                  ? "Generando plan..."
                  : "Generar plan"}
              </button>

              <button
                type="button"
                className="planner-reset-button"
                onClick={resetPlanner}
                disabled={loading}
              >
                ↻ Limpiar plan
              </button>
            </div>
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
              eventClick={handleEventClick}
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
      {selectedSession && (
        <div
          className="planner-modal-backdrop"
          onClick={closeSessionModal}
          role="presentation"
        >
          <section
            className="planner-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="planner-session-title"
            onClick={(event) => event.stopPropagation()}
          >
            <button
              type="button"
              className="planner-modal-close"
              onClick={closeSessionModal}
              aria-label="Cerrar detalle de la sesión"
            >
              ✕
            </button>

            <div className="planner-modal-icon">
              {selectedSession.type === "STUDY" && "📚"}
              {selectedSession.type === "REVIEW" && "📝"}
              {selectedSession.type === "MOCK_EXAM" && "🎯"}
            </div>

            <h2
              id="planner-session-title"
              className="planner-modal-title"
            >
              {selectedSession.title}
            </h2>

            <span className="planner-modal-type">
              {getSessionTypeLabel(selectedSession.type)}
            </span>

            <dl className="planner-modal-details">
              <div>
                <dt>Fecha</dt>
                <dd>
                  {formatDate(selectedSession.start)}
                </dd>
              </div>

              <div>
                <dt>Horario</dt>
                <dd>
                  {formatTime(selectedSession.start)}
                  {" a "}
                  {formatTime(selectedSession.end)}
                </dd>
              </div>

              <div>
                <dt>Duración</dt>
                <dd>
                  {Math.round(
                    (selectedSession.end -
                      selectedSession.start) /
                    60000
                  )}{" "}
                  minutos
                </dd>
              </div>

              {selectedSession.topicIndex != null && (
                <div>
                  <dt>Tema</dt>
                  <dd>
                    {topics[selectedSession.topicIndex]?.title ||
                      "Tema de estudio"}
                  </dd>
                </div>
              )}

              {selectedSession.partNumber != null && (
                <div>
                  <dt>Parte</dt>
                  <dd>{selectedSession.partNumber}</dd>
                </div>
              )}
            </dl>

            <button
              type="button"
              className="planner-primary-button"
              onClick={closeSessionModal}
            >
              Cerrar
            </button>
          </section>
        </div>
      )}
    </main>
  );
}