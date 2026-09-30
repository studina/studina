import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { PomodoroProvider } from './pages/pomodoro/PomodoroContext'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

describe('App sessions', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('no conserva los documentos del usuario anterior después de cerrar sesión', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url === '/api/auth/login') {
        const { email } = JSON.parse(options.body)
        return jsonResponse(email === 'usuario-a@gmail.com'
          ? {
              userId: 'user-a',
              name: 'Usuario A',
              email,
              role: 'student',
              token: 'token-a',
            }
          : {
              userId: 'user-b',
              name: 'Usuario B',
              email,
              role: 'student',
              token: 'token-b',
            })
      }

    if (url === '/api/subjects') {
      return jsonResponse(options.headers.Authorization === 'Bearer token-a'
        ? [{ id: 'subject-a', name: 'Materia de A' }]
        : [{ id: 'subject-b', name: 'Materia de B' }])
    }

      if (url.startsWith('/api/files/list')) {
        return jsonResponse(options.headers.Authorization === 'Bearer token-a'
          ? {
              data: [{ id: 'file-a', filename: 'Privado_de_A.pdf', tags: [] }],
              errors: [],
            }
          : { data: [], errors: [] })
      }

      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <PomodoroProvider>
        <App />
      </PomodoroProvider>
    )

    await login(user, 'usuario-a@gmail.com', 'clave-a')
	await user.click(await screen.findByRole('button', { name: /Materia de A/ }))
    await user.click(await screen.findByRole('button', { name: /Documentos/ }))
    expect(window.location.pathname).toBe('/documentos')
	expect(await screen.findByText('Privado_de_A.pdf', {}, { timeout: 2500 })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Usuario A/ }))
    await user.click(screen.getByRole('button', { name: /Cerrar sesión/ }))

    await login(user, 'usuario-b@gmail.com', 'clave-b')
	await user.click(await screen.findByRole('button', { name: /Materia de B/ }))
    await user.click(await screen.findByRole('button', { name: /Documentos/ }))

	expect(await screen.findByText('Subí tus primeros materiales', {}, { timeout: 2500 })).toBeInTheDocument()
    expect(screen.queryByText('Privado_de_A.pdf')).not.toBeInTheDocument()

    const listCalls = fetchMock.mock.calls.filter(([url]) => url.startsWith('/api/files/list'))
    expect(listCalls).toHaveLength(2)
    expect(listCalls[0][1].headers.Authorization).toBe('Bearer token-a')
    expect(listCalls[1][1].headers.Authorization).toBe('Bearer token-b')
  })
})

async function login(user, email, password) {
  await user.type(screen.getByPlaceholderText('tu@email.com'), email)
  await user.type(screen.getByPlaceholderText('••••••••'), password)
  await user.click(screen.getAllByRole('button', { name: 'Iniciar sesión' })[1])
  await screen.findByText(email === 'usuario-a@gmail.com' ? 'Usuario A' : 'Usuario B')
}
