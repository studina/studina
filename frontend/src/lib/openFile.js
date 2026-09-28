/**
 * Abre un archivo de la materia en una pestaña nueva.
 *
 * Los endpoints de archivo exigen el header Authorization, así que no alcanza con
 * un link: hay que descargar el contenido y abrirlo como blob. Dos detalles hacen
 * que esto funcione de verdad en el navegador:
 *
 * 1. La pestaña se abre de forma sincrónica, dentro del gesto del click. Un
 *    window.open posterior a un await lo bloquea el navegador como pop-up.
 * 2. El object URL no se revoca al abrirlo (rompería la pestaña). Quien llama
 *    junta los URLs devueltos y los revoca al desmontarse.
 */

export function fileTypeOf(filename) {
	const lower = String(filename || '').toLowerCase()
	if (lower.endsWith('.pdf')) return 'pdf'
	if (lower.endsWith('.txt')) return 'txt'
	if (lower.endsWith('.docx')) return 'docx'
	return 'file'
}

/** Los .docx se convierten a PDF en el backend; el resto se sirve tal cual. */
export function fileRequestUrl(fileId, filename) {
	const id = encodeURIComponent(fileId)
	return fileTypeOf(filename) === 'docx'
		? `/api/files/preview?fileId=${id}`
		: `/api/files/download?fileId=${id}`
}

export async function openFileInNewTab({ fileId, filename, token }) {
	const tab = window.open('', '_blank')
	try {
		const response = await fetch(fileRequestUrl(fileId, filename), {
			headers: { Authorization: `Bearer ${token}` },
		})
		if (!response.ok) {
			throw new Error(
				response.status === 403
					? 'No tenés permiso para abrir este archivo.'
					: 'No se pudo abrir el archivo.',
			)
		}
		const objectUrl = URL.createObjectURL(await response.blob())
		if (tab) tab.location = objectUrl
		return objectUrl
	} catch (error) {
		if (tab) tab.close()
		throw error
	}
}
