import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import FileViewer from './FileViewer'

describe('FileViewer', () => {
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
})
