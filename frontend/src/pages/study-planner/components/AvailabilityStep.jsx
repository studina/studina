import FullCalendar from "@fullcalendar/react";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";

const REFERENCE_WEEK_START = "2026-01-05";

const DAY_TO_INDEX = {
  MONDAY: 0,
  TUESDAY: 1,
  WEDNESDAY: 2,
  THURSDAY: 3,
  FRIDAY: 4,
  SATURDAY: 5,
  SUNDAY: 6,
};

const INDEX_TO_DAY = {
  0: "MONDAY",
  1: "TUESDAY",
  2: "WEDNESDAY",
  3: "THURSDAY",
  4: "FRIDAY",
  5: "SATURDAY",
  6: "SUNDAY",
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

const padNumber = (value) =>
  String(value).padStart(2, "0");

const formatDate = (date) => {
  const year = date.getFullYear();
  const month = padNumber(date.getMonth() + 1);
  const day = padNumber(date.getDate());

  return `${year}-${month}-${day}`;
};

const formatTime = (date) => {
  const hours = padNumber(date.getHours());
  const minutes = padNumber(date.getMinutes());

  return `${hours}:${minutes}`;
};

const getReferenceDate = (dayOfWeek) => {
  const referenceDate = new Date(
    `${REFERENCE_WEEK_START}T00:00:00`
  );

  referenceDate.setDate(
    referenceDate.getDate() +
      DAY_TO_INDEX[dayOfWeek]
  );

  return formatDate(referenceDate);
};

const buildCalendarEvents = (availability) =>
  availability.map((slot) => {
    const referenceDate = getReferenceDate(
      slot.dayOfWeek
    );

    return {
      id: slot.id,
      title: "Disponible",
      start: `${referenceDate}T${slot.startTime}:00`,
      end: `${referenceDate}T${slot.endTime}:00`,
    };
  });

const rangesOverlap = (
  firstStart,
  firstEnd,
  secondStart,
  secondEnd
) =>
  firstStart < secondEnd &&
  secondStart < firstEnd;

export default function AvailabilityStep({
  availability,
  error,
  onAddAvailability,
  onUpdateAvailability,
  onRemoveAvailability,
}) {
  const calendarEvents =
    buildCalendarEvents(availability);

  const selectionOverlaps = (
    start,
    end,
    ignoredId = null
  ) =>
    calendarEvents.some((event) => {
      if (event.id === ignoredId) {
        return false;
      }

      const eventStart = new Date(event.start);
      const eventEnd = new Date(event.end);

      return rangesOverlap(
        start,
        end,
        eventStart,
        eventEnd
      );
    });

  const handleSelect = (selectionInfo) => {
    if (
      selectionInfo.start.getDay() !==
      selectionInfo.end.getDay()
    ) {
      selectionInfo.view.calendar.unselect();
      return;
    }

    if (
      selectionOverlaps(
        selectionInfo.start,
        selectionInfo.end
      )
    ) {
      window.alert(
        "La franja seleccionada se superpone con otra disponibilidad."
      );

      selectionInfo.view.calendar.unselect();
      return;
    }

    const dayIndex =
      selectionInfo.start.getDay() === 0
        ? 6
        : selectionInfo.start.getDay() - 1;

    onAddAvailability({
      id: crypto.randomUUID(),
      dayOfWeek: INDEX_TO_DAY[dayIndex],
      startTime: formatTime(
        selectionInfo.start
      ),
      endTime: formatTime(selectionInfo.end),
    });

    selectionInfo.view.calendar.unselect();
  };

  const updateFromCalendarEvent = (
    event
  ) => {
    if (!event.start || !event.end) {
      return false;
    }

    if (
      selectionOverlaps(
        event.start,
        event.end,
        event.id
      )
    ) {
      window.alert(
        "Esa franja se superpone con otra disponibilidad."
      );

      return false;
    }

    const dayIndex =
      event.start.getDay() === 0
        ? 6
        : event.start.getDay() - 1;

    onUpdateAvailability(event.id, {
      dayOfWeek: INDEX_TO_DAY[dayIndex],
      startTime: formatTime(event.start),
      endTime: formatTime(event.end),
    });

    return true;
  };

  const handleEventDrop = (dropInfo) => {
    const wasUpdated =
      updateFromCalendarEvent(
        dropInfo.event
      );

    if (!wasUpdated) {
      dropInfo.revert();
    }
  };

  const handleEventResize = (resizeInfo) => {
    const wasUpdated =
      updateFromCalendarEvent(
        resizeInfo.event
      );

    if (!wasUpdated) {
      resizeInfo.revert();
    }
  };

  const handleEventClick = (clickInfo) => {
    const shouldRemove = window.confirm(
      "¿Querés eliminar esta franja de disponibilidad?"
    );

    if (shouldRemove) {
      onRemoveAvailability(
        clickInfo.event.id
      );
    }
  };

  return (
    <div className="availability-step">
      <section className="wizard-form-section">
        <div className="wizard-section-heading">
          <h3>Disponibilidad semanal</h3>

          <p>
            Arrastrá para crear · Mové o estirá para editar · Clic para eliminar
          </p>
        </div>

        <div className="availability-calendar">
          <FullCalendar
            plugins={[
              timeGridPlugin,
              interactionPlugin,
            ]}
            initialView="timeGridWeek"
            initialDate={REFERENCE_WEEK_START}
            headerToolbar={false}
            events={calendarEvents}
            selectable
            selectMirror
            editable
            eventStartEditable
            eventDurationEditable
            allDaySlot={false}
            firstDay={1}
            slotMinTime="06:00:00"
            slotMaxTime="24:00:00"
            slotDuration="00:30:00"
            slotLabelInterval="01:00:00"
            snapDuration="00:30:00"
            selectMinDistance={5}
            select={handleSelect}
            eventDrop={handleEventDrop}
            eventResize={handleEventResize}
            eventClick={handleEventClick}
            height={520}
            locale="es"
            dayHeaderFormat={{
              weekday: "long",
            }}
            slotLabelFormat={{
              hour: "2-digit",
              minute: "2-digit",
              hour12: false,
            }}
            eventTimeFormat={{
              hour: "2-digit",
              minute: "2-digit",
              hour12: false,
            }}
          />
        </div>
      </section>

      <section className="availability-summary">
        <div>
          <strong>
            Disponibilidad cargada
          </strong>

          <p>
            {availability.length === 0
              ? "Todavía no seleccionaste ninguna franja."
              : availability.length === 1
                ? "Tenés una franja semanal."
                : `Tenés ${availability.length} franjas semanales.`}
          </p>
        </div>

        {availability.length > 0 && (
          <ul className="availability-summary-list">
            {availability.map((slot) => (
              <li key={slot.id}>
                <strong>
                  {DAY_LABELS[slot.dayOfWeek]}
                </strong>

                <span>
                  {slot.startTime}–{slot.endTime}
                </span>
              </li>
            ))}
          </ul>
        )}
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