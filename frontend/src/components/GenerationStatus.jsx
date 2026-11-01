import { useEffect, useState } from "react";
import { getGenerationDetails } from "../lib/generationQueue";
import { providerLabel } from "../lib/aiProviderLabels";

function formatGenerationDuration(milliseconds) {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000));
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

export function GenerationDetails({ kind, subjectId, resourceId }) {
  const details = getGenerationDetails(kind, subjectId, resourceId);
  const duration = details?.durationMs;
  const model = details?.model;
  const hasDuration = typeof duration === "number" && Number.isFinite(duration) && duration >= 0;
  if (!model && !hasDuration) return null;

  return (
    <span className="library-generation-details">
      {hasDuration && <span>Tiempo total: {formatGenerationDuration(duration)}</span>}
      {model && <span>Modelo configurado: {providerLabel(details.provider)} · {model}</span>}
    </span>
  );
}

export function PendingGeneration({ pending, message }) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  return (
    <div className="library-item library-item--pending" aria-busy="true">
      <div className="library-item-pending">
        <span className="library-item-pending-title">{pending.title}</span>
        <span className="library-item-pending-status">
          <span className="loader" aria-hidden="true" />
          {message}
        </span>
        <span className="library-generation-details">
          <span role="timer" aria-label="Tiempo transcurrido">
            Tiempo transcurrido: {formatGenerationDuration(now - (pending.startedAt ?? now))}
          </span>
          <span>
            {pending.model
              ? `Modelo configurado: ${providerLabel(pending.provider)} · ${pending.model}`
              : "Modelo configurado no disponible"}
          </span>
        </span>
      </div>
    </div>
  );
}
