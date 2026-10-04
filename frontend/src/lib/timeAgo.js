const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

const plural = (count, singular, pluralForm) => `Hace ${count} ${count === 1 ? singular : pluralForm}`

/**
 * Hace cuánto pasó algo, en la unidad más grande que no da cero: minutos por debajo de la
 * hora, horas por debajo del día y días de ahí en más. Redondea para abajo, así que 59
 * minutos todavía son minutos. Sin fecha devuelve null y el que llama decide qué mostrar.
 *
 * Una fecha en el futuro sólo puede ser desfase entre el reloj del servidor y el del
 * navegador, así que se toma como recién.
 */
export function timeAgo(isoDate, now = new Date()) {
	if (!isoDate) return null
	const elapsed = now.getTime() - new Date(isoDate).getTime()
	if (Number.isNaN(elapsed)) return null

	if (elapsed < MINUTE) return 'Hace menos de un minuto'
	if (elapsed < HOUR) return plural(Math.floor(elapsed / MINUTE), 'minuto', 'minutos')
	if (elapsed < DAY) return plural(Math.floor(elapsed / HOUR), 'hora', 'horas')
	return plural(Math.floor(elapsed / DAY), 'día', 'días')
}
