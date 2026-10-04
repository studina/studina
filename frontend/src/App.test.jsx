import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { PomodoroProvider } from './pages/pomodoro/PomodoroContext'
import { clearSession } from './lib/session'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

const unauthorized = () => jsonResponse({ error: 'Unauthorized' }, { ok: false, status: 401 })

const emptyProgress = {
  average_mastery: null,
  practiced_topics: 0,
  strong_topics: 0,
  total_topics: 0,
  coverage: 0,
  total_attempts: 0,
  topics: [],
}

/**
 * El backend de mentira para los dos usuarios del caso.
 *
 * `/auth/refresh` responde 401 porque no hay cookie: es el arranque sin sesión guardada, y
 * la app tiene que mostrar el login en vez de quedarse esperando.
 */
function stubApi({ onUnexpected } = {}) {
  return vi.fn((url, options = {}) => {
    const authorization = options.headers?.Authorization

    if (url === '/api/auth/refresh') return unauthorized()
    if (url === '/api/auth/logout') return jsonResponse({})

    if (url === '/api/auth/login') {
      const { email } = JSON.parse(options.body)
      return jsonResponse(
        email === 'usuario-a@gmail.com'
          ? { userId: 'user-a', name: 'Usuario A', email, token: 'token-a', expiresIn: 900_000 }
          : { userId: 'user-b', name: 'Usuario B', email, token: 'token-b', expiresIn: 900_000 },
      )
    }

    if (url === '/api/subjects') {
      return jsonResponse(
        authorization === 'Bearer token-a'
          ? [{ id: 'subject-a', name: 'Materia de A' }]
          : [{ id: 'subject-b', name: 'Materia de B' }],
      )
    }

    // La pantalla de la materia trae el widget de progreso.
    if (url.startsWith('/api/progress')) return jsonResponse(emptyProgress)

    if (url.startsWith('/api/files/list')) {
      return jsonResponse(
        authorization === 'Bearer token-a'
          ? { data: [{ id: 'file-a', filename: 'Privado_de_A.pdf', tags: [] }], errors: [] }
          : { data: [], errors: [] },
      )
    }

    if (onUnexpected) return onUnexpected(url)
    throw new Error(`Request inesperada: ${url}`)
  })
}

function renderApp() {
  render(
    <PomodoroProvider>
      <App />
    </PomodoroProvider>,
  )
}

async function login(user, email, password) {
  await user.type(await screen.findByPlaceholderText('tu@email.com'), email)
  await user.type(screen.getByPlaceholderText('••••••••'), password)
  await user.click(screen.getAllByRole('button', { name: 'Iniciar sesión' })[1])
  await screen.findByText(email === 'usuario-a@gmail.com' ? 'Usuario A' : 'Usuario B')
}

/** Entra a la materia desde el listado y de ahí a sus documentos. */
async function openDocuments(user, subjectName) {
  await user.click(await screen.findByRole('button', { name: new RegExp(subjectName) }))
  await user.click(await screen.findByRole('button', { name: /Documentos/ }))
}

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    clearSession()
    window.history.pushState({}, '', '/')
  })

  it('no conserva los documentos del usuario anterior después de cerrar sesión', async () => {
    const fetchMock = stubApi()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderApp()

    await login(user, 'usuario-a@gmail.com', 'clave-a')
    await openDocuments(user, 'Materia de A')
    expect(await screen.findByText('Privado_de_A.pdf')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Usuario A/ }))
    await user.click(screen.getByRole('button', { name: /Cerrar sesión/ }))

    await login(user, 'usuario-b@gmail.com', 'clave-b')
    await openDocuments(user, 'Materia de B')

    expect(await screen.findByText('Subí tus primeros materiales')).toBeInTheDocument()
    expect(screen.queryByText('Privado_de_A.pdf')).not.toBeInTheDocument()

    const listCalls = fetchMock.mock.calls.filter(([url]) => url.startsWith('/api/files/list'))
    expect(listCalls).toHaveLength(2)
    expect(listCalls[0][1].headers.Authorization).toBe('Bearer token-a')
    expect(listCalls[1][1].headers.Authorization).toBe('Bearer token-b')
  })

  it('entrar a una materia cambia la URL', async () => {
    vi.stubGlobal('fetch', stubApi())
    const user = userEvent.setup()

    renderApp()

    await login(user, 'usuario-a@gmail.com', 'clave-a')
    await user.click(await screen.findByRole('button', { name: /Materia de A/ }))

    expect(window.location.pathname).toBe('/materias/subject-a')

    await user.click(await screen.findByRole('button', { name: /Documentos/ }))
    expect(window.location.pathname).toBe('/materias/subject-a/documentos')
  })

  it('abre directo la pantalla que pide la URL', async () => {
    // Es lo que hace útil el ruteo: pegar un link lleva a esa pantalla y no al inicio.
    window.history.pushState({}, '', '/materias/subject-a/documentos')
    vi.stubGlobal('fetch', stubApi())
    const user = userEvent.setup()

    renderApp()

    await login(user, 'usuario-a@gmail.com', 'clave-a')

    expect(await screen.findByText('Privado_de_A.pdf')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/materias/subject-a/documentos')
  })

  it('no manda al inicio mientras las materias todavía están cargando', async () => {
    // La regresión del link directo: en el primer render la lista está vacía, y redirigir
    // ahí dejaría el link inservible aunque la materia exista.
    let resolveSubjects
    const subjectsPending = new Promise((resolve) => {
      resolveSubjects = resolve
    })
    const fetchMock = vi.fn((url, options = {}) => {
      if (url === '/api/subjects') return subjectsPending
      return stubApi()(url, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    window.history.pushState({}, '', '/materias/subject-a')
    const user = userEvent.setup()

    renderApp()
    await login(user, 'usuario-a@gmail.com', 'clave-a')

    expect(await screen.findByText(/Cargando tu materia/)).toBeInTheDocument()
    expect(window.location.pathname).toBe('/materias/subject-a')

    resolveSubjects(jsonResponse([{ id: 'subject-a', name: 'Materia de A' }]))

    expect(await screen.findByRole('heading', { name: 'Materia de A' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/materias/subject-a')
  })

  it('manda al inicio una materia que no existe', async () => {
    window.history.pushState({}, '', '/materias/no-existe')
    vi.stubGlobal('fetch', stubApi())
    const user = userEvent.setup()

    renderApp()
    await login(user, 'usuario-a@gmail.com', 'clave-a')

    expect(await screen.findByRole('button', { name: /Materia de A/ })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
  })

  it('recupera la sesión guardada sin pasar por el login', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      // Hay cookie: la renovación funciona y el perfil se completa con /auth/me.
      if (url === '/api/auth/refresh') return jsonResponse({ token: 'token-a', expiresIn: 900_000 })
      if (url === '/api/auth/me') {
        return jsonResponse({ userId: 'user-a', name: 'Usuario A', email: 'usuario-a@gmail.com' })
      }
      return stubApi()(url, options)
    })
    vi.stubGlobal('fetch', fetchMock)

    window.history.pushState({}, '', '/materias/subject-a')
    renderApp()

    expect(await screen.findByRole('heading', { name: 'Materia de A' })).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('tu@email.com')).not.toBeInTheDocument()
  })
})
