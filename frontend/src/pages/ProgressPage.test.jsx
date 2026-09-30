import { StrictMode } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
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

/**
 * Monta la pantalla con un tema abierto, como cuando se entra por
 * /materias/:id/progreso/:topicId.
 */
function renderTopicDetail(topicId, props = {}) {
  return render(
    <MemoryRouter initialEntries={[`/progreso/${topicId}`]}>
      <Routes>
        <Route
          path="/progreso/:topicId"
          element={
            <ProgressPage
              subject={{ id: 'subject-1', name: 'Sistemas Distribuidos' }}
              user={{ userId: 'user-a', token: 'token-a' }}
              onBack={vi.fn()}
              onCloseTopic={vi.fn()}
              {...props}
            />
          }
        />
      </Routes>
    </MemoryRouter>,
  )
}

/** El contenedor destacado del tema que más repaso necesita. */
async function focusSection() {
  const eyebrow = await screen.findByText('Empezá por acá', {}, afterLoading)
  return eyebrow.closest('section')
}

/** Una columna del tablero, por su título. */
function column(title) {
  return screen.getByText(title).closest('.progress-column')
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

  it('destaca en su propio contenedor el tema que más repaso necesita', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderPage()

    const focus = await focusSection()
    expect(within(focus).getByRole('heading', { name: 'Eleccion de lider' })).toBeInTheDocument()
    expect(focus).toHaveTextContent('20%')
  })

  it('no destaca nada cuando el tema más flojo ya está dominado', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse({
      ...overview,
      topics: [overview.topics[2]],
    })))

    renderPage()

    expect(await screen.findByText('Consistencia', {}, afterLoading)).toBeInTheDocument()
    expect(screen.queryByText('Empezá por acá')).not.toBeInTheDocument()
  })

  it('agrupa cada tema en la columna de su estado, con el conteo', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderPage()

    await screen.findByText('Consistencia', {}, afterLoading)

    expect(within(column('Necesitan repaso')).getByText('Eleccion de lider')).toBeInTheDocument()
    expect(within(column('En progreso')).getByText('Relojes logicos')).toBeInTheDocument()
    expect(within(column('Dominados')).getByText('Consistencia')).toBeInTheDocument()
    expect(within(column('Sin empezar')).getByText('Tolerancia a fallos')).toBeInTheDocument()
    expect(within(column('Dominados')).getByText('1')).toBeInTheDocument()
  })

  it('muestra un tema sin practicar sin barra de dominio', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderPage()

    await screen.findByText('Consistencia', {}, afterLoading)
    const card = screen.getByText('Tolerancia a fallos').closest('li')

    // Sin evidencia no es 0% de dominio: la tarjeta no dibuja barra.
    expect(card.querySelector('.progress-bar')).toBeNull()
    expect(card).toHaveTextContent('1 recurso disponible')

    // Un tema practicado sí la dibuja.
    expect(
      screen.getByText('Consistencia').closest('li').querySelector('.progress-bar'),
    ).not.toBeNull()
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

    // El tema destacado se repite en su columna, así que la query se acota al destacado.
    const focus = await focusSection()
    await userEvent.click(within(focus).getByRole('button', { name: 'Apunte_redes.pdf' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/files/download?fileId=file-1',
      { headers: { Authorization: 'Bearer token-a' } },
    ))
  })

  it('genera un recurso del tema con sus documentos ya elegidos', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))
    const onGenerateForTopic = vi.fn()

    renderPage({ onGenerateForTopic })

    const focus = await focusSection()
    await userEvent.click(within(focus).getByRole('button', { name: /Generar recurso/ }))
    await userEvent.click(within(focus).getByRole('menuitem', { name: /Flashcards/ }))

    expect(onGenerateForTopic).toHaveBeenCalledWith('flashcards', {
      fileIds: ['file-1'],
      topicName: 'Eleccion de lider',
    })
  })

  it('ofrece también generar un cuestionario del mismo tema', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))
    const onGenerateForTopic = vi.fn()

    renderPage({ onGenerateForTopic })

    const focus = await focusSection()
    await userEvent.click(within(focus).getByRole('button', { name: /Generar recurso/ }))
    await userEvent.click(within(focus).getByRole('menuitem', { name: /Cuestionario/ }))

    expect(onGenerateForTopic).toHaveBeenCalledWith('quiz', {
      fileIds: ['file-1'],
      topicName: 'Eleccion de lider',
    })
  })

  it('no ofrece generar cuando el tema no tiene documentos asociados', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderPage({ onGenerateForTopic: vi.fn() })

    await screen.findByText('Consistencia', {}, afterLoading)
    // Sin archivos no hay de dónde generar: el generador exige al menos un documento.
    const card = screen.getByText('Consistencia').closest('li')
    expect(within(card).queryByRole('button', { name: /Generar recurso/ })).toBeNull()
    expect(card).toHaveTextContent('Sin documentos asociados')
  })

  it('avisa cuando el progreso no se pudo cargar', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse({ error: 'No se pudo cargar tu progreso' }, { ok: false, status: 500 })))

    renderPage()

    expect(await screen.findByRole('alert', {}, afterLoading))
      .toHaveTextContent('No se pudo cargar tu progreso')
  })

  it('abre el detalle al clickear el nombre del tema', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))
    const onOpenTopic = vi.fn()

    renderPage({ onOpenTopic })

    await userEvent.click(await screen.findByRole('button', { name: 'Consistencia' }, afterLoading))

    expect(onOpenTopic).toHaveBeenCalledWith('topic-strong')
  })

  it('el detalle muestra la descripción del tema y sus datos', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse({
      ...overview,
      topics: [{ ...overview.topics[0], description: 'Cómo se elige un coordinador.' }],
    })))

    renderTopicDetail('topic-weak')

    expect(await screen.findByText('Cómo se elige un coordinador.', {}, afterLoading))
      .toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Eleccion de lider' })).toBeInTheDocument()
    // El tablero se reemplaza: el detalle es la vista, no un agregado.
    expect(screen.queryByRole('heading', { name: 'Todos los temas' })).not.toBeInTheDocument()
  })

  it('las tarjetas del tablero no listan el material del tema', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderPage()

    const card = (await screen.findByText('Tolerancia a fallos', {}, afterLoading)).closest('li')
    // El material vive en el detalle: acá llenaba cada tarjeta de chips.
    expect(within(card).queryByRole('button', { name: 'Practica.docx' })).toBeNull()
  })

  it('el detalle del tema sí lista su material', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderTopicDetail('topic-weak')

    expect(await screen.findByRole('button', { name: 'Apunte_redes.pdf' }, afterLoading))
      .toBeInTheDocument()
  })

  it('el detalle no arrastra nada de la materia: sólo habla del tema', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderTopicDetail('topic-weak')

    await screen.findByRole('heading', { name: 'Eleccion de lider' }, afterLoading)

    // Ni las métricas generales...
    expect(screen.queryByText('Temas dominados')).not.toBeInTheDocument()
    expect(screen.queryByText('Cobertura del material')).not.toBeInTheDocument()
    expect(screen.queryByText(/Promedio sobre los/)).not.toBeInTheDocument()
    // ...ni el cierre con los accesos a la biblioteca.
    expect(screen.queryByText('¿Listo para practicar?')).not.toBeInTheDocument()
  })

  it('el tablero sí muestra las métricas de la materia', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderPage()

    expect(await screen.findByText('Temas dominados', {}, afterLoading)).toBeInTheDocument()
    expect(screen.getByText('¿Listo para practicar?')).toBeInTheDocument()
  })

  it('un tema sin descripción no muestra ningún texto en su lugar', async () => {
    // `description` es opcional en la tabla de topics y muchos no la tienen.
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    const { container } = renderTopicDetail('topic-weak')

    expect(await screen.findByRole('heading', { name: 'Eleccion de lider' }, afterLoading)).toBeInTheDocument()
    expect(container.querySelector('.topic-detail-description')).toBeNull()
    expect(screen.queryByText(/no tiene descripción/)).not.toBeInTheDocument()
  })

  it('un tema que no está en los datos cae al tablero', async () => {
    // Un link viejo no es un error: se muestra lo que sí existe.
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))

    renderTopicDetail('topic-borrado')

    expect(await screen.findByRole('heading', { name: 'Todos los temas' }, afterLoading))
      .toBeInTheDocument()
  })

  it('ofrece hablar con Dina sobre el tema, con sus documentos y la pregunta lista', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))
    const onChatAboutTopic = vi.fn()

    renderTopicDetail('topic-weak', { onChatAboutTopic })

    await userEvent.click(
      await screen.findByRole('button', { name: /Hablá con Dina sobre este tema/ }, afterLoading),
    )

    expect(onChatAboutTopic).toHaveBeenCalledWith({
      fileIds: ['file-1'],
      prompt: expect.stringContaining('Eleccion de lider'),
    })
  })

  it('desde el detalle se puede generar un recurso del tema', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))
    const onGenerateForTopic = vi.fn()

    renderTopicDetail('topic-weak', { onGenerateForTopic })

    await userEvent.click(
      await screen.findByRole('button', { name: /Generar recurso/ }, afterLoading),
    )
    await userEvent.click(screen.getByRole('menuitem', { name: /Flashcards/ }))

    expect(onGenerateForTopic).toHaveBeenCalledWith('flashcards', {
      fileIds: ['file-1'],
      topicName: 'Eleccion de lider',
    })
  })

  it('vuelve al tablero desde el detalle', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(overview)))
    const onCloseTopic = vi.fn()

    renderTopicDetail('topic-weak', { onCloseTopic })

    await userEvent.click(
      await screen.findByRole('button', { name: /Todos los temas/ }, afterLoading),
    )

    expect(onCloseTopic).toHaveBeenCalled()
  })
})

