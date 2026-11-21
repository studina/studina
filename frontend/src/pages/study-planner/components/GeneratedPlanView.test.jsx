import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import GeneratedPlanView from './GeneratedPlanView'

const studySession = {
  title: 'Práctica: Análisis',
  type: 'STUDY', topicIndex: 0, partNumber: 1,
  startDateTime: '2026-11-23T18:00:00', endDateTime: '2026-11-23T19:00:00',
}
const props = {
  subject: { name: 'Materia de prueba' },
  milestone: { title: 'Parcial' },
  examDate: '2026-11-30',
  topics: [{ id: 'topic-a', title: 'ANALISIS EDITADO' }],
  planResult: { sessions: [studySession] },
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-20T12:00:00'))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('current topic names in the study plan', () => {
  it('updates the calendar and an open session detail while preserving its activity type', () => {
    const { rerender } = render(<GeneratedPlanView {...props} topics={[{ id: 'topic-a', title: 'Análisis' }]} />)
    fireEvent.click(screen.getByText('Análisis'))

    rerender(<GeneratedPlanView {...props} />)

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'Práctica: ANALISIS EDITADO' })).toBeInTheDocument()
    expect(within(dialog).getByText('ANALISIS EDITADO')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cerrar' }))
    expect(screen.getByText('ANALISIS EDITADO')).toBeInTheDocument()
    expect(screen.queryByText('Análisis')).not.toBeInTheDocument()
  })

  it('exports the current topic name in the calendar event summary and description', async () => {
    const createObjectURL = vi.fn(() => 'blob:calendar')
    vi.stubGlobal('URL', class extends URL {
      static createObjectURL = createObjectURL
      static revokeObjectURL = vi.fn()
    })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<GeneratedPlanView {...props} />)

    fireEvent.click(screen.getByRole('button', { name: /Descargar calendario/ }))

    const [blob] = createObjectURL.mock.calls[0]
    const content = await new Promise((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result)
      reader.readAsText(blob)
    })
    expect(content).toContain('SUMMARY:Práctica: ANALISIS EDITADO')
    expect(content).toContain('Tema: ANALISIS EDITADO')
    expect(content).not.toContain('Práctica: Análisis')
  })

  it('keeps titles for missing topics, general reviews and mock exams', () => {
    render(<GeneratedPlanView {...props}
      topics={[{ id: 'topic-a', title: 'Tema no disponible', unavailable: true }]}
      planResult={{ sessions: [
        studySession,
        { ...studySession, title: 'Repaso general', type: 'REVIEW', topicIndex: null,
          startDateTime: '2026-11-24T18:00:00', endDateTime: '2026-11-24T19:00:00' },
        { ...studySession, title: 'Simulacro del parcial', type: 'MOCK_EXAM', topicIndex: null,
          startDateTime: '2026-11-25T18:00:00', endDateTime: '2026-11-25T19:00:00' },
      ] }}
    />)

    expect(screen.getByText('Análisis')).toBeInTheDocument()
    expect(screen.getByText('Repaso general')).toBeInTheDocument()
    expect(screen.getByText('Simulacro del parcial')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Análisis'))
    expect(screen.getByRole('heading', { name: 'Práctica: Análisis' })).toBeInTheDocument()
    expect(within(screen.getByRole('dialog')).getByText('Tema no disponible')).toBeInTheDocument()
  })
})
