import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FileViewer from './FileViewer'
import { setSession } from '../lib/session'
import { renderDocx } from '../lib/renderDocx'

vi.mock('../lib/renderDocx', () => ({ renderDocx: vi.fn(() => Promise.resolve()) }))

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

  it('renderiza el contenido de un Markdown', async () => {
    const markdown = [
      '# Unidad 2',
      '',
      '| Algoritmo | Orden |',
      '| --- | --- |',
      '| Quicksort | n log n |',
      '',
      '<script>alert(1)</script>',
    ].join('\n')
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({
      ok: true,
      text: () => Promise.resolve(markdown),
    })))
    setSession({ token: 'token-a' })

    const { container } = render(
      <FileViewer
        file={{
          id: 'md-1',
          name: 'Resumen.md',
          type: 'md',
          url: '/api/files/download?fileId=md-1',
        }}
        token="token-a"
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByRole('heading', { name: 'Unidad 2' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toHaveTextContent('Quicksort')
    // react-markdown no interpreta HTML crudo.
    expect(container.querySelector('script')).toBeNull()
  })

  it('descarga el DOCX y lo dibuja en el navegador', async () => {
    const docxBlob = new Blob(['docx'])
    const fetchMock = vi.fn(() => Promise.resolve({
      ok: true,
      blob: () => Promise.resolve(docxBlob),
    }))
    vi.stubGlobal('fetch', fetchMock)
    // El header lo pone apiFetch a partir de la sesión, no el prop `token`.
    setSession({ token: 'token-a' })

    const { container } = render(
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

    await waitFor(() => expect(renderDocx).toHaveBeenCalledWith(
      docxBlob,
      container.querySelector('.file-docx'),
    ))
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/files/download?fileId=docx-1',
      expect.objectContaining({
        headers: { Authorization: 'Bearer token-a' },
      }),
    )
  })

  it('muestra un error si no puede dibujar el DOCX', async () => {
    renderDocx.mockRejectedValueOnce(new Error('docx inválido'))
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({
      ok: true,
      blob: () => Promise.resolve(new Blob(['no es un docx'])),
    })))
    setSession({ token: 'token-a' })

    render(
      <FileViewer
        file={{
          id: 'docx-2',
          name: 'Roto.docx',
          type: 'docx',
          url: '/api/files/download?fileId=docx-2',
        }}
        token="token-a"
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText('No se pudo mostrar el documento DOCX.')).toBeInTheDocument()
  })
})
