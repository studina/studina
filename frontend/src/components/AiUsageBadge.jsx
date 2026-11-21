import './AiUsageBadge.css'
import { providerLabel } from '../lib/aiProviderLabels'

export default function AiUsageBadge({
  provider,
  model,
  source,
  loading = false,
  className = '',
}) {
  if (!loading && !provider) return null

  const label = providerLabel(provider)
  const sourceLabel = source === 'user'
    ? 'Personal'
    : source === 'server' ? 'General' : null

  return (
    <div
      className={`ai-usage-badge ai-usage-badge--${provider || 'loading'} ${className}`.trim()}
      aria-label={loading
        ? 'Consultando proveedor de inteligencia artificial'
        : `Estás usando ${label}, modelo ${model}`}
    >
      {loading ? (
        <span className="ai-usage-badge__loading">Consultando IA...</span>
      ) : (
        <>
          <span className="ai-usage-badge__eyebrow">Estás usando</span>
          <strong className="ai-usage-badge__provider">{label}</strong>
          <span className="ai-usage-badge__model" title={model}>{model}</span>
          {sourceLabel && <span className="ai-usage-badge__source">{sourceLabel}</span>}
        </>
      )}
    </div>
  )
}
