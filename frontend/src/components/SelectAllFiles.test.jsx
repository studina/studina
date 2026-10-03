import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import SelectAllFiles from './SelectAllFiles'

const files = [
  { id: 'file-a', filename: 'Apunte.pdf' },
  { id: 'file-b', filename: 'Practica.pdf' },
]

/** Se monta con estado real: lo que importa es qué selección termina aplicando. */
function Harness({ initial = [], allFiles = files }) {
  const [selectedFileIds, setSelectedFileIds] = useState(initial)
  return (
    <>
      <SelectAllFiles
        files={allFiles}
        selectedFileIds={selectedFileIds}
        onChange={setSelectedFileIds}
      />
      <span data-testid="seleccion">{selectedFileIds.join(',')}</span>
    </>
  )
}

const control = () => screen.getByRole('checkbox')
const seleccion = () => screen.getByTestId('seleccion').textContent

describe('SelectAllFiles', () => {
  it('selecciona todos los documentos de una sola vez', async () => {
    render(<Harness />)

    await userEvent.click(screen.getByText(/Seleccionar todos/))

    expect(seleccion()).toBe('file-a,file-b')
  })

  it('el mismo control deselecciona cuando ya está todo elegido', async () => {
    // Es un toggle y no dos botones: con todo elegido, la acción útil es la inversa.
    render(<Harness initial={['file-a', 'file-b']} />)

    expect(screen.getByText(/Deseleccionar todos/)).toBeInTheDocument()
    await userEvent.click(screen.getByText(/Deseleccionar todos/))

    expect(seleccion()).toBe('')
  })

  it('con una selección parcial queda en estado intermedio', () => {
    // Sin esto el control se vería vacío al elegir un documento suelto, como si no
    // hubiera nada seleccionado.
    render(<Harness initial={['file-a']} />)

    expect(control().indeterminate).toBe(true)
    expect(control().checked).toBe(false)
  })

  it('no queda en estado intermedio cuando está todo o nada', () => {
    const { unmount } = render(<Harness initial={['file-a', 'file-b']} />)
    expect(control().indeterminate).toBe(false)
    expect(control().checked).toBe(true)
    unmount()

    render(<Harness />)
    expect(control().indeterminate).toBe(false)
    expect(control().checked).toBe(false)
  })

  it('no se muestra si la materia no tiene documentos', () => {
    render(<Harness allFiles={[]} />)

    expect(screen.queryByRole('checkbox')).toBeNull()
  })

  it('ignora una selección con documentos que ya no están en la lista', () => {
    // Un archivo borrado puede quedar en la selección: no debe contar como "todo elegido".
    render(<Harness initial={['file-a', 'file-b', 'file-borrado']} allFiles={files} />)

    expect(screen.getByText(/Deseleccionar todos/)).toBeInTheDocument()
    expect(control().checked).toBe(true)
  })
})
