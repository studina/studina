import { describe, expect, it } from 'vitest'
import { timeAgo } from './timeAgo'

const now = new Date('2026-01-10T12:00:00Z')
const ago = (ms) => new Date(now.getTime() - ms).toISOString()

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

describe('timeAgo', () => {
  it('sin fecha no inventa un valor', () => {
    expect(timeAgo(null, now)).toBeNull()
    expect(timeAgo(undefined, now)).toBeNull()
    expect(timeAgo('no es una fecha', now)).toBeNull()
  })

  it('por debajo del minuto no muestra "hace 0 minutos"', () => {
    expect(timeAgo(ago(30 * 1000), now)).toBe('Hace menos de un minuto')
  })

  it('por debajo de la hora habla en minutos', () => {
    expect(timeAgo(ago(MINUTE), now)).toBe('Hace 1 minuto')
    expect(timeAgo(ago(59 * MINUTE + 59 * 1000), now)).toBe('Hace 59 minutos')
  })

  it('por debajo del día habla en horas', () => {
    expect(timeAgo(ago(HOUR), now)).toBe('Hace 1 hora')
    expect(timeAgo(ago(23 * HOUR + 59 * MINUTE), now)).toBe('Hace 23 horas')
  })

  it('de un día en adelante habla en días', () => {
    expect(timeAgo(ago(DAY), now)).toBe('Hace 1 día')
    expect(timeAgo(ago(9 * DAY + 5 * HOUR), now)).toBe('Hace 9 días')
  })

  it('una fecha en el futuro por desfase de reloj cuenta como recién', () => {
    expect(timeAgo(ago(-5 * MINUTE), now)).toBe('Hace menos de un minuto')
  })
})
