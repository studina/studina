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
      if (url.startsWith('/api/flashcards/progress?')) return jsonResponse([])
      if (url.startsWith('/api/flashcards/study-sessions?')) {
        return jsonResponse({ id: 'session-generated' }, { status: 201 })
      }
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
          id: 'set-generated',
          cards: [{ id: 'card-generated', term: 'Protocolo', definition: 'Conjunto de reglas de comunicación.' }],
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
    const generationRequest = fetchMock.mock.calls.find(([url]) => url === '/api/ai/files/flashcards')
    expect(generationRequest).toBeDefined()
    expect(generationRequest[0]).toBe('/api/ai/files/flashcards')
    expect(generationRequest[1]).toEqual({
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

  it('registra solo el rating y el ID de la tarjeta, sin medir tiempo de respuesta', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/flashcards?')) {
        return jsonResponse([{
          id: 'set-a',
          title: 'Unidad 1',
          cards: [{
            id: 'card-a',
            term: 'Consistencia eventual',
            definition: 'Las réplicas convergen eventualmente.',
            topic_ids: ['topic-a'],
          }],
        }])
      }
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [] })
      }
      if (url.startsWith('/api/flashcards/progress?')) return jsonResponse([])
      if (url.startsWith('/api/flashcards/cards/card-a/attempts?')) return jsonResponse([])
      if (url.startsWith('/api/flashcards/study-sessions?')) {
        return jsonResponse({ id: 'session-a' }, { status: 201 })
      }
      if (url === '/api/flashcards/set-a/attempts?subjectId=3' && options.method === 'POST') {
        return jsonResponse({ ok: true })
      }
      if (url === '/api/flashcards/study-sessions/session-a/complete?subjectId=3') {
        return jsonResponse({ status: 'completed' })
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

    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'Lo sabía con seguridad' }))

    const attemptRequest = fetchMock.mock.calls.find(([url, options]) =>
      url.includes('/attempts') && options?.method === 'POST',
    )
    expect(attemptRequest).toBeDefined()
    expect(JSON.parse(attemptRequest[1].body)).toEqual({
      cardId: 'card-a',
      rating: 4,
      sessionId: 'session-a',
      idempotencyKey: expect.any(String),
    })
  })

  it('no permite avanzar sin calificar y avanza después de guardar el rating', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/flashcards?')) {
        return jsonResponse([{
          id: 'set-a',
          title: 'Unidad 1',
          cards: [
            { id: 'card-a', term: 'Tarjeta A', definition: 'Respuesta A' },
            { id: 'card-b', term: 'Tarjeta B', definition: 'Respuesta B' },
          ],
        }])
      }
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: [] })
      if (url.startsWith('/api/flashcards/progress?')) return jsonResponse([])
      if (url.startsWith('/api/flashcards/cards/')) return jsonResponse([])
      if (url.startsWith('/api/flashcards/study-sessions?')) {
        return jsonResponse({ id: 'session-a' }, { status: 201 })
      }
      if (url.includes('/attempts?') && options.method === 'POST') return jsonResponse({ ok: true })
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

    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))
    expect(await screen.findByText('Tarjeta A')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Siguiente →' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'No lo sabía' }))

    expect(await screen.findByText('Tarjeta B')).toBeInTheDocument()
  })

  it('permite renombrar un set guardado', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/flashcards/progress?')) return jsonResponse([])
      if (url.startsWith('/api/flashcards?')) {
        return jsonResponse([{
          id: 'set-a',
          title: 'Flashcards #1',
          cards: [{ term: 'Protocolo', definition: 'Reglas de comunicación.' }],
          difficulty: 'intermediate',
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
      if (url.startsWith('/api/flashcards/progress?')) return jsonResponse([])
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
      if (url.startsWith('/api/flashcards/progress?')) return jsonResponse([])
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
