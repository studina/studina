/**
 * Dibuja un .docx como HTML dentro de `container`.
 *
 * docx-preview se importa recién acá para que quede en un chunk aparte: sólo se
 * descarga la primera vez que alguien abre un DOCX. Los estilos del documento se
 * insertan en el mismo `container`, así que también sirve para una pestaña nueva.
 */
export async function renderDocx(blob, container) {
	const { renderAsync } = await import('docx-preview')
	await renderAsync(blob, container, undefined, {
		inWrapper: true,
		ignoreLastRenderedPageBreak: true,
	})
}
