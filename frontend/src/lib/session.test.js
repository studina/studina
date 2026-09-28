import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, clearSession, onSessionExpired, setSession } from './session'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) })
}

const unauthorized = () => jsonResponse({ error: 'Unauthorized' }, { ok: false, status: 401 })

/** La sesión recién abierta, con el token válido por los 15 minutos de Directus. */
function openSession(overrides = {}) {
  setSession({ token: 'token-viejo', refreshToken: 'refresh-1', expiresIn: 900_000, ...overrides })
}

function authorizationOf(call) {
  return call[1].headers.Authorization
}

describe('apiFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    clearSession()
  })

  it('manda el token de la sesión sin que quien llama tenga que armarlo', async () => {
    const fetchMock = vi.fn(() => jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    openSession()

    await apiFetch('/api/flashcards/set-1/attempts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer token-viejo' },
    })
  })

  // El caso que rompía: la ronda de flashcards duraba más que los 15 minutos del token
  // y las autoevaluaciones empezaban a volver 401 sin que nadie reintentara.
  it('renueva y reintenta cuando el token venció a mitad de la sesión', async () => {
    const fetchMock = vi.fn((url, options) => {
      if (url === '/api/auth/refresh') {
        return jsonResponse({ token: 'token-nuevo', refreshToken: 'refresh-2', expiresIn: 900_000 })
      }
      return options.headers.Authorization === 'Bearer token-nuevo'
        ? jsonResponse({ saved: true })
        : unauthorized()
    })
    vi.stubGlobal('fetch', fetchMock)
    openSession()

    const response = await apiFetch('/api/flashcards/set-1/attempts', { method: 'POST' })

    expect(response.ok).toBe(true)
    const attemptCalls = fetchMock.mock.calls.filter(([url]) => url !== '/api/auth/refresh')
    expect(attemptCalls.map(authorizationOf)).toEqual([
      'Bearer token-viejo',
      'Bearer token-nuevo',
    ])
  })

  it('renueva antes de que el token venza, sin gastar un 401', async () => {
    const fetchMock = vi.fn((url) => (url === '/api/auth/refresh'
      ? jsonResponse({ token: 'token-nuevo', refreshToken: 'refresh-2', expiresIn: 900_000 })
      : jsonResponse({ saved: true })))
    vi.stubGlobal('fetch', fetchMock)
    // Le quedan 30 segundos: menos que el margen, así que se renueva de entrada.
    openSession({ expiresIn: 30_000 })

    await apiFetch('/api/progress?subjectId=3')

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/auth/refresh',
      '/api/progress?subjectId=3',
    ])
    expect(authorizationOf(fetchMock.mock.calls[1])).toBe('Bearer token-nuevo')
  })

  // Directus rota el refresh token en cada renovación. Si dos pantallas renovaran en
  // paralelo, la segunda mandaría uno ya consumido y cerraría una sesión sana.
  it('comparte una sola renovación entre requests simultáneos', async () => {
    const fetchMock = vi.fn((url) => (url === '/api/auth/refresh'
      ? jsonResponse({ token: 'token-nuevo', refreshToken: 'refresh-2', expiresIn: 900_000 })
      : jsonResponse({ ok: true })))
    vi.stubGlobal('fetch', fetchMock)
    openSession({ expiresIn: 30_000 })

    await Promise.all([
      apiFetch('/api/subjects'),
      apiFetch('/api/progress?subjectId=3'),
      apiFetch('/api/flashcards?subjectId=3'),
    ])

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => url === '/api/auth/refresh')
    expect(refreshCalls).toHaveLength(1)
  })

  it('avisa que la sesión se terminó cuando el refresh token tampoco sirve', async () => {
    const fetchMock = vi.fn((url) => (url === '/api/auth/refresh'
      ? jsonResponse({ error: 'La sesión expiró' }, { ok: false, status: 401 })
      : unauthorized()))
    vi.stubGlobal('fetch', fetchMock)
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const expired = vi.fn()
    const unsubscribe = onSessionExpired(expired)
    openSession()

    const response = await apiFetch('/api/subjects')

    // Se devuelve el 401 original: quien llamó decide qué mostrar.
    expect(response.status).toBe(401)
    expect(expired).toHaveBeenCalledTimes(1)

    // Y sin sesión, no se vuelve a intentar renovar en cada request.
    await apiFetch('/api/subjects')
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1)

    unsubscribe()
  })

  it('sin refresh token no inventa una renovación y devuelve el 401', async () => {
    const fetchMock = vi.fn(() => unauthorized())
    vi.stubGlobal('fetch', fetchMock)
    setSession({ token: 'token-suelto' })

    const response = await apiFetch('/api/subjects')

    expect(response.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
