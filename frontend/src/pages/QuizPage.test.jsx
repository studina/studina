import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import QuizPage from './QuizPage'
import { resetGenerationQueues } from '../lib/generationQueue'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

describe('QuizPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    // La cola de generación es de módulo (sobrevive al desmontaje), así que hay que
    // vaciarla entre casos.
    resetGenerationQueues()
  })

  it('persiste cada respuesta con su identificador de intento', async () => {
    const quiz = {
      id: 'quiz-a',
      title: 'Unidad 1',
      questions: [{
        id: 'question-a',
        question: '¿Qué describe la replicación?',
        options: [
          { id: 'option-a', text: 'Copias de datos entre nodos' },
          { id: 'option-b', text: 'Un formato de archivo' },
        ],
        correctOptionId: 'option-a',
        explanation: 'La replicación mantiene copias en varios nodos.',
        topic_id: 'topic-a',
      }],
    }
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: [] })
      if (url.startsWith('/api/quizzes?')) return jsonResponse([quiz])
      if (url === '/api/quizzes/quiz-a/attempts' && options.method === 'POST') {
        return jsonResponse({ id: 'attempt-a' }, { status: 201 })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <QuizPage
        subject={{ id: 5, name: 'Redes' }}
        user={{ token: 'token-a' }}
        onBack={vi.fn()}
      />,
    )

    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))
    await user.click(screen.getByRole('button', { name: 'Copias de datos entre nodos' }))
    expect(screen.getByText('¡Correcto!')).toBeInTheDocument()
    expect(screen.queryByText('Guardando respuesta...')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Finalizar' }))

    expect(await screen.findByText('Resultado final')).toBeInTheDocument()
    await waitFor(() => {
      const answerRequest = fetchMock.mock.calls.find(([url, options]) =>
        url === '/api/quizzes/quiz-a/attempts' && options?.method === 'POST',
      )
      expect(answerRequest).toBeDefined()
      expect(JSON.parse(answerRequest[1].body)).toEqual({
        questionId: 'question-a',
        selectedOptionId: 'option-a',
        attemptId: expect.any(String),
      })
    })
    expect(fetchMock.mock.calls.some(([url]) => url.includes('/study-sessions'))).toBe(false)
  })

  it('generar devuelve a la lista con el cuestionario en curso y lo deja listo al terminar', async () => {
    // La respuesta se deja pendiente para poder observar el estado intermedio:
    // sin esto sólo se vería el resultado final.
    let resolveGeneration
    const pendingGeneration = new Promise((resolve) => {
      resolveGeneration = resolve
    })
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [{ id: 'file-a', filename: 'Apunte de Redes.pdf' }] })
      }
      if (url.startsWith('/api/quizzes?')) return jsonResponse([])
      if (url === '/api/ai/files/quizzes') return pendingGeneration
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <QuizPage
        subject={{ id: 5, name: 'Redes' }}
        user={{ token: 'token-a' }}
        onBack={vi.fn()}
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Nuevo cuestionario' }))
    await user.click(await screen.findByLabelText('Apunte de Redes.pdf'))
    await user.type(screen.getByLabelText('Nombre del set'), 'Unidad 3')

    await user.click(screen.getByRole('button', { name: 'Generar cuestionario' }))

    // Ya no deja al usuario esperando en el formulario: vuelve a la lista con el
    // cuestionario ocupando su lugar, todavía sin poder abrirse.
    expect(await screen.findByText(/Dina está preparando tu cuestionario/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Generar cuestionario' })).not.toBeInTheDocument()
    expect(screen.queryByText('Todavía no hay cuestionarios')).not.toBeInTheDocument()

    resolveGeneration({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        id: 'quiz-generated',
        title: 'Unidad 3',
        difficulty: 'basic',
        questions: [{
          id: 'question-a',
          question: '¿Qué describe la replicación?',
          options: [{ id: 'option-a', text: 'Copias de datos entre nodos' }],
          correctOptionId: 'option-a',
          explanation: 'Mantiene copias en varios nodos.',
          topic_id: 'topic-a',
        }],
      }),
    })

    // Y al terminar queda como un cuestionario más, abrible, sin abrirse solo.
    expect(await screen.findByRole('button', { name: /Unidad 3/ })).toBeInTheDocument()
    expect(screen.queryByText(/Dina está preparando tu cuestionario/)).not.toBeInTheDocument()
    expect(screen.queryByText('¿Qué describe la replicación?')).not.toBeInTheDocument()
  })

  it('durante el cuestionario se puede volver al listado sin salir de la sección', async () => {
    const quiz = {
      id: 'quiz-a',
      title: 'Unidad 1',
      questions: [{
        id: 'question-a',
        question: '¿Qué describe la replicación?',
        options: [{ id: 'option-a', text: 'Copias de datos entre nodos' }],
        correctOptionId: 'option-a',
        explanation: 'Mantiene copias en varios nodos.',
        topic_id: 'topic-a',
      }],
    }
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: [] })
      if (url.startsWith('/api/quizzes?')) return jsonResponse([quiz])
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const onBack = vi.fn()
    const user = userEvent.setup()

    render(
      <QuizPage
        subject={{ id: 5, name: 'Redes' }}
        user={{ token: 'token-a' }}
        onBack={onBack}
      />,
    )

    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))
    expect(screen.getByText('¿Qué describe la replicación?')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '← Ver mis cuestionarios' }))

    expect(await screen.findByRole('button', { name: 'Nuevo cuestionario' })).toBeInTheDocument()
    expect(screen.queryByText('¿Qué describe la replicación?')).not.toBeInTheDocument()
    expect(onBack).not.toHaveBeenCalled()

    // "Volver" sigue siendo la salida de la sección.
    await user.click(await screen.findByRole('button', { name: /Unidad 1/ }))
    await user.click(screen.getByRole('button', { name: '← Volver' }))
    expect(onBack).toHaveBeenCalled()
  })

  it('permite elegir todos los documentos de una vez', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({
          data: [
            { id: 'file-a', filename: 'Apunte.pdf' },
            { id: 'file-b', filename: 'Practica.pdf' },
          ],
        })
      }
      if (url.startsWith('/api/quizzes')) return jsonResponse([])
      if (url === '/api/ai/files/quizzes') {
        return jsonResponse({ id: 'quiz-1', title: 'Todo', questions: [{ id: 'q-1' }] })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <QuizPage
        subject={{ id: 3, name: 'Redes' }}
        user={{ token: 'token-a' }}
        onBack={vi.fn()}
      />,
    )

    await user.click(await screen.findByRole('button', { name: 'Nuevo cuestionario' }))
    await user.click(await screen.findByText(/Seleccionar todos/))
    await user.click(screen.getByRole('button', { name: 'Generar cuestionario' }))

    const generation = fetchMock.mock.calls.find(([url]) => url === '/api/ai/files/quizzes')
    expect(JSON.parse(generation[1].body).fileIds).toEqual(['file-a', 'file-b'])
  })
})
