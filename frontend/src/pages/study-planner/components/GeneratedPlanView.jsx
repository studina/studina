import { useState } from "react";
import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";

export default function GeneratedPlanView({
    subject,
    milestone,
    topics,
    examDate,
    planResult,
    onEdit,
    onBack,
}) {
    const subjectName =
        subject?.name ||
        subject?.title ||
        subject?.nombre ||
        "Materia seleccionada";
    const milestoneTitle =
        milestone?.title?.trim() ||
        "Hito académico";

    const [selectedSession, setSelectedSession] =
        useState(null);

    const sessionEvents =
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
    const milestoneEvent = examDate
        ? {
            id: "milestone",
            title: `🎯 ${milestoneTitle}`,
            start: examDate,
            allDay: true,
            classNames: [
                "generated-plan-milestone-event",
            ],
            extendedProps: {
                isMilestone: true,
            },
        }
        : null;
    const calendarEvents = milestoneEvent
        ? [...sessionEvents, milestoneEvent]
        : sessionEvents;

    const formatDate = (date) =>
        new Intl.DateTimeFormat("es-AR", {
            weekday: "long",
            day: "2-digit",
            month: "long",
            year: "numeric",
        }).format(date);

    const formatTime = (date) =>
        new Intl.DateTimeFormat("es-AR", {
            hour: "2-digit",
            minute: "2-digit",
            hour12: false,
        }).format(date);

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

        if (event.extendedProps.isMilestone) {
            return;
        }

        setSelectedSession({
            title: event.title,
            start: event.start,
            end: event.end,
            type: event.extendedProps.type,
            topicIndex:
                event.extendedProps.topicIndex,
            partNumber:
                event.extendedProps.partNumber,
        });
    };

    const closeSessionModal = () => {
        setSelectedSession(null);
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
        const month = String(
            date.getMonth() + 1
        ).padStart(2, "0");
        const day = String(date.getDate()).padStart(
            2,
            "0"
        );
        const hours = String(date.getHours()).padStart(
            2,
            "0"
        );
        const minutes = String(
            date.getMinutes()
        ).padStart(2, "0");
        const seconds = String(
            date.getSeconds()
        ).padStart(2, "0");

        return (
            `${year}${month}${day}` +
            `T${hours}${minutes}${seconds}`
        );
    };

    const formatIcsDate = (dateValue) =>
        dateValue.replaceAll("-", "");

    const getNextDate = (dateValue) => {
        const [year, month, day] = dateValue
            .split("-")
            .map(Number);

        const date = new Date(
            year,
            month - 1,
            day
        );

        date.setDate(date.getDate() + 1);

        return [
            date.getFullYear(),
            String(date.getMonth() + 1).padStart(
                2,
                "0"
            ),
            String(date.getDate()).padStart(
                2,
                "0"
            ),
        ].join("-");
    };

    const createCalendarFileName = () => {
        const safeSubjectName = subjectName
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")
            .replace(/[^a-zA-Z0-9_-]+/g, "-")
            .replace(/^-+|-+$/g, "")
            .toLowerCase();

        return (
            `plan-estudio-` +
            `${safeSubjectName || "studina"}.ics`
        );
    };

    const downloadCalendar = () => {
        if (!planResult.sessions?.length) {
            return;
        }

        const createdAt =
            formatIcsDateTime(new Date());

        const sessionEvents =
            planResult.sessions
                .map((session, index) => {
                    const typeLabel =
                        getSessionTypeLabel(
                            session.type
                        );

                    const topic =
                        session.topicIndex != null
                            ? topics[
                                session.topicIndex
                            ]?.title
                            : null;

                    const description = [
                        `Tipo: ${typeLabel}`,
                        topic
                            ? `Tema: ${topic}`
                            : null,
                        session.partNumber != null
                            ? `Parte: ${session.partNumber}`
                            : null,
                        `Materia: ${subjectName}`,
                        "Generado por Studina",
                    ]
                        .filter(Boolean)
                        .join("\n");

                    return [
                        "BEGIN:VEVENT",
                        `UID:studina-${Date.now()}-${index}@study-planner`,
                        `DTSTAMP:${createdAt}`,
                        `DTSTART:${formatIcsDateTime(
                            session.startDateTime
                        )}`,
                        `DTEND:${formatIcsDateTime(
                            session.endDateTime
                        )}`,
                        `SUMMARY:${escapeIcsText(
                            session.title
                        )}`,
                        `DESCRIPTION:${escapeIcsText(
                            description
                        )}`,
                        "STATUS:CONFIRMED",
                        "END:VEVENT",
                    ].join("\r\n");
                })
                .join("\r\n");

        const examEvent = examDate
            ? [
                "BEGIN:VEVENT",
                `UID:studina-hito-${Date.now()}@study-planner`,
                `DTSTAMP:${createdAt}`,
                `DTSTART;VALUE=DATE:${formatIcsDate(
                    examDate
                )}`,
                `DTEND;VALUE=DATE:${formatIcsDate(
                    getNextDate(examDate)
                )}`,
                `SUMMARY:${escapeIcsText(
                    `Hito — ${milestoneTitle}`
                )}`,
                `DESCRIPTION:${escapeIcsText(
                    "Fecha del hito registrada en Studina"
                )}`,
                "STATUS:CONFIRMED",
                "END:VEVENT",
            ].join("\r\n")
            : "";

        const calendarContent = [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Studina//Plan de estudio//ES",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            sessionEvents,
            examEvent,
            "END:VCALENDAR",
        ]
            .filter(Boolean)
            .join("\r\n");

        const blob = new Blob(
            [calendarContent],
            {
                type: "text/calendar;charset=utf-8",
            }
        );

        const url = URL.createObjectURL(blob);
        const link =
            document.createElement("a");

        link.href = url;
        link.download =
            createCalendarFileName();

        document.body.appendChild(link);
        link.click();
        link.remove();

        URL.revokeObjectURL(url);
    };

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

                <div className="generated-plan-header-actions">
                    <button
                        type="button"
                        className="generated-plan-download-button"
                        onClick={downloadCalendar}
                    >
                        📅 Descargar calendario
                    </button>

                    <button
                        type="button"
                        className="generated-plan-edit-button"
                        onClick={onEdit}
                    >
                        Editar configuración
                    </button>
                </div>
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
                    allDaySlot
                    allDayText="Hito"
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
                    eventClick={handleEventClick}
                />
            </section>
            {selectedSession && (
                <div
                    className="generated-session-backdrop"
                    onClick={closeSessionModal}
                    role="presentation"
                >
                    <section
                        className="generated-session-modal"
                        role="dialog"
                        aria-modal="true"
                        aria-labelledby="generated-session-title"
                        onClick={(event) =>
                            event.stopPropagation()
                        }
                    >
                        <button
                            type="button"
                            className="generated-session-close"
                            onClick={closeSessionModal}
                            aria-label="Cerrar detalle"
                        >
                            ✕
                        </button>

                        <span className="generated-session-type">
                            {getSessionTypeLabel(
                                selectedSession.type
                            )}
                        </span>

                        <h2
                            id="generated-session-title"
                            className="generated-session-title"
                        >
                            {selectedSession.title}
                        </h2>

                        <dl className="generated-session-details">
                            <div>
                                <dt>Fecha</dt>

                                <dd>
                                    {formatDate(
                                        selectedSession.start
                                    )}
                                </dd>
                            </div>

                            <div>
                                <dt>Horario</dt>

                                <dd>
                                    {formatTime(
                                        selectedSession.start
                                    )}
                                    {" a "}
                                    {formatTime(
                                        selectedSession.end
                                    )}
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

                            {selectedSession.topicIndex !=
                                null && (
                                    <div>
                                        <dt>Tema</dt>

                                        <dd>
                                            {topics[
                                                selectedSession.topicIndex
                                            ]?.title ||
                                                "Tema de estudio"}
                                        </dd>
                                    </div>
                                )}

                            {selectedSession.partNumber !=
                                null && (
                                    <div>
                                        <dt>Parte</dt>

                                        <dd>
                                            {selectedSession.partNumber}
                                        </dd>
                                    </div>
                                )}
                        </dl>

                        <button
                            type="button"
                            className="wizard-primary-button"
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
