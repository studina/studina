/**
 * Carga el valor del localStorage o devuelve el fallback.
 */
export function loadFromStorage(key, fallback) {
	try {
		const raw = localStorage.getItem(key)
		return raw !== null ? JSON.parse(raw) : fallback
	} catch {
		return fallback
	}
}

/**
 * Guarda un valor en localStorage.
 */
export function saveToStorage(key, value) {
	try {
		localStorage.setItem(key, JSON.stringify(value))
	} catch {
		// silenciar errores de cuota, etc.
	}
}
