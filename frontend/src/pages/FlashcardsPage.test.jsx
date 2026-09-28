import { render, screen, waitFor } from '@testing-library/react'
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
          id: 'set-generated',
          cards: [{ id: 'card-generated', term: 'Protocolo', definition: 'Conjunto de reglas de comunicación.', topic_id: 'topic-a' }],
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
            topic_id: 'topic-a',
          }],
        }])
      }
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [] })
      }
      if (url === '/api/flashcards/set-a/attempts' && options.method === 'POST') return jsonResponse({ ok: true })
      // Calificar la única tarjeta termina el set, y el cierre lee el progreso.
      if (url.startsWith('/api/flashcards/progress?')) {
        return jsonResponse([{
          topic_id: 'topic-a',
          topic_name: 'Consistencia',
          mastery_score: 0.7,
          coverage: 1,
        }])
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

    await waitFor(() => {
      const attemptRequest = fetchMock.mock.calls.find(([url, options]) =>
        url === '/api/flashcards/set-a/attempts' && options?.method === 'POST',
      )
      expect(attemptRequest).toBeDefined()
      expect(JSON.parse(attemptRequest[1].body)).toEqual({
        cardId: 'card-a',
        rating: 4,
        attemptId: expect.any(String),
      })
    })
    expect(fetchMock.mock.calls.some(([url]) => url.includes('/study-sessions'))).toBe(false)
    // El progreso se lee una sola vez, al cerrar el set: calificar no dispara lecturas.
    await screen.findByText(/Set de flashcards terminado/)
    expect(
      fetchMock.mock.calls.filter(([url]) => url.startsWith('/api/flashcards/progress?')),
    ).toHaveLength(1)
    expect(fetchMock.mock.calls.some(([url]) => url.includes('/cards/card-a/attempts?'))).toBe(false)
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
      if (url.includes('/attempts') && options.method === 'POST') return jsonResponse({ ok: true })
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
      if (url === '/api/flashcards/set-a') {
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
    expect(fetchMock).toHaveBeenLastCalledWith('/api/flashcards/set-a', {
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
      if (url === '/api/flashcards/set-a' && options.method === 'DELETE') {
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

    expect(fetchMock).toHaveBeenLastCalledWith('/api/flashcards/set-a', {
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
  // ── Pantalla de cierre del set ──

  const twoCardSet = {
    id: 'set-a',
    title: 'Unidad 1',
    source_file_ids: ['file-a', 'file-borrado'],
    cards: [
      { id: 'card-a', term: 'Tarjeta A', definition: 'Respuesta A', topic_id: 'topic-a' },
      { id: 'card-b', term: 'Tarjeta B', definition: 'Respuesta B', topic_id: 'topic-b' },
    ],
  }

  // topic-c pertenece a la materia pero no a este set: no debe entrar al promedio.
  const subjectProgress = [
    { topic_id: 'topic-a', topic_name: 'Consistencia', mastery_score: 0.8, coverage: 1 },
    { topic_id: 'topic-b', topic_name: 'Elección de líder', mastery_score: 0.2, coverage: 0.5 },
    { topic_id: 'topic-c', topic_name: 'Relojes lógicos', mastery_score: 0, coverage: 0 },
  ]

  function studyMock(overrides = {}) {
    return vi.fn((url, options = {}) => {
      if (url.startsWith('/api/flashcards?')) return jsonResponse([twoCardSet])
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [{ id: 'file-a', filename: 'apunte.pdf' }] })
      }
      if (url.includes('/attempts') && options.method === 'POST') return jsonResponse({ ok: true })
      if (url.startsWith('/api/flashcards/progress?')) return jsonResponse(subjectProgress)
      if (overrides[url]) return overrides[url]()
      if (url.includes('/review-round')) return jsonResponse({ cards: twoCardSet.cards })
      throw new Error(`Request inesperada: ${url}`)
    })
  }

  async function completeBothCards(user) {
    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))
    for (const term of ['Tarjeta A', 'Tarjeta B']) {
      await screen.findByText(term)
      await user.click(screen.getByRole('button', { name: /Ver definición/ }))
      await user.click(await screen.findByRole('button', { name: 'Lo sabía con seguridad' }))
    }
  }

  it('al terminar el set muestra el promedio de mastery y el tema más flojo, solo de sus temas', async () => {
    vi.stubGlobal('fetch', studyMock())
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await completeBothCards(user)

    await screen.findByText(/Set de flashcards terminado/)
    // (0.8 + 0.2) / 2 = 0.5 → 50%. Si entrara topic-c daría 33%.
    expect(await screen.findByText('50%')).toBeInTheDocument()
    expect(screen.getByText(/El tema que más necesitás repasar es/)).toHaveTextContent('Elección de líder')
    expect(screen.queryByText('Relojes lógicos')).not.toBeInTheDocument()
  })

  it('ofrece los documentos del set y deshabilita los que ya no existen', async () => {
    vi.stubGlobal('fetch', studyMock())
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await completeBothCards(user)

    expect(await screen.findByRole('button', { name: 'apunte.pdf' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Documento no disponible' })).toBeDisabled()
  })

  it('"Otra ronda" vuelve a pedir la ronda y la estudia de nuevo', async () => {
    const fetchMock = studyMock({
      '/api/flashcards/set-a/review-round': () => jsonResponse({
        cards: [twoCardSet.cards[1]],
      }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await completeBothCards(user)

    await user.click(await screen.findByRole('button', { name: 'Otra ronda' }))

    expect(await screen.findByText('Tarjeta B')).toBeInTheDocument()
    expect(screen.getByText('1 / 1')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'Lo sabía con seguridad' }))
    expect(await screen.findByText(/Ronda 2/)).toBeInTheDocument()
  })

  it('pide la ronda una sola vez y no vuelve a pedir nada entre tarjeta y tarjeta', async () => {
    const fetchMock = studyMock({
      '/api/flashcards/set-a/review-round': () => jsonResponse({
        cards: [twoCardSet.cards[1], twoCardSet.cards[0]],
      }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await user.click(await screen.findByRole('button', { name: 'Repasar' }))

    await screen.findByText('Tarjeta B')
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'Lo sabía con seguridad' }))
    await screen.findByText('Tarjeta A')

    // Todo el repaso sale de una sola respuesta: antes era un request por tarjeta.
    expect(
      fetchMock.mock.calls.filter(([url]) => url.includes('/review-round')),
    ).toHaveLength(1)
  })

  it('permite calificar cada aparición de una tarjeta repetida en la ronda', async () => {
    const repeated = twoCardSet.cards[1]
    const fetchMock = studyMock({
      '/api/flashcards/set-a/review-round': () => jsonResponse({
        cards: [repeated, twoCardSet.cards[0], repeated],
      }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await user.click(await screen.findByRole('button', { name: 'Repasar' }))

    for (const position of ['1 / 3', '2 / 3', '3 / 3']) {
      await screen.findByText(position)
      await user.click(screen.getByRole('button', { name: /Ver definición/ }))
      // En la tercera vuelve card-b: si el estado se indexara por card.id,
      // acá ya estaría deshabilitado y "Siguiente" habilitado sin calificar.
      const ratingButton = await screen.findByRole('button', { name: 'Lo sabía con seguridad' })
      expect(ratingButton).toBeEnabled()
      await user.click(ratingButton)
    }

    await screen.findByText(/Set de flashcards terminado/)
    // Las tres apariciones registran su propio intento, incluidas las dos de card-b.
    const attempts = fetchMock.mock.calls
      .filter(([url, options]) => url.includes('/attempts') && options?.method === 'POST')
      .map(([, options]) => JSON.parse(options.body).cardId)
    expect(attempts).toEqual(['card-b', 'card-a', 'card-b'])
  })

  it('vuelve a la biblioteca desde la pantalla de cierre', async () => {
    vi.stubGlobal('fetch', studyMock())
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await completeBothCards(user)

    await user.click(await screen.findByRole('button', { name: 'Volver a mis sets' }))

    expect(await screen.findByRole('heading', { name: 'Mis sets' })).toBeInTheDocument()
  })
  it('después de generar un set y terminarlo, vuelve a la biblioteca y no al generador', async () => {
    const generatedSet = {
      id: 'set-generated',
      title: 'Flashcards #1',
      source_file_ids: [],
      cards: [{ id: 'card-generated', term: 'Protocolo', definition: 'Reglas.', topic_id: 'topic-a' }],
    }
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/flashcards?')) return jsonResponse([])
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [{ id: 'file-a', filename: 'Apunte.pdf' }] })
      }
      if (url === '/api/ai/files/flashcards') return jsonResponse(generatedSet)
      if (url.includes('/attempts') && options.method === 'POST') return jsonResponse({ ok: true })
      if (url.startsWith('/api/flashcards/progress?')) return jsonResponse(subjectProgress)
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Nuevo set' }))
    await user.click(await screen.findByLabelText('Apunte.pdf'))
    await user.click(screen.getByRole('button', { name: 'Generar flashcards' }))

    await screen.findByText('Protocolo')
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'Lo sabía con seguridad' }))

    await screen.findByText(/Set de flashcards terminado/)
    await user.click(screen.getByRole('button', { name: 'Volver a mis sets' }))

    // El generador quedaba abierto detrás y tapaba la biblioteca.
    expect(await screen.findByRole('heading', { name: 'Mis sets' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Generar flashcards' })).not.toBeInTheDocument()
  })

  it('el repaso no monta la vista de estudio hasta tener la ronda', async () => {
    // La selección se deja pendiente a propósito: el salto sólo es observable
    // mientras el fetch no resolvió. Antes del fix, acá ya se veía la tarjeta A.
    let resolveSelection
    const pendingSelection = new Promise((resolve) => {
      resolveSelection = resolve
    })
    const fetchMock = studyMock({
      '/api/flashcards/set-a/review-round': () => pendingSelection,
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await user.click(await screen.findByRole('button', { name: 'Repasar' }))

    expect(await screen.findByRole('button', { name: 'Preparando...' })).toBeInTheDocument()
    expect(screen.queryByText('Tarjeta A')).not.toBeInTheDocument()
    expect(screen.queryByText('Tarjeta B')).not.toBeInTheDocument()

    resolveSelection({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ cards: [twoCardSet.cards[1]] }),
    })

    expect(await screen.findByText('Tarjeta B')).toBeInTheDocument()
    expect(screen.queryByText('Tarjeta A')).not.toBeInTheDocument()
  })

  it('no muestra la cobertura en el listado de temas', async () => {
    vi.stubGlobal('fetch', studyMock())
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await completeBothCards(user)

    await screen.findByText(/Set de flashcards terminado/)
    expect(screen.getByText('Consistencia')).toBeInTheDocument()
    expect(screen.queryByText(/Cobertura/)).not.toBeInTheDocument()
  })
  // ── Corregir una calificación ──

  it('volver atrás y cambiar la nota manda un PATCH, no un intento nuevo', async () => {
    const fetchMock = studyMock({
      '/api/flashcards/set-a/review-round': () => jsonResponse({ cards: twoCardSet.cards }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))

    await screen.findByText('Tarjeta A')
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'No lo sabía' }))

    // Avanzó sola a la segunda; volvemos a corregir la primera.
    await screen.findByText('Tarjeta B')
    await user.click(screen.getByRole('button', { name: /Anterior/ }))
    await screen.findByText('Tarjeta A')
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'Lo sabía con seguridad' }))

    const posts = fetchMock.mock.calls.filter(
      ([url, options]) => url.includes('/attempts') && options?.method === 'POST',
    )
    const patches = fetchMock.mock.calls.filter(([, options]) => options?.method === 'PATCH')

    expect(posts).toHaveLength(1)
    expect(patches).toHaveLength(1)

    const attemptId = JSON.parse(posts[0][1].body).attemptId
    expect(patches[0][0]).toBe(`/api/flashcards/set-a/attempts/${attemptId}`)
    expect(JSON.parse(patches[0][1].body)).toEqual({ cardId: 'card-a', rating: 4 })

    // La marca se mueve a la nota nueva: sin esto la corrección no da ninguna
    // señal de haber ocurrido.
    expect(screen.getByRole('button', { name: 'Lo sabía con seguridad' })).toHaveClass('selected')
    expect(screen.getByRole('button', { name: 'No lo sabía' })).not.toHaveClass('selected')
  })

  it('marca la nota elegida al volver a una tarjeta ya calificada', async () => {
    vi.stubGlobal('fetch', studyMock())
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))

    await screen.findByText('Tarjeta A')
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'Lo sabía, pero dudé' }))

    await screen.findByText('Tarjeta B')
    await user.click(screen.getByRole('button', { name: /Anterior/ }))
    await screen.findByText('Tarjeta A')
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))

    const chosen = await screen.findByRole('button', { name: 'Lo sabía, pero dudé' })
    expect(chosen).toHaveAttribute('aria-pressed', 'true')
    // El nombre de la clase es el contrato con el CSS: si cambia acá y no allá,
    // la marca desaparece sin que nada falle.
    expect(chosen).toHaveClass('selected')
    // Y sigue habilitada, para poder cambiarla.
    expect(chosen).toBeEnabled()

    const other = screen.getByRole('button', { name: 'No lo sabía' })
    expect(other).toHaveAttribute('aria-pressed', 'false')
    expect(other).not.toHaveClass('selected')
  })

  it('si el intento nunca se guardó, la corrección lo crea con la nota buena', async () => {
    const fetchMock = studyMock({
      '/api/flashcards/set-a/review-round': () => jsonResponse({ cards: twoCardSet.cards }),
    })
    // El PATCH no encuentra qué editar: el POST original se perdió.
    const withMissingAttempt = vi.fn((url, options = {}) => {
      if (options?.method === 'PATCH') {
        return jsonResponse({ error: 'No existe un intento con ese identificador' }, { ok: false, status: 404 })
      }
      return fetchMock(url, options)
    })
    vi.stubGlobal('fetch', withMissingAttempt)
    const user = userEvent.setup()

    render(<FlashcardsPage subject={{ id: 3, name: 'Redes' }} user={{ token: 'token-a' }} onBack={vi.fn()} />)
    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))

    await screen.findByText('Tarjeta A')
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'No lo sabía' }))

    await screen.findByText('Tarjeta B')
    await user.click(screen.getByRole('button', { name: /Anterior/ }))
    await screen.findByText('Tarjeta A')
    await user.click(screen.getByRole('button', { name: /Ver definición/ }))
    await user.click(await screen.findByRole('button', { name: 'Lo sabía con seguridad' }))

    await waitFor(() => {
      const posts = withMissingAttempt.mock.calls.filter(
        ([url, options]) => url.includes('/attempts') && options?.method === 'POST',
      )
      expect(posts).toHaveLength(2)
      expect(JSON.parse(posts[1][1].body).rating).toBe(4)
    })
  })
})
