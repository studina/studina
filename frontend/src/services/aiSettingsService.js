import { apiFetch } from '../lib/session'

async function requestAiSettings(options = {}) {
  const response = await apiFetch('/api/ai/settings', {
    ...options,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
    },
  })
  const body = await response.json().catch(() => ({}))

  if (!response.ok) {
    throw new Error(
      body.error
      || body.detail
      || body.message
      || 'No se pudo guardar la configuración de IA',
    )
  }
  return body
}

export function loadAiSettings() {
  return requestAiSettings()
}

export function saveAiSettings(settings) {
  return requestAiSettings({
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}

export function resetAiSettings() {
  return requestAiSettings({ method: 'DELETE' })
}
