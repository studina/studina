import { afterEach, describe, expect, it, vi } from 'vitest'
import {
	clearGenerationError,
	enqueueGeneration,
	getGenerationQueue,
	resetGenerationQueues,
	subscribeToGenerations,
} from './generationQueue'

function deferred() {
	let resolve
	const promise = new Promise((settle) => {
		resolve = settle
	})
	return { promise, resolve }
}

function jsonResponse(body, { ok = true, status = 200 } = {}) {
	return { ok, status, json: () => Promise.resolve(body) }
}

const flashcardSet = { id: 'set-1', title: 'Unidad 3', cards: [{ id: 'card-1' }] }

describe('generationQueue', () => {
	afterEach(() => {
		vi.unstubAllGlobals()
		vi.restoreAllMocks()
		resetGenerationQueues()
	})

	it('registra la generación como pendiente en el acto', () => {
		vi.stubGlobal('fetch', vi.fn(() => deferred().promise))

		enqueueGeneration({
			kind: 'flashcards',
			subjectId: 3,
			title: 'Unidad 3',
			payload: { subjectId: '3' },
		})

		expect(getGenerationQueue('flashcards', 3).pending).toMatchObject([{ title: 'Unidad 3' }])
	})

	it('usa un título por defecto cuando el usuario no puso ninguno', () => {
		vi.stubGlobal('fetch', vi.fn(() => deferred().promise))

		enqueueGeneration({ kind: 'flashcards', subjectId: 3, title: '   ', payload: {} })
		enqueueGeneration({ kind: 'quiz', subjectId: 3, payload: {} })
		enqueueGeneration({ kind: 'trueFalse', subjectId: 3, payload: {} })
		enqueueGeneration({ kind: 'openQuestions', subjectId: 3, payload: {} })

		expect(getGenerationQueue('flashcards', 3).pending[0].title).toBe('Set nuevo')
		expect(getGenerationQueue('quiz', 3).pending[0].title).toBe('Cuestionario nuevo')
		expect(getGenerationQueue('trueFalse', 3).pending[0].title).toBe('Verdadero o falso nuevo')
		expect(getGenerationQueue('openQuestions', 3).pending[0].title).toBe('Preguntas abiertas nuevas')
	})

	it('quien se suscribe después ve lo que ya estaba en curso', () => {
		vi.stubGlobal('fetch', vi.fn(() => deferred().promise))
		enqueueGeneration({ kind: 'flashcards', subjectId: 3, title: 'Unidad 3', payload: {} })

		const onChange = vi.fn()
		subscribeToGenerations('flashcards', 3, { onChange })

		// El estado llega al suscribirse, sin esperar al próximo cambio: es lo que hace que
		// al volver a la sección la fila en curso esté desde el primer render.
		expect(onChange).toHaveBeenCalledWith(
			expect.objectContaining({ pending: [expect.objectContaining({ title: 'Unidad 3' })] }),
		)
	})

	it('sigue corriendo sin nadie escuchando y avisa a quien esté al terminar', async () => {
		const pending = deferred()
		vi.stubGlobal('fetch', vi.fn(() => pending.promise))

		const running = enqueueGeneration({
			kind: 'flashcards',
			subjectId: 3,
			title: 'Unidad 3',
			payload: {},
		})

		// Nadie suscripto: es el caso de salir de la sección con la generación a medias.
		const onGenerated = vi.fn()
		subscribeToGenerations('flashcards', 3, { onGenerated })

		pending.resolve(jsonResponse(flashcardSet))
		await running

		expect(onGenerated).toHaveBeenCalledWith(flashcardSet)
		expect(getGenerationQueue('flashcards', 3).pending).toEqual([])
	})

	it('guarda el error para mostrarlo a quien vuelva', async () => {
		vi.spyOn(console, 'error').mockImplementation(() => {})
		vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(
			jsonResponse({ error: 'Se agotó la cuota' }, { ok: false, status: 429 }),
		)))

		await enqueueGeneration({ kind: 'quiz', subjectId: 3, title: 'Parcial', payload: {} })

		// El error no se pierde con el componente: sobrevive para el próximo montaje.
		expect(getGenerationQueue('quiz', 3).error).toBe('Se agotó la cuota')
		expect(getGenerationQueue('quiz', 3).pending).toEqual([])

		clearGenerationError('quiz', 3)
		expect(getGenerationQueue('quiz', 3).error).toBe('')
	})

	it('trata una respuesta sin contenido como un error y no como un recurso vacío', async () => {
		vi.spyOn(console, 'error').mockImplementation(() => {})
		vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ cards: [] }))))

		const onGenerated = vi.fn()
		subscribeToGenerations('flashcards', 3, { onGenerated })

		await enqueueGeneration({ kind: 'flashcards', subjectId: 3, payload: {} })

		expect(onGenerated).not.toHaveBeenCalled()
		expect(getGenerationQueue('flashcards', 3).error).toMatch(/no devolvió flashcards/)
	})

	it('separa las generaciones por tipo y por materia', () => {
		vi.stubGlobal('fetch', vi.fn(() => deferred().promise))

		enqueueGeneration({ kind: 'flashcards', subjectId: 3, title: 'De Redes', payload: {} })
		enqueueGeneration({ kind: 'quiz', subjectId: 7, title: 'De Sistemas', payload: {} })

		expect(getGenerationQueue('flashcards', 3).pending).toHaveLength(1)
		expect(getGenerationQueue('quiz', 3).pending).toHaveLength(0)
		expect(getGenerationQueue('flashcards', 7).pending).toHaveLength(0)
		expect(getGenerationQueue('quiz', 7).pending).toHaveLength(1)
	})

	it('deja de avisar a quien se desuscribió', async () => {
		const pending = deferred()
		vi.stubGlobal('fetch', vi.fn(() => pending.promise))

		const onGenerated = vi.fn()
		const unsubscribe = subscribeToGenerations('flashcards', 3, { onGenerated })
		const running = enqueueGeneration({ kind: 'flashcards', subjectId: 3, payload: {} })

		unsubscribe()
		pending.resolve(jsonResponse(flashcardSet))
		await running

		expect(onGenerated).not.toHaveBeenCalled()
	})

	it('rechaza un tipo de generación que no existe', () => {
		expect(() => enqueueGeneration({ kind: 'podcast', subjectId: 3, payload: {} }))
			.toThrow(/desconocido/)
	})

	it('un verdadero o falso sin enunciados cuenta como error', async () => {
		vi.spyOn(console, 'error').mockImplementation(() => {})
		const fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ id: 'tf-1', statements: [] })))
		vi.stubGlobal('fetch', fetchMock)

		await enqueueGeneration({ kind: 'trueFalse', subjectId: 3, payload: { subjectId: '3' } })

		expect(fetchMock.mock.calls[0][0]).toBe('/api/ai/files/true-false')
		expect(getGenerationQueue('trueFalse', 3).error).toBe('La IA no devolvió enunciados válidos')
	})

	it('un set de preguntas abiertas sin preguntas cuenta como error', async () => {
		vi.spyOn(console, 'error').mockImplementation(() => {})
		const fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ id: 'oq-1', questions: [] })))
		vi.stubGlobal('fetch', fetchMock)

		await enqueueGeneration({ kind: 'openQuestions', subjectId: 3, payload: { subjectId: '3' } })

		expect(fetchMock.mock.calls[0][0]).toBe('/api/ai/files/open-questions')
		expect(getGenerationQueue('openQuestions', 3).error).toBe('La IA no devolvió preguntas abiertas válidas')
	})
})
