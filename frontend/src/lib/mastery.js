/**
 * Vocabulario compartido del progreso.
 *
 * El backend clasifica el mastery (LearningProgressPolicy.masteryLevel) y manda el nivel ya
 * resuelto, así que el frontend no repite los umbrales: sólo les pone nombre. Un topic sin
 * practicar llega con level null, y eso no es "flojo" sino "sin empezar" — la distinción
 * entre no hay evidencia y la evidencia dice que no lo sabe.
 */

export const percent = (mastery) => Math.round((Number(mastery) || 0) * 100)

const labels = {
	strong: 'Dominado',
	progressing: 'En progreso',
	weak: 'Necesita repaso',
}

export function levelLabel(level) {
	return labels[level] ?? 'Sin empezar'
}

export function levelClass(level) {
	return `level-${level ?? 'none'}`
}

/** El orden en que la pantalla agrupa los temas: primero lo que hay que atender. */
export const levelOrder = ['weak', 'progressing', 'strong', null]
