import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import OpenQuestionsPage from './OpenQuestionsPage'
import { resetGenerationQueues } from '../lib/generationQueue'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

const openSet = {
  id: 'set-a',
  title: 'Congestión',
  difficulty: 'basic',
  questions: [
    {
      id: 'question-a',
      question: '¿Por qué TCP reduce la ventana ante una pérdida?',
      reference_answer: 'Porque interpreta la pérdida como congestión.',
      key_points: ['la pérdida indica congestión'],
      topic_id: 'topic-a',
    },
    {
      id: 'question-b',
      question: '¿Qué diferencia hay entre control de flujo y de congestión?',
      reference_answer: 'El de flujo protege al receptor; el de congestión, a la red.',
      key_points: ['flujo protege al receptor', 'congestión protege a la red'],
      topic_id: 'topic-a',
    },
  ],
}

function renderPage(props = {}) {
  return render(
    <OpenQuestionsPage
      subject={{ id: 5, name: 'Redes' }}
      user={{ token: 'token-a' }}
      onBack={vi.fn()}
      {...props}
    />,
  )
}

function attemptsOf(fetchMock) {
  return fetchMock.mock.calls
    .filter(([url, options]) =>
      url === '/api/open-questions/set-a/attempts' && options?.method === 'POST')
    .map(([, options]) => JSON.parse(options.body))
}

describe('OpenQuestionsPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    // La cola de generación es de módulo (sobrevive al desmontaje), así que hay que
    // vaciarla entre casos.
    resetGenerationQueues()
  })

  it('envía la respuesta escrita y muestra la corrección de Dina', async () => {
    const evaluations = [
      {
        verdict: 'partially_incorrect',
        score: 0.25,
        feedback: 'Confundís causa y efecto.',
        corrections: [{ issue: 'Dijiste que la agranda', correction: 'La reduce' }],
        missing_points: ['la pérdida indica congestión'],
      },
      { verdict: 'perfect', score: 1, feedback: 'Está completa.', corrections: [], missing_points: [] },
      { verdict: 'correct', score: 0.75, feedback: 'Bien.', corrections: [], missing_points: [] },
    ]
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: [] })
      if (url.startsWith('/api/open-questions?')) return jsonResponse([openSet])
      if (url === '/api/open-questions/set-a/attempts' && options.method === 'POST') {
        return jsonResponse(evaluations.shift(), { status: 201 })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderPage()

    await user.click(await screen.findByRole('button', { name: /Congestión/ }))
    expect(screen.getByRole('button', { name: 'Enviar respuesta' })).toBeDisabled()

    await user.type(screen.getByLabelText('Tu respuesta'), 'TCP agranda la ventana')
    await user.click(screen.getByRole('button', { name: 'Enviar respuesta' }))

    expect(await screen.findByText('Parcialmente incorrecta')).toBeInTheDocument()
    expect(screen.getByText('Confundís causa y efecto.')).toBeInTheDocument()
    expect(screen.getByText('Dijiste que la agranda')).toBeInTheDocument()
    expect(screen.getByText(/La reduce/)).toBeInTheDocument()
    expect(screen.getByText('Ver respuesta modelo')).toBeInTheDocument()
    expect(screen.getByLabelText('Tu respuesta')).toBeDisabled()

    // Reintentar la misma pregunta es un intento nuevo, y su corrección reemplaza a la anterior.
    await user.click(screen.getByRole('button', { name: 'Intentar de nuevo' }))
    expect(screen.getByLabelText('Tu respuesta')).toHaveValue('')
    await user.type(screen.getByLabelText('Tu respuesta'), 'Porque la pérdida indica congestión')
    await user.click(screen.getByRole('button', { name: 'Enviar respuesta' }))
    expect(await screen.findByText('Perfecta')).toBeInTheDocument()
    expect(screen.queryByText('Correcciones')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    await user.type(screen.getByLabelText('Tu respuesta'), 'Uno cuida al receptor y el otro a la red')
    await user.click(screen.getByRole('button', { name: 'Enviar respuesta' }))
    await user.click(await screen.findByRole('button', { name: 'Finalizar' }))

    // Cuenta la última corrección de cada pregunta: (1 + 0.75) / 2.
    expect(await screen.findByText('Resultado final')).toBeInTheDocument()
    expect(screen.getByText('88%')).toBeInTheDocument()

    const attempts = attemptsOf(fetchMock)
    expect(attempts).toEqual([
      { questionId: 'question-a', answer: 'TCP agranda la ventana', attemptId: expect.any(String) },
      { questionId: 'question-a', answer: 'Porque la pérdida indica congestión', attemptId: expect.any(String) },
      { questionId: 'question-b', answer: 'Uno cuida al receptor y el otro a la red', attemptId: expect.any(String) },
    ])
    expect(new Set(attempts.map((attempt) => attempt.attemptId)).size).toBe(3)
  })

  it('si el envío falla, reenviar la misma respuesta reusa el mismo intento', async () => {
    const responses = [
      jsonResponse({ error: 'Dina está con mucha demanda', retryable: true }, { ok: false, status: 502 }),
      jsonResponse(
        { verdict: 'correct', score: 0.75, feedback: 'Bien.', corrections: [], missing_points: [] },
        { status: 201 },
      ),
    ]
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: [] })
      if (url.startsWith('/api/open-questions?')) return jsonResponse([openSet])
      if (url === '/api/open-questions/set-a/attempts' && options.method === 'POST') {
        return responses.shift()
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderPage()

    await user.click(await screen.findByRole('button', { name: /Congestión/ }))
    await user.type(screen.getByLabelText('Tu respuesta'), 'Por la congestión')
    await user.click(screen.getByRole('button', { name: 'Enviar respuesta' }))

    expect(await screen.findByText('Dina está con mucha demanda')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar envío' }))
    expect(await screen.findByText('Correcta')).toBeInTheDocument()

    await waitFor(() => {
      const attempts = attemptsOf(fetchMock)
      expect(attempts).toHaveLength(2)
      expect(attempts[1]).toEqual(attempts[0])
    })
  })

  it('generar manda la cantidad de preguntas y deja el set en curso en la lista', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [{ id: 'file-a', filename: 'Apunte de Redes.pdf' }] })
      }
      if (url.startsWith('/api/open-questions?')) return jsonResponse([])
      if (url === '/api/ai/files/open-questions') return new Promise(() => {})
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Nuevas preguntas abiertas' }))
    await user.click(await screen.findByLabelText('Apunte de Redes.pdf'))
    await user.click(screen.getByRole('button', { name: 'Generar preguntas abiertas' }))

    expect(await screen.findByText(/Dina está preparando tus preguntas/)).toBeInTheDocument()
    const generation = fetchMock.mock.calls.find(([url]) => url === '/api/ai/files/open-questions')
    expect(JSON.parse(generation[1].body)).toMatchObject({
      subjectId: '5',
      fileIds: ['file-a'],
      questionCount: 5,
      difficulty: 'basic',
    })
  })
})
