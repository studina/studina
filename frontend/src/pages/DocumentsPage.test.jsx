import { StrictMode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
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
})
