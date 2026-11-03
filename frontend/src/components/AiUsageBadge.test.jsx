import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import AiUsageBadge from './AiUsageBadge'

describe('AiUsageBadge', () => {
  it('muestra el proveedor, el modelo y el origen de la configuración', () => {
    render(
      <AiUsageBadge
        provider="openai"
        model="gpt-4.1-mini"
        source="user"
      />,
    )

    expect(screen.getByText('Estás usando')).toBeInTheDocument()
    expect(screen.getByText('OpenAI')).toBeInTheDocument()
    expect(screen.getByText('gpt-4.1-mini')).toBeInTheDocument()
    expect(screen.getByText('Personal')).toBeInTheDocument()
  })

  it('explica que está consultando mientras carga la configuración', () => {
    render(<AiUsageBadge loading />)

    expect(screen.getByText('Consultando IA...')).toBeInTheDocument()
  })
})
