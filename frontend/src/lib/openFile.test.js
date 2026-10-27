import { afterEach, describe, expect, it, vi } from 'vitest'
import { fileTypeOf, openFileInNewTab } from './openFile'
import { renderDocx } from './renderDocx'
import { setSession } from './session'

vi.mock('./renderDocx', () => ({ renderDocx: vi.fn(() => Promise.resolve()) }))

describe('fileTypeOf', () => {
	it('reconoce los formatos que se pueden subir', () => {
		expect(fileTypeOf('Apunte.PDF')).toBe('pdf')
		expect(fileTypeOf('Notas.txt')).toBe('txt')
		expect(fileTypeOf('Practica.docx')).toBe('docx')
		expect(fileTypeOf('Resumen.md')).toBe('md')
		expect(fileTypeOf('Resumen.markdown')).toBe('md')
		expect(fileTypeOf('foto.png')).toBe('file')
	})
})

describe('openFileInNewTab', () => {
	afterEach(() => {
		vi.unstubAllGlobals()
	})

	it('dibuja el DOCX dentro de la pestaña nueva en vez de abrir un object URL', async () => {
		const docxBlob = new Blob(['docx'])
		const fetchMock = vi.fn(() => Promise.resolve({
			ok: true,
			blob: () => Promise.resolve(docxBlob),
		}))
		vi.stubGlobal('fetch', fetchMock)
		setSession({ token: 'token-a' })
		const tab = { document: { title: '', body: {} }, close: vi.fn() }
		vi.stubGlobal('open', vi.fn(() => tab))

		const objectUrl = await openFileInNewTab({ fileId: 'docx-1', filename: 'Practica.docx' })

		expect(objectUrl).toBeNull()
		expect(fetchMock).toHaveBeenCalledWith(
			'/api/files/download?fileId=docx-1',
			expect.anything(),
		)
		expect(renderDocx).toHaveBeenCalledWith(docxBlob, tab.document.body)
		expect(tab.document.title).toBe('Practica.docx')
		expect(tab.close).not.toHaveBeenCalled()
	})
})
