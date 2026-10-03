import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatDina from './ChatDina'

const userAccount = {
  userId: 'user-a',
  token: 'token-a',
}

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

describe('ChatDina AI provider indicator', () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('muestra la configuración que se usará antes de enviar el mensaje', async () => {
    vi.stubGlobal('fetch', vi.fn((url) => {
      if (url === '/api/ai/settings') {
        return jsonResponse({
          source: 'user',
          provider: 'openai',
          chatModel: 'gpt-4.1-mini',
        })
      }
      if (String(url).startsWith('/api/ai/chats?')) return jsonResponse([])
      throw new Error(`Request inesperada: ${url}`)
    }))

    render(
      <ChatDina
        subject={{ id: 'subject-a', name: 'Derecho' }}
        files={[]}
        user={userAccount}
      />,
    )

    expect(await screen.findByLabelText(
      'Estás usando OpenAI, modelo gpt-4.1-mini',
    )).toBeInTheDocument()
    expect(screen.getByText('Personal')).toBeInTheDocument()
  })

  it('etiqueta la respuesta con el proveedor y modelo realmente usados', async () => {
    vi.stubGlobal('fetch', vi.fn((url, options = {}) => {
      if (url === '/api/ai/settings') {
        return jsonResponse({
          source: 'user',
          provider: 'openai',
          chatModel: 'gpt-4.1-mini',
        })
      }
      if (String(url).startsWith('/api/ai/chats?')) return jsonResponse([])
      if (url === '/api/ai/chat' && options.method === 'POST') {
        return jsonResponse({
          answer: 'Respuesta de prueba',
          conversationId: 'conversation-a',
          newConversation: false,
          provider: 'openai',
          model: 'gpt-4.1-mini',
        })
      }
      throw new Error(`Request inesperada: ${url}`)
    }))
    const browserUser = userEvent.setup()

    render(
      <ChatDina
        subject={{ id: 'subject-a', name: 'Derecho' }}
        files={[]}
        user={userAccount}
      />,
    )

    const input = screen.getByPlaceholderText('Preguntale algo a Dina...')
    await browserUser.type(input, 'Hola')
    await browserUser.click(screen.getByRole('button', { name: '➤' }))

    expect(await screen.findByText('Respuesta de prueba')).toBeInTheDocument()
    expect(screen.getByText('OpenAI · gpt-4.1-mini')).toBeInTheDocument()
  })
})
