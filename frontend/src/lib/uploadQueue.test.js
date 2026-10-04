import { afterEach, describe, expect, it, vi } from 'vitest'
import { enqueueUploads, getUploadQueue, resetUploadQueues } from './uploadQueue'
import { setSession } from './session'

/** Lanza un beforeunload y contesta si el navegador preguntaría antes de salir. */
function wouldWarnOnUnload() {
	const event = new Event('beforeunload', { cancelable: true })
	window.dispatchEvent(event)
	return event.defaultPrevented
}

function fileNamed(name) {
	return new File(['contenido'], name, { type: 'application/pdf' })
}

describe('uploadQueue', () => {
	afterEach(() => {
		vi.unstubAllGlobals()
		resetUploadQueues()
	})

	it('avisa antes de recargar mientras hay una subida en curso', async () => {
		// Recargar aborta el fetch y la cola vive en memoria: lo único que evita perder la
		// subida es preguntar antes, porque después ya no hay nada que recuperar.
		let finishUpload
		const uploading = new Promise((resolve) => {
			finishUpload = resolve
		})
		vi.stubGlobal('fetch', vi.fn(() => uploading))
		setSession({ token: 'token-a' })

		expect(wouldWarnOnUnload()).toBe(false)

		const running = enqueueUploads({ subjectId: 3, files: [fileNamed('Apunte.pdf')] })
		expect(wouldWarnOnUnload()).toBe(true)

		finishUpload({
			ok: true,
			status: 200,
			json: () => Promise.resolve({ id: 'file-a', filename: 'Apunte.pdf' }),
		})
		await running

		// Terminada la subida el aviso se va solo: molestar sin motivo sería peor.
		expect(wouldWarnOnUnload()).toBe(false)
	})

	it('no avisa cuando no hay nada subiendo', () => {
		expect(getUploadQueue(3).pending).toEqual([])
		expect(wouldWarnOnUnload()).toBe(false)
	})
})
