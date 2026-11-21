import { useEffect, useState } from "react";
import { providerLabel } from "../lib/aiProviderLabels";

function formatGenerationDuration(milliseconds) {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000));
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

export function GenerationDetails({ resource }) {
  const details = resource?.generation_metadata;
  const duration = details?.durationMs;
  const model = details?.model;
  const hasDuration = typeof duration === "number" && Number.isFinite(duration) && duration >= 0;
  if (!model && !hasDuration) return null;

  return (
    <span className="library-generation-details">
      {hasDuration && <span>Tiempo de generación: {formatGenerationDuration(duration)}</span>}
      {model && <span>Modelo usado: {providerLabel(details.provider)} · {model}</span>}
    </span>
  );
}

function RunningGenerationDetails({ pending }) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  return (
    <span className="library-generation-details">
      <span role="timer" aria-label="Tiempo transcurrido">
        Tiempo transcurrido: {formatGenerationDuration(now - pending.startedAt)}
      </span>
      <span>
        {pending.model
          ? `Modelo configurado: ${providerLabel(pending.provider)} · ${pending.model}`
          : "Modelo configurado no disponible"}
      </span>
    </span>
  );
}

export function PendingGeneration({ pending, message }) {
  const queued = pending.status === "queued";

  return (
    <div className="library-item library-item--pending" aria-busy="true">
      <div className="library-item-pending">
        <span className="library-item-pending-title">{pending.title}</span>
        <span className="library-item-pending-status">
          {!queued && <span className="loader" aria-hidden="true" />}
          {queued ? "En espera" : message}
        </span>
        {queued
          ? <span className="library-generation-details">Empieza cuando termine el recurso anterior.</span>
          : <RunningGenerationDetails pending={pending} />}
      </div>
    </div>
  );
}
