import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FileViewer from './FileViewer'
import { setSession } from '../lib/session'

describe('FileViewer', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('descarga y muestra el contenido de un TXT como texto de solo lectura', async () => {
    const fetchMock = vi.fn(() => Promise.resolve({
      ok: true,
      text: () => Promise.resolve('Repaso de la unidad 1: semáforos'),
    }))
    vi.stubGlobal('fetch', fetchMock)
    setSession({ token: 'token-a' })

    render(
      <FileViewer
        file={{
          id: 'text-1',
          name: 'Notas.txt',
          type: 'txt',
          url: '/api/files/download?fileId=text-1',
        }}
        token="token-a"
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText('Repaso de la unidad 1: semáforos')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/files/download?fileId=text-1',
      expect.objectContaining({
        headers: { Authorization: 'Bearer token-a' },
      }),
    )
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('solicita y muestra la vista previa PDF de un DOCX autenticado', async () => {
    const previewBlob = new Blob(['pdf'], { type: 'application/pdf' })
    const fetchMock = vi.fn(() => Promise.resolve({
      ok: true,
      blob: () => Promise.resolve(previewBlob),
    }))
    vi.stubGlobal('fetch', fetchMock)
    // El header lo pone apiFetch a partir de la sesión, no el prop `token`.
    setSession({ token: 'token-a' })
    const createObjectUrl = vi.fn(() => 'http://localhost/docx-preview.pdf')
    const revokeObjectUrl = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: createObjectUrl,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      value: revokeObjectUrl,
    })

    render(
      <FileViewer
        file={{
          id: 'docx-1',
          name: 'Concurrencia.docx',
          type: 'docx',
          url: '/api/files/download?fileId=docx-1',
        }}
        token="token-a"
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByTitle('Concurrencia.docx')).toHaveAttribute(
      'src',
      'http://localhost/docx-preview.pdf',
    )
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/files/preview?fileId=docx-1',
      expect.objectContaining({
        headers: { Authorization: 'Bearer token-a' },
      }),
    )
    expect(createObjectUrl).toHaveBeenCalledWith(previewBlob)
  })
})
