import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FileViewer from './FileViewer'

describe('FileViewer', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('guarda el texto mediante el callback sin modificar el archivo recibido', async () => {
    const file = {
      id: 'text-1',
      name: 'Notas.txt',
      type: 'txt',
      content: 'Contenido original',
      url: null,
    }
    const onSave = vi.fn()
    const user = userEvent.setup()

    render(
      <FileViewer
        file={file}
        token="token-a"
        onClose={vi.fn()}
        onSave={onSave}
      />,
    )

    const editor = screen.getByRole('textbox')
    await user.clear(editor)
    await user.type(editor, 'Contenido editado')
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(onSave).toHaveBeenCalledWith('Contenido editado')
    expect(file.content).toBe('Contenido original')
    expect(screen.queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument()
  })

  it('solicita y muestra la vista previa PDF de un DOCX autenticado', async () => {
    const previewBlob = new Blob(['pdf'], { type: 'application/pdf' })
    const fetchMock = vi.fn(() => Promise.resolve({
      ok: true,
      blob: () => Promise.resolve(previewBlob),
    }))
    vi.stubGlobal('fetch', fetchMock)
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
