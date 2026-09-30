import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { clearSession } from '../lib/session'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  // La sesión vive en un módulo, no en el árbol de React: si no se limpia acá, se
  // filtra de un test al siguiente.
  clearSession()
})

// jsdom no implementa scrollIntoView y varios componentes lo usan para seguir el scroll
// (el chat, al llegar un mensaje). Sin el shim, montarlos tira TypeError.
if (!Element.prototype.scrollIntoView) {
	Element.prototype.scrollIntoView = () => {}
}
