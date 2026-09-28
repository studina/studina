/**
 * La sesión del usuario y el fetch autenticado.
 *
 * El token de acceso que emite Directus dura 15 minutos. Antes se guardaba al iniciar
 * sesión y se mandaba tal cual para siempre: cualquier pantalla abierta más de ese rato
 * empezaba a recibir 401. En flashcards eso se comía autoevaluaciones —las escrituras van
 * en segundo plano— y el usuario se enteraba recién al terminar la ronda, con las métricas
 * ya incompletas.
 *
 * La sesión vive acá, en el módulo, y no en el estado de React, porque hay que poder leer
 * el token vigente en medio de un request ya disparado: el componente que lo capturó puede
 * tener una copia vieja. `apiFetch` renueva antes del vencimiento y, si igual vuelve un 401,
 * renueva y reintenta una vez.
 *
 * Nada de esto toca el almacenamiento del navegador: el refresh token vive en memoria y se
 * pierde al recargar, igual que el resto de la sesión.
 */

// Margen para renovar antes de que venza: cubre el viaje del request y los relojes
// desfasados entre el navegador y el servidor.
const REFRESH_MARGIN_MS = 60_000

let session = null
let refreshRequest = null
let expiredListener = null

/** Guarda la sesión recién abierta (login o registro). */
export function setSession(user) {
	session = user?.token
		? {
			token: user.token,
			refreshToken: user.refreshToken ?? null,
			expiresAt: expiryFrom(user.expiresIn),
		}
		: null
	refreshRequest = null
}

export function clearSession() {
	session = null
	refreshRequest = null
}

/**
 * Avisa cuando la sesión se terminó de verdad: el refresh token también venció o fue
 * revocado, así que no hay nada que renovar y hay que volver a iniciar sesión.
 */
export function onSessionExpired(listener) {
	expiredListener = listener
	return () => {
		if (expiredListener === listener) expiredListener = null
	}
}

/**
 * Sin `expiresIn` no hay renovación preventiva: queda sólo el reintento ante un 401.
 * Es preferible a inventar una duración que no sabemos.
 */
function expiryFrom(expiresIn) {
	return Number.isFinite(expiresIn) && expiresIn > 0 ? Date.now() + expiresIn : null
}

/**
 * Renueva el token. Devuelve el nuevo o null si la sesión ya no se puede recuperar.
 *
 * Las renovaciones simultáneas comparten el mismo request: si no, varias pantallas pidiendo
 * a la vez rotarían el refresh token en paralelo y las últimas en llegar lo usarían ya
 * consumido, cerrando una sesión que estaba perfectamente viva.
 */
function refreshSession() {
	if (!session?.refreshToken) return Promise.resolve(null)
	if (!refreshRequest) refreshRequest = startRefresh(session.refreshToken)
	return refreshRequest
}

function startRefresh(refreshToken) {
	const request = fetch('/api/auth/refresh', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ refreshToken }),
	})
		.then(async (response) => {
			const body = await response.json().catch(() => ({}))
			if (!response.ok || !body?.token) {
				throw new Error(body.error || 'No se pudo renovar la sesión')
			}

			session = {
				token: body.token,
				// Directus rota el refresh token en cada renovación; si no viniera uno
				// nuevo, el anterior sigue siendo el válido.
				refreshToken: body.refreshToken ?? refreshToken,
				expiresAt: expiryFrom(body.expiresIn),
			}

			return session.token
		})
		.catch((error) => {
			console.warn('No se pudo renovar la sesión:', error.message)
			clearSession()
			expiredListener?.()
			return null
		})

	return request.finally(() => {
		refreshRequest = null
	})
}

function withAuthorization(options, token) {
	return {
		...options,
		headers: { ...options.headers, Authorization: `Bearer ${token}` },
	}
}

/**
 * fetch contra la API con el token vigente.
 *
 * Sin sesión abierta pasa el request tal cual: los endpoints públicos (login, registro)
 * lo usan igual.
 */
export async function apiFetch(url, options = {}) {
	if (!session) return fetch(url, options)

	if (session.expiresAt !== null && session.expiresAt - Date.now() <= REFRESH_MARGIN_MS) {
		await refreshSession()
	}

	const token = session?.token
	if (!token) return fetch(url, options)

	const response = await fetch(url, withAuthorization(options, token))
	if (response.status !== 401) return response

	// El token podía estar vencido igual: un reloj desfasado, una sesión revocada o un
	// request que salió justo en el borde. Una renovación y un reintento; si tampoco sale,
	// se devuelve el 401 y quien llamó decide qué mostrar.
	const renewed = await refreshSession()
	if (!renewed) return response

	return fetch(url, withAuthorization(options, renewed))
}
