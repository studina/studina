import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TrueFalsePage from './TrueFalsePage'
import { resetGenerationQueues } from '../lib/generationQueue'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

const trueFalseSet = {
  id: 'set-a',
  title: 'Transporte',
  difficulty: 'basic',
  statements: [
    {
      id: 'statement-a',
      statement: 'UDP garantiza el orden de entrega.',
      isTrue: false,
      explanation: 'UDP no garantiza orden; TCP sí.',
      topic_id: 'topic-a',
    },
    {
      id: 'statement-b',
      statement: 'TCP es orientado a conexión.',
      isTrue: true,
      explanation: 'Establece una conexión antes de enviar datos.',
      topic_id: 'topic-a',
    },
  ],
}

function renderPage(props = {}) {
  return render(
    <TrueFalsePage
      subject={{ id: 5, name: 'Redes' }}
      user={{ token: 'token-a' }}
      onBack={vi.fn()}
      {...props}
    />,
  )
}

describe('TrueFalsePage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    // La cola de generación es de módulo (sobrevive al desmontaje), así que hay que
    // vaciarla entre casos.
    resetGenerationQueues()
  })

  it('persiste cada respuesta con su identificador de intento y cuenta los aciertos', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: [] })
      if (url.startsWith('/api/true-false?')) return jsonResponse([trueFalseSet])
      if (url === '/api/true-false/set-a/attempts' && options.method === 'POST') {
        return jsonResponse({ id: 'attempt-a' }, { status: 201 })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderPage()

    await user.click(await screen.findByRole('button', { name: /Transporte/ }))
    expect(screen.getByText('UDP garantiza el orden de entrega.')).toBeInTheDocument()

    // El primero es falso: elegir "Falso" es acertar.
    await user.click(screen.getByRole('button', { name: 'Falso' }))
    expect(screen.getByText('¡Correcto!')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Verdadero' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))

    // El segundo es verdadero: elegir "Falso" es errar, y se dice cuál era.
    await user.click(screen.getByRole('button', { name: 'Falso' }))
    expect(screen.getByText('Incorrecto: el enunciado es verdadero')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Finalizar' }))

    expect(await screen.findByText('Resultado final')).toBeInTheDocument()
    expect(screen.getByText('1 / 2')).toBeInTheDocument()

    await waitFor(() => {
      const answers = fetchMock.mock.calls
        .filter(([url, options]) =>
          url === '/api/true-false/set-a/attempts' && options?.method === 'POST')
        .map(([, options]) => JSON.parse(options.body))
      expect(answers).toEqual([
        { statementId: 'statement-a', answer: false, attemptId: expect.any(String) },
        { statementId: 'statement-b', answer: false, attemptId: expect.any(String) },
      ])
      expect(answers[0].attemptId).not.toBe(answers[1].attemptId)
    })
  })

  it('generar devuelve a la lista con el set en curso y lo deja listo al terminar', async () => {
    let resolveGeneration
    const pendingGeneration = new Promise((resolve) => {
      resolveGeneration = resolve
    })
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [{ id: 'file-a', filename: 'Apunte de Redes.pdf' }] })
      }
      if (url.startsWith('/api/true-false?')) return jsonResponse([])
      if (url === '/api/ai/files/true-false') return pendingGeneration
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Nuevo verdadero o falso' }))
    await user.click(await screen.findByLabelText('Apunte de Redes.pdf'))
    await user.type(screen.getByLabelText('Nombre del set'), 'Transporte')
    await user.click(screen.getByRole('button', { name: 'Generar verdadero o falso' }))

    expect(await screen.findByText(/Dina está preparando tu verdadero o falso/)).toBeInTheDocument()
    expect(screen.queryByText('Todavía no hay sets de verdadero o falso')).not.toBeInTheDocument()

    const generation = fetchMock.mock.calls.find(([url]) => url === '/api/ai/files/true-false')
    expect(JSON.parse(generation[1].body)).toMatchObject({
      subjectId: '5',
      title: 'Transporte',
      fileIds: ['file-a'],
      statementCount: 10,
      difficulty: 'basic',
    })

    resolveGeneration({
      ok: true,
      status: 200,
      json: () => Promise.resolve(trueFalseSet),
    })

    expect(await screen.findByRole('button', { name: /Transporte/ })).toBeInTheDocument()
    expect(screen.queryByText(/Dina está preparando tu verdadero o falso/)).not.toBeInTheDocument()
    expect(screen.queryByText('UDP garantiza el orden de entrega.')).not.toBeInTheDocument()
  })

  it('un pedido desde un tema abre el generador con sus documentos y el tema indicado', async () => {
    vi.stubGlobal('fetch', vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [{ id: 'file-a', filename: 'Apunte de Redes.pdf' }] })
      }
      if (url.startsWith('/api/true-false?')) return jsonResponse([])
      throw new Error(`Request inesperada: ${url}`)
    }))

    renderPage({ initialGeneration: { fileIds: ['file-a'], topicName: 'Transporte' } })

    expect(await screen.findByLabelText('Apunte de Redes.pdf')).toBeChecked()
    expect(screen.getByLabelText('Indicaciones adicionales'))
      .toHaveValue('Enfocate únicamente en el tema: Transporte.')
  })
})
