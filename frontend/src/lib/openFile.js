/**
 * Abre un archivo de la materia en una pestaña nueva.
 *
 * Los endpoints de archivo exigen el header Authorization, así que no alcanza con
 * un link: hay que descargar el contenido y abrirlo como blob (el header lo pone
 * apiFetch). Dos detalles hacen que esto funcione de verdad en el navegador:
 *
 * 1. La pestaña se abre de forma sincrónica, dentro del gesto del click. Un
 *    window.open posterior a un await lo bloquea el navegador como pop-up.
 * 2. El object URL no se revoca al abrirlo (rompería la pestaña). Quien llama
 *    junta los URLs devueltos y los revoca al desmontarse.
 *
 * Los .docx no se pueden mostrar con un object URL (el navegador los descarga):
 * se dibujan con docx-preview dentro de la pestaña y no se devuelve ningún URL.
 */

import { apiFetch } from './session'
import { renderDocx } from './renderDocx'

export function fileTypeOf(filename) {
	const lower = String(filename || '').toLowerCase()
	if (lower.endsWith('.pdf')) return 'pdf'
	if (lower.endsWith('.txt')) return 'txt'
	if (lower.endsWith('.docx')) return 'docx'
	if (lower.endsWith('.md') || lower.endsWith('.markdown')) return 'md'
	return 'file'
}

export async function openFileInNewTab({ fileId, filename }) {
	const tab = window.open('', '_blank')
	try {
		const response = await apiFetch(
			`/api/files/download?fileId=${encodeURIComponent(fileId)}`,
		)
		if (!response.ok) {
			throw new Error(
				response.status === 403
					? 'No tenés permiso para abrir este archivo.'
					: 'No se pudo abrir el archivo.',
			)
		}
		const blob = await response.blob()
		if (fileTypeOf(filename) === 'docx') {
			if (tab) {
				tab.document.title = filename
				await renderDocx(blob, tab.document.body)
			}
			return null
		}
		const objectUrl = URL.createObjectURL(blob)
		if (tab) tab.location = objectUrl
		return objectUrl
	} catch (error) {
		if (tab) tab.close()
		throw error
	}
}
