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
      if (url.startsWith('/api/flashcards')) {
        return jsonResponse([])
      }
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

    expect(await screen.findByText('Creá uno a partir de los documentos de esta materia.')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Nuevo set' }))
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
        subjectId: '3',
        title: '',
        fileIds: ['file-a'],
        instructions: 'Priorizá definiciones',
        cardCount: 10,
        cardFormat: 'term_definition',
        difficulty: 'basic',
      }),
    })
  })

  it('permite renombrar un set guardado', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/flashcards?')) {
        return jsonResponse([{
          id: 'set-a',
          title: 'Flashcards #1',
          cards: [{ term: 'Protocolo', definition: 'Reglas de comunicación.' }],
        }])
      }
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [] })
      }
      if (url === '/api/flashcards/set-a?subjectId=3') {
        return jsonResponse({ id: 'set-a', title: 'Unidad 1', cards: [] })
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

    await user.click(await screen.findByRole('button', { name: 'Editar nombre' }))
    const input = screen.getByLabelText('Nombre de Flashcards #1')
    await user.clear(input)
    await user.type(input, 'Unidad 1')
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Unidad 1')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenLastCalledWith('/api/flashcards/set-a?subjectId=3', {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer token-a',
      },
      body: JSON.stringify({ title: 'Unidad 1' }),
    })
  })

  it('permite eliminar un set guardado tras confirmar', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/flashcards?')) {
        return jsonResponse([{
          id: 'set-a',
          title: 'Flashcards #1',
          cards: [{ term: 'Protocolo', definition: 'Reglas de comunicación.' }],
        }])
      }
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [] })
      }
      if (url === '/api/flashcards/set-a?subjectId=3' && options.method === 'DELETE') {
        return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve({}) })
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

    await user.click(await screen.findByRole('button', { name: 'Eliminar' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    expect(fetchMock).toHaveBeenLastCalledWith('/api/flashcards/set-a?subjectId=3', {
      method: 'DELETE',
      headers: { Authorization: 'Bearer token-a' },
    })
    expect(screen.queryByText('Flashcards #1')).not.toBeInTheDocument()
  })

  it('deshabilita crear un set cuando no hay documentos ni sets guardados', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/flashcards?')) return jsonResponse([])
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: [] })
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <FlashcardsPage
        subject={{ id: 3, name: 'Redes' }}
        user={{ token: 'token-a' }}
        onBack={vi.fn()}
      />,
    )

    const newSetButton = await screen.findByRole('button', { name: 'Nuevo set' })
    expect(newSetButton).toBeDisabled()
    expect(screen.getByText('Para crear flashcards, primero tenés que cargar documentos en esta materia.')).toBeInTheDocument()
  })
})
