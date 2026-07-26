import { StrictMode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DocumentsPage from './DocumentsPage'

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  })
}

describe('DocumentsPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('carga únicamente la materia pedida y envía el token del usuario', async () => {
    const fetchMock = vi.fn(() => jsonResponse({
      data: [{
        id: 'file-a',
        filename: 'Apunte_de_Redes.pdf',
        tags: ['redes'],
      }],
      errors: [],
    }))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <StrictMode>
        <DocumentsPage
          subject={{ id: 3, name: 'Redes' }}
          user={{ userId: 'user-a', token: 'token-a' }}
          onFilesLoaded={vi.fn()}
          onBack={vi.fn()}
          onOpenFile={vi.fn()}
        />
      </StrictMode>,
    )

    expect(await screen.findByText('Apunte_de_Redes.pdf')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/files/list?subjectId=3',
      { headers: { Authorization: 'Bearer token-a' } },
    )
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
  })

  it('no intenta consultar documentos sin una sesión autenticada', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(
      <DocumentsPage
        subject={{ id: 3, name: 'Redes' }}
        user={null}
        onFilesLoaded={vi.fn()}
        onBack={vi.fn()}
        onOpenFile={vi.fn()}
      />,
    )

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByText('Subí tus primeros materiales')).toBeInTheDocument()
  })

  it('agrega a la lista un documento subido correctamente', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [], errors: [] })
      }
      if (url === '/api/files/upload') {
        return jsonResponse({ id: 'new-file', filename: 'Nuevo_apunte.pdf' })
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <DocumentsPage
        subject={{ id: 3, name: 'Redes' }}
        user={{ userId: 'user-a', token: 'token-a' }}
        onFilesLoaded={vi.fn()}
        onBack={vi.fn()}
        onOpenFile={vi.fn()}
      />,
    )

    await screen.findByText('Subí tus primeros materiales')
    const file = new File(['contenido'], 'Nuevo_apunte.pdf', { type: 'application/pdf' })
    await user.upload(screen.getByLabelText('＋ Agregar documento'), file)

    expect(await screen.findAllByText('Nuevo_apunte.pdf')).toHaveLength(2)
    const uploadCall = fetchMock.mock.calls.find(([url]) => url === '/api/files/upload')
    expect(uploadCall[1].headers.Authorization).toBe('Bearer token-a')
    expect(uploadCall[1].body.get('subjectId')).toBe('3')
    expect(uploadCall[1].body.get('file').name).toBe('Nuevo_apunte.pdf')
  })

  it('muestra el error del backend cuando un documento no puede procesarse', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [], errors: [] })
      }
      if (url === '/api/files/upload') {
        return jsonResponse(
          { error: 'El PDF no contiene texto legible' },
          { ok: false, status: 500 },
        )
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <DocumentsPage
        subject={{ id: 3, name: 'Redes' }}
        user={{ userId: 'user-a', token: 'token-a' }}
        onFilesLoaded={vi.fn()}
        onBack={vi.fn()}
        onOpenFile={vi.fn()}
      />,
    )

    await screen.findByText('Subí tus primeros materiales')
    const file = new File(['contenido'], 'Imagen_escaneada.pdf', { type: 'application/pdf' })
    await user.upload(screen.getByLabelText('＋ Agregar documento'), file)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Imagen_escaneada.pdf: El PDF no contiene texto legible',
    )
    expect(screen.getByText('Subí tus primeros materiales')).toBeInTheDocument()
  })

  it('explica que el nombre está duplicado cuando el backend responde 409', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [], errors: [] })
      }
      if (url === '/api/files/upload') {
        return jsonResponse(
          { error: 'Ya existe un archivo con el nombre "Repetido.pdf" en esta materia' },
          { ok: false, status: 409 },
        )
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <DocumentsPage
        subject={{ id: 3, name: 'Redes' }}
        user={{ userId: 'user-a', token: 'token-a' }}
        onFilesLoaded={vi.fn()}
        onBack={vi.fn()}
        onOpenFile={vi.fn()}
      />,
    )

    await screen.findByText('Subí tus primeros materiales')
    await user.upload(
      screen.getByLabelText('＋ Agregar documento'),
      new File(['contenido'], 'Repetido.pdf', { type: 'application/pdf' }),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Ya existe un archivo con el nombre "Repetido.pdf" en esta materia',
    )
  })

  it('rechaza un PDF que no puede indexarse para Dina', async () => {
    const fetchMock = vi.fn((url) => {
      if (url.startsWith('/api/files/list')) {
        return jsonResponse({ data: [], errors: [] })
      }
      if (url === '/api/files/upload') {
        return jsonResponse(
          {
            error: 'No se pudo extraer texto del documento',
            fileStored: false,
          },
          { ok: false, status: 422 },
        )
      }
      throw new Error(`Request inesperada: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <DocumentsPage
        subject={{ id: 3, name: 'Redes' }}
        user={{ userId: 'user-a', token: 'token-a' }}
        onFilesLoaded={vi.fn()}
        onBack={vi.fn()}
        onOpenFile={vi.fn()}
      />,
    )

    await screen.findByText('Subí tus primeros materiales')
    await user.upload(
      screen.getByLabelText('＋ Agregar documento'),
      new File([''], 'Vacio.pdf', { type: 'application/pdf' }),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Vacio.pdf: No se pudo extraer texto del documento',
    )
    expect(screen.queryByText('Vacio.pdf')).not.toBeInTheDocument()
  })
})
