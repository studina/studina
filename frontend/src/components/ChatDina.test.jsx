import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatDina from './ChatDina'
import { clearSession, setSession } from '../lib/session'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) })
}

const files = [
  { id: 'file-a', filename: 'Apunte.pdf' },
  { id: 'file-b', filename: 'Practica.pdf' },
]

function stubChatApi(extra = {}) {
  return vi.fn((url, options = {}) => {
    if (url.startsWith('/api/ai/chats')) return jsonResponse({ data: [] })
    if (extra.onRequest) return extra.onRequest(url, options)
    throw new Error(`Request inesperada: ${url}`)
  })
}

function renderChat(fetchMock, props = {}) {
  vi.stubGlobal('fetch', fetchMock)
  render(
    <ChatDina
      subject={{ id: 3, name: 'Redes' }}
      files={files}
      user={{ token: 'token-a' }}
      {...props}
    />,
  )
}

describe('ChatDina', () => {
  beforeEach(() => {
    setSession({ token: 'token-a' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    clearSession()
  })

  it('permite elegir todos los documentos de una vez', async () => {
    renderChat(stubChatApi())
    const user = userEvent.setup()

    expect(await screen.findByText(/Documentos seleccionados: 0/)).toBeInTheDocument()

    await user.click(screen.getByText(/Seleccionar todos/))

    expect(await screen.findByText(/Documentos seleccionados: 2/)).toBeInTheDocument()
  })

  it('el mismo control deselecciona cuando ya está todo elegido', async () => {
    renderChat(stubChatApi())
    const user = userEvent.setup()

    await user.click(await screen.findByText(/Seleccionar todos/))
    await user.click(screen.getByText(/Deseleccionar todos/))

    expect(await screen.findByText(/Documentos seleccionados: 0/)).toBeInTheDocument()
  })

  it('manda a la IA los documentos elegidos como contexto', async () => {
    // Lo que importa del selector: que el backend reciba esos fileIds para responder
    // sobre esos documentos y no sobre toda la materia.
    const fetchMock = stubChatApi({
      onRequest: (url) => {
        if (url === '/api/ai/files/chat') {
          return jsonResponse({ answer: 'Depende del protocolo.', conversationId: 'chat-1' })
        }
        throw new Error(`Request inesperada: ${url}`)
      },
    })
    renderChat(fetchMock)
    const user = userEvent.setup()

    await user.click(await screen.findByText(/Seleccionar todos/))
    await user.type(screen.getByRole('textbox'), '¿Qué es un protocolo?')
    await user.keyboard('{Enter}')

    const chatCall = await vi.waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) => url === '/api/ai/files/chat')
      expect(call).toBeDefined()
      return call
    })
    expect(JSON.parse(chatCall[1].body).fileIds).toEqual(['file-a', 'file-b'])
  })

  it('no ofrece seleccionar todos si la materia no tiene documentos', async () => {
    renderChat(stubChatApi(), { files: [] })

    expect(await screen.findByText(/No hay documentos cargados/)).toBeInTheDocument()
    expect(screen.queryByText(/Seleccionar todos/)).toBeNull()
  })

  it('abre con la pregunta escrita y los documentos del tema tildados', async () => {
    // Es el caso de entrar desde un tema de la pantalla de progreso.
    renderChat(stubChatApi(), {
      initialPrompt: 'Quiero aprender más sobre "Consistencia".',
      initialFileIds: ['file-b'],
    })

    expect(await screen.findByRole('textbox'))
      .toHaveValue('Quiero aprender más sobre "Consistencia".')
    expect(screen.getByText(/Documentos seleccionados: 1/)).toBeInTheDocument()
  })

  it('la precarga sobrevive al efecto que limpia el chat al montar', async () => {
    // El efecto de arranque resetea la conversación y la selección. Si no sembrara la
    // precarga, los documentos del tema se destildarían solos apenas monta.
    renderChat(stubChatApi(), { initialFileIds: ['file-a', 'file-b'] })

    expect(await screen.findByText(/Documentos seleccionados: 2/)).toBeInTheDocument()
    // Y sigue tildado después de que la carga de conversaciones termine.
    await vi.waitFor(() =>
      expect(screen.getByText(/Documentos seleccionados: 2/)).toBeInTheDocument(),
    )
  })

  it('no manda la pregunta precargada sola: queda lista para revisar', async () => {
    const fetchMock = stubChatApi()
    renderChat(fetchMock, { initialPrompt: '¿Qué es un protocolo?', initialFileIds: ['file-a'] })

    await screen.findByRole('textbox')
    // Ojo con el historial: /api/ai/chats contiene "/chat" como subcadena, así que la
    // comprobación va contra los endpoints de envío y no por coincidencia parcial.
    const sendEndpoints = ['/api/ai/chat', '/api/ai/files/chat']
    await vi.waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => sendEndpoints.includes(url))).toBe(false),
    )
  })

  it('carga los documentos de la materia cuando no los recibe', async () => {
    // Pasa al entrar directo al chat sin pasar por Documentos: antes el desplegable decía
    // "No hay documentos cargados" aunque la materia tuviera archivos.
    const fetchMock = stubChatApi({
      onRequest: (url) => {
        if (url.startsWith('/api/files/list')) {
          return jsonResponse({ data: [{ id: 'file-x', filename: 'Apunte suelto.pdf' }] })
        }
        throw new Error(`Request inesperada: ${url}`)
      },
    })
    renderChat(fetchMock, { files: [] })

    expect(await screen.findByText('Apunte suelto.pdf')).toBeInTheDocument()
    expect(screen.queryByText(/No hay documentos cargados/)).toBeNull()
  })

  it('no pide documentos si ya los recibió por prop', async () => {
    const fetchMock = stubChatApi()
    renderChat(fetchMock)

    await screen.findByText('Apunte.pdf')
    expect(fetchMock.mock.calls.some(([url]) => url.startsWith('/api/files/list'))).toBe(false)
  })
})
