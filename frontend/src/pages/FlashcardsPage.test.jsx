import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FlashcardsPage from './FlashcardsPage'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

describe('FlashcardsPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('genera flashcards desde los documentos seleccionados y las muestra', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({
          data: [{ id: 'file-a', filename: 'Apunte de Redes.pdf' }],
        })
      }
      if (url === '/api/ai/files/flashcards') {
        return jsonResponse({
          cards: [{ term: 'Protocolo', definition: 'Conjunto de reglas de comunicación.' }],
          chunksUsed: 1,
        })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <FlashcardsPage
        subject={{ id: 3, name: 'Redes' }}
        user={{ token: 'token-a' }}
        onBack={vi.fn()}
      />,
    )

    await user.click(await screen.findByLabelText('Apunte de Redes.pdf'))
    await user.type(
      screen.getByLabelText('Indicaciones adicionales'),
      'Priorizá definiciones',
    )
    await user.click(screen.getByRole('button', { name: 'Generar flashcards' }))

    expect(await screen.findByText('Protocolo')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenLastCalledWith('/api/ai/files/flashcards', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer token-a',
      },
      body: JSON.stringify({
        fileIds: ['file-a'],
        instructions: 'Priorizá definiciones',
      }),
    })
  })
})
