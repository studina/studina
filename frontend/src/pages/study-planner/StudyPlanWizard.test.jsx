import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StudyPlanWizard from './StudyPlanWizard'
import * as service from '../../services/studyPlanService'

vi.mock('../../services/studyPlanService', () => ({
  getLatestStudyPlan: vi.fn(),
  getStudyPlanTopics: vi.fn(),
  generateStudyPlan: vi.fn(),
  deleteStudyPlan: vi.fn(),
  StudyPlanGenerationError: class extends Error {},
}))

const originalTopics = [
  { topic_id: 'topic-a', topic_name: 'Álgebra' },
  { topic_id: 'topic-b', topic_name: 'Biología' },
]

const session = {
  title: 'Estudio: Álgebra',
  startDateTime: '2026-11-23T18:00:00',
  endDateTime: '2026-11-23T19:00:00',
  type: 'STUDY',
  topicIndex: 0,
  partNumber: 1,
}

function savedPlan(sessions = [session]) {
  return {
    milestone: { type: 'EXAM', title: 'Parcial', description: '' },
    startDate: '2026-11-23',
    examDate: '2026-11-30',
    reviewSessions: 0,
    selectedTopicIds: ['topic-a', 'topic-b'],
    availability: [],
    planResult: { canBeScheduled: true, sessions },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-20T12:00:00'))
  service.getLatestStudyPlan.mockResolvedValue(savedPlan())
  service.getStudyPlanTopics.mockResolvedValue({ topics: originalTopics })
})

afterEach(() => {
  vi.useRealTimers()
})

function renderWizard() {
  render(<StudyPlanWizard subject={{ id: 'subject-a', name: 'Materia A' }} />)
}

async function openSession(topicTitle) {
  fireEvent.click(await screen.findByText(topicTitle))
  return screen.findByRole('dialog')
}

describe('saved study plan topic indexes', () => {
  it('keeps a session attached to its topic when a rename changes alphabetical order', async () => {
    service.getStudyPlanTopics.mockResolvedValue({
      topics: [originalTopics[1], { ...originalTopics[0], topic_name: 'Zoología' }],
    })
    renderWizard()

    const dialog = await openSession('Álgebra')

    await waitFor(() => expect(within(dialog).getByText('Zoología')).toBeInTheDocument())
    expect(within(dialog).queryByText('Biología')).not.toBeInTheDocument()
  })

  it('does not shift later session indexes when an earlier topic is missing', async () => {
    service.getLatestStudyPlan.mockResolvedValue(savedPlan([
      session,
      { ...session, title: 'Estudio: Biología', topicIndex: 1,
        startDateTime: '2026-11-23T19:15:00', endDateTime: '2026-11-23T20:00:00' },
    ]))
    service.getStudyPlanTopics.mockResolvedValue({ topics: [originalTopics[1]] })
    renderWizard()

    let dialog = await openSession('Álgebra')
    await waitFor(() => expect(within(dialog).getByText('Tema no disponible')).toBeInTheDocument())
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cerrar' }))

    dialog = await openSession('Biología')
    expect(within(dialog).getByText('Biología')).toBeInTheDocument()
  })

  it('uses the new request topic order after editing and generating another plan', async () => {
    service.getStudyPlanTopics.mockResolvedValue({
      topics: [originalTopics[1], { ...originalTopics[0], topic_name: 'Zoología' }],
    })
    service.generateStudyPlan.mockResolvedValue({
      canBeScheduled: true,
      sessions: [{ ...session, title: 'Estudio: Biología' }],
    })
    renderWizard()
    const dialog = await openSession('Álgebra')
    await waitFor(() => expect(within(dialog).getByText('Zoología')).toBeInTheDocument())
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cerrar' }))
    fireEvent.click(screen.getByRole('button', { name: 'Editar configuración' }))
    fireEvent.click(screen.getByRole('button', { name: 'Generar plan' }))

    await screen.findByRole('heading', { name: 'Tu plan de estudio' })
    expect(service.generateStudyPlan).toHaveBeenCalledWith('subject-a', expect.objectContaining({
      topics: [expect.objectContaining({ topicId: 'topic-b' }), expect.objectContaining({ topicId: 'topic-a' })],
    }))
    expect(within(await openSession('Biología')).getByText('Biología')).toBeInTheDocument()
  })
})
