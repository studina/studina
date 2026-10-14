/**
 * Generaciones de flashcards, cuestionarios y verdadero o falso en curso, vivas fuera de React.
 *
 * Generar un recurso es una llamada a la IA que puede tardar bastante. Mientras el estado
 * vivía en FlashcardsPage/QuizPage, salir de la sección desmontaba el componente y la fila
 * "Dina está preparando..." desaparecía: el fetch seguía corriendo, pero su resultado se
 * aplicaba a un componente muerto, así que al volver no había ni recurso ni señal de que
 * algo estuviera pasando.
 *
 * Por eso el registro es de módulo, como la cola de subidas: sobrevive al desmontaje. La
 * página se suscribe al montarse, ve lo que ya estaba en curso y recibe los avisos de lo que
 * termine. Lo que terminó mientras no había nadie mirando aparece igual, porque al montarse
 * la página relee la lista del backend.
 *
 * A diferencia de las subidas, cada generación arranca su propio fetch en el acto en lugar
 * de esperar turno: son pedidos independientes y hacer cola sólo agregaría espera.
 */

import { randomUuid } from './uuid'
import { apiFetch } from './session'

/** Qué endpoint pega cada tipo y cómo se valida lo que vuelve. */
const kinds = {
	flashcards: {
		endpoint: '/api/ai/files/flashcards',
		fallbackTitle: 'Set nuevo',
		errorMessage: 'No se pudieron generar las flashcards',
		emptyMessage: 'La IA no devolvió flashcards para los documentos seleccionados',
		isEmpty: (body) => !Array.isArray(body.cards) || body.cards.length === 0,
	},
	quiz: {
		endpoint: '/api/ai/files/quizzes',
		fallbackTitle: 'Cuestionario nuevo',
		errorMessage: 'No se pudo generar el cuestionario',
		emptyMessage: 'La IA no devolvió preguntas válidas',
		isEmpty: (body) => !Array.isArray(body.questions) || body.questions.length === 0,
	},
	trueFalse: {
		endpoint: '/api/ai/files/true-false',
		fallbackTitle: 'Verdadero o falso nuevo',
		errorMessage: 'No se pudo generar el verdadero o falso',
		emptyMessage: 'La IA no devolvió enunciados válidos',
		isEmpty: (body) => !Array.isArray(body.statements) || body.statements.length === 0,
	},
}

// `${kind}:${subjectId}` → { pending, error, listeners }
const queues = new Map()

function keyOf(kind, subjectId) {
	return `${kind}:${subjectId}`
}

function queueFor(kind, subjectId) {
	const key = keyOf(kind, subjectId)
	let queue = queues.get(key)

	if (!queue) {
		queue = { key, kind, subjectId: String(subjectId), pending: [], error: '', listeners: new Set() }
		queues.set(key, queue)
	}

	return queue
}

// El snapshot se memoriza para que volver a suscribirse no devuelva un objeto nuevo y no
// provoque un render de más.
function snapshotOf(queue) {
	if (!queue.snapshot) {
		queue.snapshot = { pending: queue.pending, error: queue.error }
	}

	return queue.snapshot
}

function emit(queue) {
	queue.snapshot = null
	const snapshot = snapshotOf(queue)
	queue.listeners.forEach((listener) => listener.onChange?.(snapshot))
}

/** Se descarta la entrada sólo si no queda nada que recordar. */
function forgetIfIdle(queue) {
	if (queue.listeners.size === 0 && queue.pending.length === 0 && !queue.error) {
		queues.delete(queue.key)
	}
}

/** Estado actual de las generaciones de una materia, para el primer render. */
export function getGenerationQueue(kind, subjectId) {
	return snapshotOf(queueFor(kind, subjectId))
}

/**
 * Escucha las generaciones de un tipo y una materia. `onChange` recibe el estado;
 * `onGenerated`, cada recurso que termina mientras haya alguien escuchando.
 */
export function subscribeToGenerations(kind, subjectId, listener) {
	const queue = queueFor(kind, subjectId)
	queue.listeners.add(listener)
	// Quien se suscribe recibe el estado actual: al volver a la sección las filas en curso
	// ya están ahí, sin esperar al próximo cambio.
	listener.onChange?.(snapshotOf(queue))

	return () => {
		queue.listeners.delete(listener)
		forgetIfIdle(queue)
	}
}

export function clearGenerationError(kind, subjectId) {
	const queue = queueFor(kind, subjectId)
	if (!queue.error) return
	queue.error = ''
	emit(queue)
	forgetIfIdle(queue)
}

/**
 * Arranca una generación y la registra como pendiente. Devuelve la promesa por si quien
 * llama quiere esperarla, pero no hace falta: el registro sigue vivo sin ella.
 */
export function enqueueGeneration({ kind, subjectId, title, payload }) {
	const spec = kinds[kind]
	if (!spec) throw new Error(`Tipo de generación desconocido: ${kind}`)

	const queue = queueFor(kind, subjectId)
	const pending = { id: randomUuid(), title: title?.trim() || spec.fallbackTitle }

	queue.pending = [pending, ...queue.pending]
	queue.error = ''
	emit(queue)

	const running = generateOne(queue, spec, pending, payload)
	return running
}

async function generateOne(queue, spec, pending, payload) {
	try {
		const response = await apiFetch(spec.endpoint, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(payload),
		})
		const body = await response.json().catch(() => ({}))
		if (!response.ok) throw new Error(body.error || spec.errorMessage)
		if (spec.isEmpty(body)) throw new Error(spec.emptyMessage)

		// Aparece en la lista listo para abrir; no se abre solo, porque el usuario puede
		// estar en cualquier otra pantalla.
		queue.listeners.forEach((listener) => listener.onGenerated?.(body))
	} catch (error) {
		console.error(`Error generando (${queue.kind}):`, error)
		// El error se guarda en el registro y no sólo se muestra: si falló mientras el
		// usuario estaba en otra pantalla, tiene que enterarse al volver.
		queue.error = error.message
	} finally {
		queue.pending = queue.pending.filter((item) => item.id !== pending.id)
		emit(queue)
		forgetIfIdle(queue)
	}
}

/** Sólo para los tests: el registro es de módulo y sobrevive entre casos. */
export function resetGenerationQueues() {
	queues.clear()
}
