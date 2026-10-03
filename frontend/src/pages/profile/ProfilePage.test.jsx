import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ProfilePage from './ProfilePage'

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
    expect(screen.getByLabelText('Modelo para conversar')).toHaveValue('gpt-4.1-mini')
    await browserUser.clear(screen.getByLabelText('Modelo para conversar'))
    await browserUser.type(screen.getByLabelText('Modelo para conversar'), 'gpt-test')
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
})
