import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProfilePage from './ProfilePage'
import { setSession } from '../../lib/session'

const userAccount = {
  userId: 'user-a',
  name: 'Mariana Gómez',
  email: 'mariana@example.com',
  token: 'token-a',
}

const serverSettings = {
  source: 'server',
  provider: 'google-genai',
  chatModel: 'gemini-2.5-flash',
  generationModel: 'gemini-2.5-flash',
  classificationModel: 'gemini-2.5-flash',
  summaryModel: 'gemini-2.5-flash',
  credentialConfigured: false,
}

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

describe('ProfilePage AI settings', () => {
  beforeEach(() => {
    setSession(userAccount)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('permite elegir proveedor, modelos y enviar una credencial propia', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (!options.method) return jsonResponse(serverSettings)
      if (options.method === 'PUT') {
        const saved = JSON.parse(options.body)
        return jsonResponse({
          source: 'user',
          provider: saved.provider,
          chatModel: saved.chatModel,
          generationModel: saved.generationModel,
          classificationModel: saved.classificationModel,
          summaryModel: saved.summaryModel,
          credentialConfigured: true,
        })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const browserUser = userEvent.setup()

    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    await browserUser.selectOptions(
      await screen.findByLabelText('Proveedor'),
      'openai',
    )
    expect(screen.getByLabelText('Modelo para conversar')).toHaveValue('gpt-6-luna')
    expect(within(screen.getByLabelText('Modelo para conversar'))
      .getByRole('option', { name: /GPT-4.1 Mini/ })).toBeInTheDocument()
    expect(screen.getByText(/Con saldo \$0, OpenAI rechazará las consultas/))
      .toBeInTheDocument()

    await browserUser.selectOptions(
      screen.getByLabelText('Modelo para conversar'),
      '__custom__',
    )
    await browserUser.type(
      screen.getByLabelText('Modelo para conversar: modelo personalizado'),
      'gpt-test',
    )
    await browserUser.type(screen.getByLabelText('Tu API key'), 'private-key')
    await browserUser.click(screen.getByRole('button', { name: 'Guardar configuración' }))

    expect(await screen.findByText('Configuración de IA guardada.')).toBeInTheDocument()
    const putCall = fetchMock.mock.calls.find(([, options]) => options.method === 'PUT')
    expect(putCall[0]).toBe('/api/ai/settings')
    expect(putCall[1].headers.Authorization).toBe('Bearer token-a')
    expect(JSON.parse(putCall[1].body)).toEqual(expect.objectContaining({
      provider: 'openai',
      chatModel: 'gpt-test',
      apiKey: 'private-key',
    }))
    expect(screen.getByLabelText('Tu API key')).toHaveValue('')
  })

  it('cambia las opciones de modelos junto con el proveedor', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(serverSettings)))
    const browserUser = userEvent.setup()

    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    const provider = await screen.findByLabelText('Proveedor')
    const chatModel = screen.getByLabelText('Modelo para conversar')

    expect(within(chatModel).getByRole('option', { name: /Gemini 3.8 Flash/ }))
      .toBeInTheDocument()

    await browserUser.selectOptions(provider, 'ollama')
    expect(screen.getByLabelText('Modelo para conversar')).toHaveValue('llama3.2')
    expect(within(screen.getByLabelText('Modelo para conversar'))
      .getByRole('option', { name: 'Gemma 3' })).toBeInTheDocument()

    await browserUser.selectOptions(provider, 'openai')
    expect(screen.getByLabelText('Modelo para conversar')).toHaveValue('gpt-6-luna')
    expect(within(screen.getByLabelText('Modelo para conversar'))
      .queryByRole('option', { name: /Gemini/ })).not.toBeInTheDocument()
  })

  it('nunca muestra la credencial guardada y permite volver a la configuración general', async () => {
    const personalized = {
      ...serverSettings,
      source: 'user',
      credentialConfigured: true,
    }
    const fetchMock = vi.fn((url, options = {}) => {
      if (options.method === 'DELETE') return jsonResponse(serverSettings)
      return jsonResponse(personalized)
    })
    vi.stubGlobal('fetch', fetchMock)
    const browserUser = userEvent.setup()

    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    const credential = await screen.findByLabelText('Tu API key')
    expect(credential).toHaveValue('')
    expect(credential).toHaveAttribute(
      'placeholder',
      'Ya configurada; dejá vacío para conservarla',
    )
    await browserUser.click(screen.getByRole('button', { name: 'Usar configuración general' }))

    expect(await screen.findByText('Volviste a la configuración general de Studina.'))
      .toBeInTheDocument()
    const deleteCall = fetchMock.mock.calls.find(([, options]) => options.method === 'DELETE')
    expect(deleteCall[1].headers.Authorization).toBe('Bearer token-a')
  })

  it('renueva la sesión al cargar y guarda con el token vigente', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url === '/api/auth/refresh') return jsonResponse({
        token: 'renewed-token', expiresIn: 900_000,
      })
      if (url === '/api/auth/logout') return jsonResponse({})
      if (url === '/api/ai/settings') {
        if (options.headers.Authorization === 'Bearer token-a') {
          return jsonResponse({ error: 'La sesión expiró' }, { ok: false, status: 401 })
        }
        return jsonResponse({ ...serverSettings, source: options.method === 'PUT' ? 'user' : 'server' })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const browserUser = userEvent.setup()

    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)
    await screen.findByLabelText('Proveedor')
    await browserUser.click(screen.getByRole('button', { name: 'Guardar configuración' }))

    expect(await screen.findByText('Configuración de IA guardada.')).toBeInTheDocument()
    const putCall = fetchMock.mock.calls.find(([, options]) => options.method === 'PUT')
    expect(putCall[1].headers.Authorization).toBe('Bearer renewed-token')
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1)
  })
})
