import { fireEvent, render, screen, within } from '@testing-library/react'
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

const ollamaSettings = {
  ...serverSettings,
  source: 'user',
  provider: 'ollama',
  chatModel: 'llama3.2',
  generationModel: 'llama3.2',
  classificationModel: 'llama3.2',
  summaryModel: 'llama3.2',
}

const installedModels = ['llama3.2:latest', 'qwen2.5:7b']

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

  it('muestra el error si no se puede cargar la configuración inicial', async () => {
    vi.stubGlobal('fetch', vi.fn((url) => {
      if (url === '/api/ai/settings') {
        return jsonResponse({ error: 'El servicio de configuración no está disponible' }, {
          ok: false, status: 503,
        })
      }
      if (url === '/api/auth/logout') return jsonResponse({})
      throw new Error(`Request inesperada: ${url}`)
    }))

    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('El servicio de configuración no está disponible')
    expect(screen.queryByRole('button', { name: 'Guardar configuración' })).not.toBeInTheDocument()
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

  it.each([
    ['google-genai', 'Google Gemini'],
    ['openai', 'OpenAI'],
  ])('requiere una API key personal no vacía para %s', async (provider, label) => {
    const fetchMock = vi.fn(() => jsonResponse(serverSettings))
    vi.stubGlobal('fetch', fetchMock)
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    await browserUser.selectOptions(await screen.findByLabelText('Proveedor'), provider)
    const credential = screen.getByLabelText('Tu API key')
    const saveButton = screen.getByRole('button', { name: 'Guardar configuración' })
    const help = `Ingresá tu API key de ${label} para guardar una configuración personal.`

    expect(credential).toBeRequired()
    expect(credential).toHaveAccessibleDescription(help)
    expect(saveButton).toBeDisabled()
    await browserUser.click(saveButton)
    expect(fetchMock.mock.calls.filter(([, options]) => options.method === 'PUT')).toHaveLength(0)

    await browserUser.type(credential, '   ')
    expect(saveButton).toBeDisabled()
    fireEvent.submit(saveButton.closest('form'))
    expect(screen.getByRole('alert')).toHaveTextContent(help)
    expect(fetchMock.mock.calls.filter(([, options]) => options.method === 'PUT')).toHaveLength(0)

    await browserUser.type(credential, 'private-key')
    expect(saveButton).toBeEnabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it.each(['google-genai', 'openai'])(
    'conserva la API key guardada de %s al actualizar sólo un modelo',
    async (provider) => {
      const personalized = { ...serverSettings, source: 'user', provider, credentialConfigured: true }
      const fetchMock = vi.fn((url, options = {}) => jsonResponse(options.method === 'PUT'
        ? { ...personalized, chatModel: JSON.parse(options.body).chatModel }
        : personalized))
      vi.stubGlobal('fetch', fetchMock)
      const browserUser = userEvent.setup()
      render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

      const credential = await screen.findByLabelText('Tu API key')
      expect(credential).not.toBeRequired()
      expect(credential).toHaveValue('')
      await browserUser.selectOptions(screen.getByLabelText('Modelo para conversar'), '__custom__')
      await browserUser.type(screen.getByLabelText('Modelo para conversar: modelo personalizado'), 'model-updated')
      await browserUser.click(screen.getByRole('button', { name: 'Guardar configuración' }))

      expect(await screen.findByText('Configuración de IA guardada.')).toBeInTheDocument()
      const putCall = fetchMock.mock.calls.find(([, options]) => options.method === 'PUT')
      expect(JSON.parse(putCall[1].body)).toEqual(expect.objectContaining({
        provider, chatModel: 'model-updated', apiKey: '',
      }))
      expect(screen.getByLabelText('Tu API key')).toHaveValue('')
      expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled()
    },
  )

  it.each([
    ['google-genai', 'openai'],
    ['openai', 'google-genai'],
  ])('no reutiliza la credencial de %s al cambiar a %s', async (savedProvider, nextProvider) => {
    const fetchMock = vi.fn(() => jsonResponse({
      ...serverSettings, source: 'user', provider: savedProvider, credentialConfigured: true,
    }))
    vi.stubGlobal('fetch', fetchMock)
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    const provider = await screen.findByLabelText('Proveedor')
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled()
    await browserUser.selectOptions(provider, nextProvider)
    expect(screen.getByLabelText('Tu API key')).toBeRequired()
    expect(screen.getByLabelText('Tu API key')).toHaveAttribute('placeholder', 'Pegá acá tu API key')
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeDisabled()
    expect(fetchMock.mock.calls.filter(([, options]) => options.method === 'PUT')).toHaveLength(0)

    await browserUser.selectOptions(provider, savedProvider)
    expect(screen.getByLabelText('Tu API key')).not.toBeRequired()
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled()
  })

  it.each([
    [{ detail: 'Tenés que ingresar una API key para google-genai' }, 'Tenés que ingresar una API key para google-genai'],
    [{ reason: 'El modelo elegido no está disponible' }, 'El modelo elegido no está disponible'],
    [{ message: 'La API key no es válida' }, 'La API key no es válida'],
    [{}, 'Revisá el proveedor, los modelos y la API key de la configuración de IA.'],
  ])('muestra un error de validación comprensible: %s', async (details, expected) => {
    const fetchMock = vi.fn((url, options = {}) => options.method === 'PUT'
      ? jsonResponse({ error: 'Bad Request', ...details }, { ok: false, status: 400 })
      : jsonResponse(serverSettings))
    vi.stubGlobal('fetch', fetchMock)
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    await browserUser.type(await screen.findByLabelText('Tu API key'), 'private-key')
    await browserUser.click(screen.getByRole('button', { name: 'Guardar configuración' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(expected)
    expect(screen.getByRole('alert')).not.toHaveTextContent('Bad Request')
  })

  it('cambia las opciones de modelos junto con el proveedor', async () => {
    vi.stubGlobal('fetch', vi.fn((url) => url === '/api/ai/models/ollama'
      ? jsonResponse({ models: installedModels })
      : jsonResponse(serverSettings)))
    const browserUser = userEvent.setup()

    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    const provider = await screen.findByLabelText('Proveedor')
    const chatModel = screen.getByLabelText('Modelo para conversar')

    expect(within(chatModel).getByRole('option', { name: /Gemini 3.8 Flash/ }))
      .toBeInTheDocument()

    await browserUser.selectOptions(provider, 'ollama')
    await vi.waitFor(() => expect(screen.getByLabelText('Modelo para conversar'))
      .toHaveValue('llama3.2:latest'))
    expect(within(screen.getByLabelText('Modelo para conversar'))
      .getByRole('option', { name: 'qwen2.5:7b' })).toBeInTheDocument()
    expect(within(screen.getByLabelText('Modelo para conversar'))
      .queryByRole('option', { name: 'Gemma 3' })).not.toBeInTheDocument()

    await browserUser.selectOptions(provider, 'openai')
    expect(screen.getByLabelText('Modelo para conversar')).toHaveValue('gpt-6-luna')
    expect(within(screen.getByLabelText('Modelo para conversar'))
      .queryByRole('option', { name: /Gemini/ })).not.toBeInTheDocument()
  })

  it('muestra los modelos del servidor en las cuatro tareas y permite guardar Qwen', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url === '/api/ai/models/ollama') return jsonResponse({ models: installedModels })
      if (options.method === 'PUT') {
        const saved = JSON.parse(options.body)
        return jsonResponse({ ...saved, source: 'user', credentialConfigured: false })
      }
      return jsonResponse(serverSettings)
    })
    vi.stubGlobal('fetch', fetchMock)
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    await browserUser.selectOptions(await screen.findByLabelText('Proveedor'), 'ollama')
    await vi.waitFor(() => expect(screen.getByLabelText('Modelo para conversar'))
      .toHaveValue('llama3.2:latest'))
    for (const label of ['Modelo para conversar', 'Flashcards y quizzes', 'Clasificar documentos', 'Resumir conversaciones']) {
      expect(screen.getByLabelText(label)).toHaveValue('llama3.2:latest')
      expect(within(screen.getByLabelText(label)).getByRole('option', { name: 'qwen2.5:7b' }))
        .toBeInTheDocument()
      expect(within(screen.getByLabelText(label)).queryByRole('option', { name: 'Otro modelo compatible…' }))
        .not.toBeInTheDocument()
      await browserUser.selectOptions(screen.getByLabelText(label), 'qwen2.5:7b')
    }
    expect(screen.queryByLabelText('Tu API key')).not.toBeInTheDocument()
    await browserUser.click(screen.getByRole('button', { name: 'Guardar configuración' }))

    expect(await screen.findByText('Configuración de IA guardada.')).toBeInTheDocument()
    const saved = JSON.parse(fetchMock.mock.calls.find(([, options]) => options.method === 'PUT')[1].body)
    expect(saved).toEqual(expect.objectContaining({
      provider: 'ollama', chatModel: 'qwen2.5:7b', generationModel: 'qwen2.5:7b',
      classificationModel: 'qwen2.5:7b', summaryModel: 'qwen2.5:7b', apiKey: '',
    }))
    expect(fetchMock.mock.calls.find(([url]) => url === '/api/ai/models/ollama')[1].headers.Authorization)
      .toBe('Bearer token-a')
  })

  it('explica una instalación vacía y no permite guardar modelos que no existen', async () => {
    vi.stubGlobal('fetch', vi.fn((url) => url === '/api/ai/models/ollama'
      ? jsonResponse({ models: [] }) : jsonResponse(ollamaSettings)))
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    expect(await screen.findByText(/No hay modelos descargados/)).toBeInTheDocument()
    expect(screen.getByLabelText('Modelo para conversar')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Actualizar modelos' })).toBeEnabled()
    expect(screen.getByLabelText('Proveedor')).toBeEnabled()
  })

  it('permite reintentar cuando Ollama no está disponible', async () => {
    let attempts = 0
    vi.stubGlobal('fetch', vi.fn((url) => {
      if (url === '/api/ai/models/ollama') {
        attempts += 1
        return attempts === 1
          ? jsonResponse({ error: 'Ollama no está disponible' }, { ok: false, status: 503 })
          : jsonResponse({ models: installedModels })
      }
      return jsonResponse(ollamaSettings)
    }))
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Ollama no está disponible')
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeDisabled()
    await browserUser.click(screen.getByRole('button', { name: 'Actualizar modelos' }))
    await vi.waitFor(() => expect(screen.getByLabelText('Modelo para conversar'))
      .toHaveValue('llama3.2:latest'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled()
    expect(attempts).toBe(2)
  })

  it('reconoce las etiquetas latest sin cambiar la configuración guardada', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url === '/api/ai/models/ollama') return jsonResponse({ models: installedModels })
      return jsonResponse({ ...ollamaSettings, ...(options.method === 'PUT' ? JSON.parse(options.body) : {}) })
    })
    vi.stubGlobal('fetch', fetchMock)
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    await vi.waitFor(() => expect(screen.getByLabelText('Modelo para conversar'))
      .toHaveValue('llama3.2:latest'))
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled()
    await browserUser.click(screen.getByRole('button', { name: 'Guardar configuración' }))
    expect(await screen.findByText('Configuración de IA guardada.')).toBeInTheDocument()
    expect(JSON.parse(fetchMock.mock.calls.find(([, options]) => options.method === 'PUT')[1].body).chatModel)
      .toBe('llama3.2')
  })

  it('conserva un modelo eliminado como no disponible hasta que el usuario elige otro', async () => {
    vi.stubGlobal('fetch', vi.fn((url) => url === '/api/ai/models/ollama'
      ? jsonResponse({ models: installedModels })
      : jsonResponse({ ...ollamaSettings, chatModel: 'llama3.1' })))
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('ya no está instalado')
    expect(screen.getByRole('option', { name: 'llama3.1 (no instalado)' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeDisabled()
    await browserUser.selectOptions(screen.getByLabelText('Modelo para conversar'), 'qwen2.5:7b')
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('actualiza el catálogo después de descargar un modelo y detecta los eliminados', async () => {
    let calls = 0
    vi.stubGlobal('fetch', vi.fn((url) => {
      if (url === '/api/ai/models/ollama') {
        calls += 1
        return jsonResponse({ models: calls === 1 ? ['llama3.2:latest'] : ['qwen2.5:7b'] })
      }
      return jsonResponse(ollamaSettings)
    }))
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled())
    await browserUser.click(screen.getByRole('button', { name: 'Actualizar modelos' }))
    expect(await within(screen.getByLabelText('Modelo para conversar')).findByRole('option', { name: 'qwen2.5:7b' }))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeDisabled()
    expect(screen.getByLabelText('Modelo para conversar')).toHaveValue('llama3.2')
  })

  it('ignora una consulta pendiente si el usuario cambia de proveedor', async () => {
    let resolveCatalog
    const pending = new Promise((resolve) => { resolveCatalog = resolve })
    vi.stubGlobal('fetch', vi.fn((url) => url === '/api/ai/models/ollama'
      ? pending : jsonResponse(serverSettings)))
    const browserUser = userEvent.setup()
    render(<ProfilePage user={userAccount} onBack={vi.fn()} />)

    await browserUser.selectOptions(await screen.findByLabelText('Proveedor'), 'ollama')
    expect(screen.getByLabelText('Modelo para conversar')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeDisabled()
    await browserUser.selectOptions(screen.getByLabelText('Proveedor'), 'openai')
    resolveCatalog(await jsonResponse({ models: ['qwen2.5:7b'] }))
    await vi.waitFor(() => expect(screen.getByLabelText('Modelo para conversar')).toHaveValue('gpt-6-luna'))
    expect(screen.queryByRole('button', { name: 'Actualizar modelos' })).not.toBeInTheDocument()
  })

  it('recarga los modelos cuando cambia el token sin dejar el catálogo cargando', async () => {
    let catalogCalls = 0
    const fetchMock = vi.fn((url) => {
      if (url === '/api/ai/models/ollama') {
        catalogCalls += 1
        return jsonResponse({ models: catalogCalls === 1 ? ['llama3.2:latest'] : installedModels })
      }
      return jsonResponse(ollamaSettings)
    })
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<ProfilePage user={userAccount} onBack={vi.fn()} />)
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled())

    const renewedUser = { ...userAccount, token: 'token-b' }
    setSession(renewedUser)
    view.rerender(<ProfilePage user={renewedUser} onBack={vi.fn()} />)
    expect(await within(screen.getByLabelText('Modelo para conversar')).findByRole('option', { name: 'qwen2.5:7b' }))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Guardar configuración' })).toBeEnabled()
    expect(catalogCalls).toBe(2)
    const latestRequest = fetchMock.mock.calls.filter(([url]) => url === '/api/ai/models/ollama').at(-1)
    expect(latestRequest[1].headers.Authorization).toBe('Bearer token-b')
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
    await browserUser.type(screen.getByLabelText('Tu API key'), 'private-key')
    await browserUser.click(screen.getByRole('button', { name: 'Guardar configuración' }))

    expect(await screen.findByText('Configuración de IA guardada.')).toBeInTheDocument()
    const putCall = fetchMock.mock.calls.find(([, options]) => options.method === 'PUT')
    expect(putCall[1].headers.Authorization).toBe('Bearer renewed-token')
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1)
  })
})
