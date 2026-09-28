/**
 * Cola de subidas de documentos, viva fuera de React.
 *
 * El backend indexa el archivo dentro del mismo request de upload, así que
 * "Dina lo está procesando..." dura lo que dura ese fetch. Si la cola viviera en
 * el estado de DocumentsPage, salir de la sección desmontaría el componente y,
 * aunque el fetch en curso seguiría, se perderían las filas en curso y los
 * archivos que terminan mientras el usuario está en otra pantalla.
 *
 * Por eso la cola es de módulo: sobrevive al desmontaje. La página se suscribe
 * al montarse, ve lo que ya estaba en curso y vuelve a recibir los avisos de los
 * que terminen; los que terminaron mientras no estaba llegan igual, porque al
 * montarse relee la lista del backend.
 */

import { randomUuid } from './uuid'
import { fileTypeOf } from './openFile'
import { apiFetch } from './session'

// subjectId → { pending, uploading, error, failed, warnings, listeners }
const queues = new Map()

function queueFor(subjectId) {
	const key = String(subjectId)
	let queue = queues.get(key)

	if (!queue) {
		queue = {
			key,
			pending: [],
			uploading: false,
			error: '',
			failed: [],
			warnings: [],
			listeners: new Set(),
		}
		queues.set(key, queue)
	}

	return queue
}

// El snapshot se memoriza para que volver a suscribirse no devuelva un objeto
// nuevo y no provoque un render de más.
function snapshotOf(queue) {
	if (!queue.snapshot) {
		queue.snapshot = {
			pending: queue.pending,
			uploading: queue.uploading,
			error: queue.error,
		}
	}

	return queue.snapshot
}

function emit(queue) {
	queue.snapshot = null
	const snapshot = snapshotOf(queue)
	queue.listeners.forEach((listener) => listener.onChange?.(snapshot))
}

/** Estado actual de la cola de una materia, para el primer render. */
export function getUploadQueue(subjectId) {
	return snapshotOf(queueFor(subjectId))
}

/**
 * Escucha una materia. `onChange` recibe el estado de la cola; `onUploaded`, cada
 * archivo que termina de procesarse mientras haya alguien escuchando.
 */
export function subscribeToUploads(subjectId, listener) {
	const queue = queueFor(subjectId)
	queue.listeners.add(listener)
	// Quien se suscribe recibe el estado actual: al volver a Documentos las filas
	// en curso ya están ahí, sin esperar al próximo cambio de la cola.
	listener.onChange?.(snapshotOf(queue))

	return () => {
		queue.listeners.delete(listener)
		// La entrada sólo se descarta si no queda nada que recordar: una subida en
		// curso o un error todavía sin ver tienen que seguir estando al volver.
		if (
			queue.listeners.size === 0
			&& queue.pending.length === 0
			&& !queue.uploading
			&& !queue.error
		) {
			queues.delete(queue.key)
		}
	}
}

export function clearUploadError(subjectId) {
	const queue = queueFor(subjectId)
	if (!queue.error) return
	queue.error = ''
	emit(queue)
}

export function setUploadError(subjectId, message) {
	const queue = queueFor(subjectId)
	queue.error = message
	emit(queue)
}

/**
 * Agrega archivos a la cola de la materia y arranca el procesamiento si no
 * estaba corriendo. Los archivos se suben de a uno, en el orden elegido.
 */
export function enqueueUploads({ subjectId, files }) {
	const queue = queueFor(subjectId)

	// Se encolan todos de una para que el usuario vea desde el principio qué
	// eligió, aunque después se suban de a uno.
	const queued = files.map((file) => ({
		uploadId: randomUuid(),
		file,
		name: file.name,
		type: fileTypeOf(file.name),
	}))

	if (queued.length === 0) return Promise.resolve()

	queue.pending = [...queue.pending, ...queued]
	queue.error = ''

	if (queue.uploading) {
		emit(queue)
		return queue.running
	}

	queue.uploading = true
	queue.running = drain(queue).finally(() => {
		queue.running = null
	})
	emit(queue)

	return queue.running
}

async function drain(queue) {
	while (queue.pending.length > 0) {
		const current = queue.pending[0]
		// El primero de la cola es el que está en el fetch: la fila lo muestra
		// distinto de los que todavía esperan su turno.
		queue.pending = [
			{ ...current, processing: true },
			...queue.pending.slice(1),
		]
		emit(queue)

		try {
			await uploadOne(queue, current)
		} finally {
			queue.pending = queue.pending.filter(
				(item) => item.uploadId !== current.uploadId
			)
			emit(queue)
		}
	}

	queue.uploading = false
	queue.error = queue.failed.length > 0
		? `No se pudieron subir: ${queue.failed.join(' · ')}`
		: queue.warnings.length > 0
			? `Advertencia: ${queue.warnings.join(' · ')}`
			: ''
	queue.failed = []
	queue.warnings = []
	emit(queue)

	// Nadie mirando y nada que contar: la entrada ya no hace falta.
	if (queue.listeners.size === 0 && !queue.error) queues.delete(queue.key)
}

async function uploadOne(queue, queued) {
	const formData = new FormData()
	formData.append('file', queued.file)
	formData.append('subjectId', queue.key)

	try {
		const response = await apiFetch('/api/files/upload', {
			method: 'POST',
			body: formData,
		})

		if (response.ok) {
			const { id, filename, indexingError } = await response.json()
			queue.listeners.forEach((listener) =>
				listener.onUploaded?.({ id, filename })
			)
			if (indexingError) queue.warnings.push(`${filename}: ${indexingError}`)
			return
		}

		const body = await response.json().catch(() => ({}))
		queue.failed.push(
			`${queued.name}: ${body.error || fallbackMessageFor(response.status)}`
		)
	} catch (error) {
		console.error('Error subiendo', queued.name, error)
		queue.failed.push(`${queued.name}: no se pudo conectar con el servidor`)
	}
}

function fallbackMessageFor(status) {
	return status === 401
		? 'la sesión venció o no es válida'
		: status === 403
			? 'no tenés permiso para subirlo'
			: status === 409
				? 'ya existe un archivo con ese nombre'
				: status >= 500
					? 'el servidor no pudo procesarlo'
					: 'no se pudo subir'
}

/** Sólo para los tests: la cola es de módulo y sobrevive entre casos. */
export function resetUploadQueues() {
	queues.clear()
}
