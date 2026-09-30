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
 * El refresh token no pasa por acá: vive en una cookie `HttpOnly` que pone el backend y que
 * el JavaScript no puede leer. Por eso recargar no cierra la sesión —el navegador manda la
 * cookie sola— sin dejar una credencial al alcance de un XSS. Lo único que guarda este módulo
 * es el access token, que dura 15 minutos y muere con la pestaña.
 */

// Margen para renovar antes de que venza: cubre el viaje del request y los relojes
// desfasados entre el navegador y el servidor.
const REFRESH_MARGIN_MS = 60_000

let session = null
let refreshRequest = null
let restoreRequest = null
let expiredListener = null

/** Guarda la sesión recién abierta (login o registro). */
export function setSession(user) {
	session = user?.token
		? {
			token: user.token,
			expiresAt: expiryFrom(user.expiresIn),
		}
		: null
	refreshRequest = null
}

/**
 * Cierra la sesión. Avisa al backend para que borre la cookie e invalide el refresh token en
 * Directus: sin eso seguiría sirviendo hasta vencer, aunque el usuario haya cerrado sesión.
 */
export function clearSession() {
	session = null
	refreshRequest = null
	restoreRequest = null
	// Fire and forget: si el aviso no llega, la sesión local ya está cerrada igual.
	try {
		fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' })
			.catch((error) => console.warn('No se pudo cerrar la sesión:', error.message))
	} catch (error) {
		console.warn('No se pudo cerrar la sesión:', error.message)
	}
}

/**
 * Recupera la sesión guardada al arrancar la aplicación.
 *
 * El refresh token está en la cookie, así que alcanza con intentar renovar: si el navegador
 * la tiene y sigue siendo válida, vuelve un access token y se completa el perfil con
 * `/auth/me` —la renovación devuelve el token pero no de quién es—. Si no, no hay sesión que
 * recuperar y hay que iniciar una.
 *
 * Las llamadas simultáneas comparten el mismo request, por lo mismo que `refreshSession`:
 * Directus rota el refresh token en cada uso, así que dos renovaciones en paralelo dejan a
 * la segunda usando uno ya consumido. Ese 401 además borra la cookie recién rotada, y la
 * sesión —que estaba perfectamente viva— se pierde para siempre. Pasa con sólo montar la
 * aplicación: StrictMode ejecuta el efecto de arranque dos veces.
 */
export function restoreSession() {
	if (!restoreRequest) {
		restoreRequest = attemptRestore().finally(() => {
			restoreRequest = null
		})
	}
	return restoreRequest
}

async function attemptRestore() {
	const response = await fetch('/api/auth/refresh', {
		method: 'POST',
		credentials: 'same-origin',
	}).catch(() => null)

	const body = await response?.json().catch(() => ({}))
	if (!response?.ok || !body?.token) return null

	session = {
		token: body.token,
		expiresAt: expiryFrom(body.expiresIn),
	}

	const profileResponse = await apiFetch('/api/auth/me').catch(() => null)
	const profile = await profileResponse?.json().catch(() => ({}))
	if (!profileResponse?.ok || !profile?.userId) {
		clearSessionLocally()
		return null
	}

	return { ...profile, token: body.token, expiresIn: body.expiresIn }
}

/** Olvida la sesión sin avisarle al backend: se usa cuando ya sabemos que no sirve. */
function clearSessionLocally() {
	session = null
	refreshRequest = null
	restoreRequest = null
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
	if (!session) return Promise.resolve(null)
	if (!refreshRequest) refreshRequest = startRefresh()
	return refreshRequest
}

function startRefresh() {
	// Sin cuerpo: el refresh token lo manda el navegador en la cookie.
	const request = fetch('/api/auth/refresh', {
		method: 'POST',
		credentials: 'same-origin',
	})
		.then(async (response) => {
			const body = await response.json().catch(() => ({}))
			if (!response.ok || !body?.token) {
				throw new Error(body.error || 'No se pudo renovar la sesión')
			}

			// Directus rota el refresh token en cada renovación y el backend vuelve a
			// setear la cookie, así que acá no hay nada que guardar más que el acceso.
			session = {
				token: body.token,
				expiresAt: expiryFrom(body.expiresIn),
			}

			return session.token
		})
		.catch((error) => {
			console.warn('No se pudo renovar la sesión:', error.message)
			// Local: el backend ya borró la cookie al responder 401, y avisarle de nuevo
			// sólo agregaría un request que no cambia nada.
			clearSessionLocally()
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
