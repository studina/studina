import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import AvailabilityStep from './AvailabilityStep'

describe('AvailabilityStep', () => {
  it.each([
    ['new availability', '18:00', '20:00'],
    ['persisted availability', '18:00:00', '20:00:00'],
  ])('renders and allows removing %s', (_, startTime, endTime) => {
    const onRemoveAvailability = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    render(
      <AvailabilityStep
        availability={[{ id: 'slot-1', dayOfWeek: 'MONDAY', startTime, endTime }]}
        onAddAvailability={vi.fn()}
        onUpdateAvailability={vi.fn()}
        onRemoveAvailability={onRemoveAvailability}
      />,
    )

    fireEvent.click(screen.getByText('Disponible'))

    expect(onRemoveAvailability).toHaveBeenCalledWith('slot-1')
  })
})
