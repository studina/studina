import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProgressWidget from './ProgressWidget'
import { setSession } from '../lib/session'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) })
}

const afterLoading = { timeout: 3000 }

function renderWidget(overview, props = {}) {
  vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))
  render(
    <StrictMode>
      <ProgressWidget
        subject={{ id: 'subject-1', name: 'Sistemas Distribuidos' }}
        user={{ userId: 'user-a', token: 'token-a' }}
        onOpen={vi.fn()}
        {...props}
      />
    </StrictMode>,
  )
}

describe('ProgressWidget', () => {
  beforeEach(() => {
    setSession({ token: 'token-a' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('resume el dominio de la materia y el tema más flojo', async () => {
    renderWidget({
      average_mastery: 0.62,
      practiced_topics: 2,
      strong_topics: 1,
      total_topics: 4,
      topics: [
        { topic_id: 'a', topic_name: 'Consistencia', mastery_score: 0.9, level: 'strong' },
        { topic_id: 'b', topic_name: 'Eleccion de lider', mastery_score: 0.34, level: 'weak' },
        { topic_id: 'c', topic_name: 'Sin practicar', mastery_score: null, level: null },
      ],
    })

    expect(await screen.findByText('62%', {}, afterLoading)).toBeInTheDocument()
    expect(screen.getByText(/A repasar:/)).toHaveTextContent('Eleccion de lider')
  })

  it('no propone repasar un tema que nunca se practicó', async () => {
    // Sin evidencia no es lo mismo que mastery bajo: un tema sin practicar no compite
    // por ser "el más flojo".
    renderWidget({
      average_mastery: 0.9,
      practiced_topics: 1,
      strong_topics: 1,
      total_topics: 2,
      topics: [
        { topic_id: 'a', topic_name: 'Consistencia', mastery_score: 0.9, level: 'strong' },
        { topic_id: 'b', topic_name: 'Sin practicar', mastery_score: null, level: null },
      ],
    })

    expect(await screen.findByText(/A repasar:/, {}, afterLoading)).toHaveTextContent('Consistencia')
  })

  it('invita a empezar cuando todavía no se practicó nada', async () => {
    renderWidget({
      average_mastery: null,
      practiced_topics: 0,
      strong_topics: 0,
      total_topics: 3,
      topics: [{ topic_id: 'a', topic_name: 'Consistencia', mastery_score: null, level: null }],
    })

    expect(await screen.findByText(/Todavía no practicaste nada/, {}, afterLoading)).toBeInTheDocument()
  })

  it('abre la pantalla de progreso al clickearlo', async () => {
    const onOpen = vi.fn()
    renderWidget({
      average_mastery: 0.5, practiced_topics: 1, strong_topics: 0, total_topics: 1, topics: [],
    }, { onOpen })

    await userEvent.click(await screen.findByRole('button', { name: /Tu progreso/ }, afterLoading))
    expect(onOpen).toHaveBeenCalled()
  })

  it('se esconde en vez de romper la pantalla si el progreso no carga', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse({ error: 'falló' }, { ok: false, status: 500 })))

    const { container } = render(
      <StrictMode>
        <ProgressWidget
          subject={{ id: 'subject-1', name: 'Sistemas Distribuidos' }}
          user={{ userId: 'user-a', token: 'token-a' }}
          onOpen={vi.fn()}
        />
      </StrictMode>,
    )

    await vi.waitFor(() => expect(container.querySelector('.progress-widget')).toBeNull())
  })
})
