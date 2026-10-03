async function requestAiSettings(token, options = {}) {
  const response = await fetch('/api/ai/settings', {
    ...options,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      Authorization: `Bearer ${token}`,
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

export function loadAiSettings(token) {
  return requestAiSettings(token)
}

export function saveAiSettings(token, settings) {
  return requestAiSettings(token, {
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}

export function resetAiSettings(token) {
  return requestAiSettings(token, { method: 'DELETE' })
}
