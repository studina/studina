import { StrictMode } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProgressPage from './ProgressPage'
import { setSession } from '../lib/session'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

// La pantalla muestra el spinner mientras dura el pedido, así que las queries esperan;
// el margen extra es sólo para que un runner lento no las haga fallar.
const afterLoading = { timeout: 3000 }

/**
 * Una materia con los cuatro estados que la pantalla tiene que distinguir: un tema flojo,
 * uno en progreso, uno dominado y uno que nadie practicó.
 */
const overview = {
  subject_id: 'subject-1',
  average_mastery: 0.55,
  practiced_topics: 3,
  strong_topics: 1,
  total_topics: 4,
  coverage: 0.5,
  total_resources: 12,
  total_attempts: 21,
  last_seen_at: '2026-01-02T10:00:00Z',
  topics: [
    {
      topic_id: 'topic-weak',
      topic_name: 'Eleccion de lider',
      mastery_score: 0.2,
      level: 'weak',
      unique_resources_seen: 1,
      total_resources: 4,
      total_attempts: 3,
      coverage: 0.25,
      files: [{ id: 'file-1', filename: 'Apunte_redes.pdf' }],
    },
    {
      topic_id: 'topic-mid',
      topic_name: 'Relojes logicos',
      mastery_score: 0.55,
      level: 'progressing',
      unique_resources_seen: 2,
      total_resources: 4,
      total_attempts: 8,
      coverage: 0.5,
      files: [],
    },
    {
      topic_id: 'topic-strong',
      topic_name: 'Consistencia',
      mastery_score: 0.9,
      level: 'strong',
      unique_resources_seen: 3,
      total_resources: 3,
      total_attempts: 10,
      coverage: 1,
      files: [],
    },
    {
      topic_id: 'topic-new',
      topic_name: 'Tolerancia a fallos',
      mastery_score: null,
      level: null,
      unique_resources_seen: 0,
      total_resources: 1,
      total_attempts: 0,
      coverage: 0,
      files: [{ id: 'file-2', filename: 'Practica.docx' }],
    },
  ],
}

function renderPage(props = {}) {
  return render(
    <StrictMode>
      <ProgressPage
        subject={{ id: 'subject-1', name: 'Sistemas Distribuidos' }}
        user={{ userId: 'user-a', token: 'token-a' }}
        onBack={vi.fn()}
        {...props}
      />
    </StrictMode>,
  )
}

describe('ProgressPage', () => {
  // El header Authorization lo pone apiFetch a partir de la sesión del módulo, así que
  // hay que abrirla: el prop `user` sólo sirve para saber que hay alguien logueado.
  beforeEach(() => {
    setSession({ token: 'token-a' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('pide el progreso de la materia con el token del usuario', async () => {
    const fetchMock = vi.fn(() => jsonResponse(overview))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(await screen.findByText('Consistencia', {}, afterLoading)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/progress?subjectId=subject-1',
      { headers: { Authorization: 'Bearer token-a' } },
    )
  })

  it('pone los temas flojos primero, del más flojo al menos flojo', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse({
      ...overview,
      topics: [
        { ...overview.topics[1], topic_id: 'topic-b', topic_name: 'Menos flojo', mastery_score: 0.3, level: 'weak' },
        { ...overview.topics[0], topic_id: 'topic-a', topic_name: 'Mas flojo', mastery_score: 0.1, level: 'weak' },
      ],
    })))

    renderPage()

    const review = (await screen.findByRole('heading', { name: 'Qué te conviene repasar' }, afterLoading))
      .closest('section')
    const names = [...review.querySelectorAll('.topic-name')].map((node) => node.textContent)
    expect(names).toEqual(['Mas flojo', 'Menos flojo'])
  })

  it('muestra un tema sin practicar como "Sin empezar" y sin barra de dominio', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderPage()

    const row = (await screen.findByText('Tolerancia a fallos', {}, afterLoading)).closest('li')
    expect(row).toHaveTextContent('Sin empezar')
    // Sin evidencia no es 0% de dominio: la fila no dibuja barra.
    expect(row.querySelector('.progress-bar')).toBeNull()
    expect(row).toHaveTextContent('1 recurso disponible para practicar')

    // Un tema practicado sí la dibuja.
    const practiced = screen.getByText('Consistencia').closest('li')
    expect(practiced.querySelector('.progress-bar')).not.toBeNull()
  })

  it('aclara que el promedio es sobre los temas practicados y no sobre toda la materia', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderPage()

    expect(
      await screen.findByText(/Promedio sobre los 3 temas practicados, no sobre los 4/, {}, afterLoading),
    ).toBeInTheDocument()
  })

  it('abre el archivo del tema en una pestaña nueva al clickearlo', async () => {
    const fetchMock = vi.fn((url) => {
      if (String(url).startsWith('/api/progress')) return jsonResponse(overview)
      if (String(url).startsWith('/api/files/download')) {
        return Promise.resolve({ ok: true, status: 200, blob: () => Promise.resolve(new Blob(['pdf'])) })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('open', vi.fn(() => ({ location: '', close: vi.fn() })))
    vi.stubGlobal('URL', { ...URL, createObjectURL: vi.fn(() => 'blob:apunte'), revokeObjectURL: vi.fn() })

    renderPage()

    // Un tema flojo se lista dos veces —en "Qué te conviene repasar" y en el listado
    // completo—, así que la query se acota a la sección de repaso.
    const review = (await screen.findByRole('heading', { name: 'Qué te conviene repasar' }, afterLoading))
      .closest('section')
    await userEvent.click(within(review).getByRole('button', { name: 'Apunte_redes.pdf' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/files/download?fileId=file-1',
      { headers: { Authorization: 'Bearer token-a' } },
    ))
  })

  it('avisa cuando el progreso no se pudo cargar', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse({ error: 'No se pudo cargar tu progreso' }, { ok: false, status: 500 })))

    renderPage()

    expect(await screen.findByRole('alert', {}, afterLoading))
      .toHaveTextContent('No se pudo cargar tu progreso')
  })
})
