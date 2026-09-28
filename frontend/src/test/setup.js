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
