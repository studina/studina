import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import QuizPage from './QuizPage'

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
  })

  it('persists each quiz answer with topic session and idempotency evidence', async () => {
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
        topic_ids: ['topic-a'],
      }],
    }
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: [] })
      if (url.startsWith('/api/quizzes?')) return jsonResponse([quiz])
      if (url === '/api/quizzes/quiz-a/study-sessions?subjectId=5' && options.method === 'POST') {
        return jsonResponse({ id: 'session-a' }, { status: 201 })
      }
      if (url === '/api/quizzes/quiz-a/attempts?subjectId=5' && options.method === 'POST') {
        return jsonResponse({ id: 'attempt-a' }, { status: 201 })
      }
      if (url === '/api/flashcards/study-sessions/session-a/complete?subjectId=5') {
        return jsonResponse({ status: 'completed' })
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
    await user.click(screen.getByRole('button', { name: 'Finalizar' }))

    expect(await screen.findByText('Resultado final')).toBeInTheDocument()
    const answerRequest = fetchMock.mock.calls.find(([url, options]) =>
      url === '/api/quizzes/quiz-a/attempts?subjectId=5' && options?.method === 'POST',
    )
    expect(JSON.parse(answerRequest[1].body)).toEqual({
      questionId: 'question-a',
      selectedOptionId: 'option-a',
      sessionId: 'session-a',
      idempotencyKey: expect.any(String),
    })
  })
})
