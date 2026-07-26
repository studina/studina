import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AuthPage from './AuthPage'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

describe('AuthPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('entrega al resto de la app los datos completos devueltos por el login', async () => {
    const authenticatedUser = {
      userId: 'user-1',
      name: 'six seven',
      email: 'sixseven@gmail.com',
      role: 'student',
      token: 'token-1',
    }
    const onEnter = vi.fn()
    const fetchMock = vi.fn(() => jsonResponse(authenticatedUser))
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    render(<AuthPage onEnter={onEnter} />)

    await user.type(screen.getByPlaceholderText('tu@email.com'), authenticatedUser.email)
    await user.type(screen.getByPlaceholderText('••••••••'), 'sixseven')
    await user.click(screen.getAllByRole('button', { name: 'Iniciar sesión' })[1])

    expect(fetchMock).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        email: authenticatedUser.email,
        password: 'sixseven',
      }),
    }))
    expect(onEnter).toHaveBeenCalledWith(authenticatedUser)
  })

  it('muestra el mensaje del backend cuando las credenciales son incorrectas', async () => {
    const onEnter = vi.fn()
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(
      { error: 'Email o contraseña incorrectos' },
      { ok: false, status: 401 },
    )))

    const user = userEvent.setup()
    render(<AuthPage onEnter={onEnter} />)

    await user.type(screen.getByPlaceholderText('tu@email.com'), 'hola@gmail.com')
    await user.type(screen.getByPlaceholderText('••••••••'), 'incorrecta')
    await user.click(screen.getAllByRole('button', { name: 'Iniciar sesión' })[1])

    expect(await screen.findByText('Email o contraseña incorrectos')).toBeInTheDocument()
    expect(onEnter).not.toHaveBeenCalled()
  })
})
