import { describe, expect, it } from 'vitest'
import { fileRequestUrl, fileTypeOf } from './openFile'

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

describe('fileRequestUrl', () => {
	it('descarga el Markdown tal cual, sin pasar por la vista previa', () => {
		expect(fileRequestUrl('md-1', 'Resumen.md')).toBe('/api/files/download?fileId=md-1')
	})
})
