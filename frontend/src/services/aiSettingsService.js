import { apiFetch } from '../lib/session'

async function requestAiSettings(url, options = {}) {
  const response = await apiFetch(url, {
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
  return requestAiSettings('/api/ai/settings')
}

export async function loadOllamaModels() {
  const body = await requestAiSettings('/api/ai/models/ollama')
  if (!Array.isArray(body.models)
    || body.models.some((model) => typeof model !== 'string' || !model.trim())) {
    throw new Error('El servidor devolvió una lista de modelos inválida')
  }
  return body.models
}

export function saveAiSettings(settings) {
  return requestAiSettings('/api/ai/settings', {
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}

export function resetAiSettings() {
  return requestAiSettings('/api/ai/settings', { method: 'DELETE' })
}