describe('ProgressPage: crear un tema a mano', () => {
  const subjectFiles = [
    { id: 'file-1', filename: 'Apunte_redes.pdf' },
    { id: 'file-2', filename: 'Practica.docx' },
  ]

  beforeEach(() => {
    setSession({ token: 'token-a' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** Un Directus de mentira: el panorama, los documentos y la respuesta del POST de topics. */
  function stubApi(createResponse = () => jsonResponse(
    { topic_id: 'topic-created', topic_name: 'Consenso', description: '' },
    { status: 201 },
  )) {
    const fetchMock = vi.fn((url, options = {}) => {
      if (url.startsWith('/api/files/list')) return jsonResponse({ data: subjectFiles })
      if (url === '/api/topics' && options.method === 'POST') return createResponse()
      return jsonResponse(overview)
    })
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  const progressCalls = (fetchMock) =>
    fetchMock.mock.calls.filter(([url]) => url.startsWith('/api/progress')).length

  async function openModal(user) {
    await user.click(await screen.findByRole('button', { name: '+ Nuevo tema' }, afterLoading))
    return screen.findByRole('dialog')
  }

  it('no deja crear el tema sin título o sin documentos', async () => {
    stubApi()
    const user = userEvent.setup()
    renderPage()

    const dialog = await openModal(user)
    const submit = within(dialog).getByRole('button', { name: 'Crear tema' })
    await within(dialog).findByText('Apunte_redes.pdf')

    expect(submit).toBeDisabled()

    await user.type(within(dialog).getByLabelText('Título'), 'Consenso')
    expect(submit).toBeDisabled()

    await user.click(within(dialog).getByLabelText('Apunte_redes.pdf'))
    expect(submit).toBeEnabled()

    await user.clear(within(dialog).getByLabelText('Título'))
    await user.type(within(dialog).getByLabelText('Título'), '   ')
    expect(submit).toBeDisabled()
  })

  it('crea el tema con sus documentos y vuelve a pedir el progreso', async () => {
    const fetchMock = stubApi()
    const user = userEvent.setup()
    renderPage()

    const dialog = await openModal(user)
    await user.type(within(dialog).getByLabelText('Título'), '  Consenso ')
    await user.type(within(dialog).getByLabelText(/Descripción/), 'Paxos y Raft')
    await user.click(await within(dialog).findByLabelText('Practica.docx'))
    const callsBefore = progressCalls(fetchMock)
    await user.click(within(dialog).getByRole('button', { name: 'Crear tema' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const [, options] = fetchMock.mock.calls.find(([url]) => url === '/api/topics')
    expect(JSON.parse(options.body)).toEqual({
      subjectId: 'subject-1',
      name: 'Consenso',
      description: 'Paxos y Raft',
      fileIds: ['file-2'],
    })
    await waitFor(() => expect(progressCalls(fetchMock)).toBeGreaterThan(callsBefore))
  })

  it('muestra en el modal el error de un nombre repetido', async () => {
    stubApi(() => jsonResponse(
      { error: 'Ya existe un tema llamado "Consistencia" en esta materia' },
      { ok: false, status: 409 },
    ))
    const user = userEvent.setup()
    renderPage()

    const dialog = await openModal(user)
    await user.type(within(dialog).getByLabelText('Título'), 'consistencia')
    await user.click(await within(dialog).findByLabelText('Apunte_redes.pdf'))
    await user.click(within(dialog).getByRole('button', { name: 'Crear tema' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Ya existe un tema llamado "Consistencia"')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('ofrece crear un tema también cuando la materia no tiene ninguno', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse({ ...overview, topics: [], total_topics: 0 })))
    renderPage()

    expect(await screen.findByText('Todavía no hay temas en esta materia', {}, afterLoading)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ Nuevo tema' })).toBeInTheDocument()
  })
})

describe('ProgressPage: editar un tema', () => {
  beforeEach(() => {
    setSession({ token: 'token-a' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function stubApi(updateResponse = () => jsonResponse(
    { topic_id: 'topic-weak', topic_name: 'Elección de líder', description: 'Bully y anillo' },
  )) {
    const fetchMock = vi.fn((url, options = {}) => {
      if (options.method === 'PATCH') return updateResponse()
      return jsonResponse(overview)
    })
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  async function openEditModal(user) {
    await user.click(await screen.findByRole('button', { name: /Editar/ }, afterLoading))
    return screen.findByRole('dialog')
  }

  it('el tablero no ofrece editar: se edita desde el detalle', async () => {
    stubApi()
    renderPage()

    await screen.findByText('Consistencia', {}, afterLoading)
    expect(screen.queryByRole('button', { name: /Editar/ })).not.toBeInTheDocument()
  })

  it('abre el modal con el título y la descripción actuales, sin documentos', async () => {
    stubApi()
    const user = userEvent.setup()
    renderTopicDetail('topic-weak')

    const dialog = await openEditModal(user)

    expect(within(dialog).getByText('Editar tema')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Título')).toHaveValue('Eleccion de lider')
    expect(within(dialog).queryByText('Documentos')).not.toBeInTheDocument()
    // Sin cambios no hay nada que guardar.
    expect(within(dialog).getByRole('button', { name: 'Guardar cambios' })).toBeDisabled()
  })

  it('guarda el título y la descripción con un PATCH y vuelve a pedir el progreso', async () => {
    const fetchMock = stubApi()
    const user = userEvent.setup()
    renderTopicDetail('topic-weak')

    const dialog = await openEditModal(user)
    const title = within(dialog).getByLabelText('Título')
    await user.clear(title)
    await user.type(title, 'Elección de líder ')
    await user.type(within(dialog).getByLabelText(/Descripción/), 'Bully y anillo')
    const progressBefore = fetchMock.mock.calls.filter(([url]) => url.startsWith('/api/progress')).length
    await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const [url, options] = fetchMock.mock.calls.find(([, init]) => init?.method === 'PATCH')
    expect(url).toBe('/api/topics/topic-weak')
    expect(JSON.parse(options.body)).toEqual({ name: 'Elección de líder', description: 'Bully y anillo' })
    await waitFor(() => expect(
      fetchMock.mock.calls.filter(([calledUrl]) => calledUrl.startsWith('/api/progress')).length,
    ).toBeGreaterThan(progressBefore))
  })

  it('muestra en el modal el error de un nombre que ya usa otro tema', async () => {
    stubApi(() => jsonResponse(
      { error: 'Ya existe un tema llamado "Consistencia" en esta materia' },
      { ok: false, status: 409 },
    ))
    const user = userEvent.setup()
    renderTopicDetail('topic-weak')

    const dialog = await openEditModal(user)
    const title = within(dialog).getByLabelText('Título')
    await user.clear(title)
    await user.type(title, 'consistencia')
    await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Ya existe un tema llamado "Consistencia"')
  })
})
