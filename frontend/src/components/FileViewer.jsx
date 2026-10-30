import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'
import { FILE_ICONS } from '../utils/text'
import { apiFetch } from '../lib/session'
import { renderDocx } from '../lib/renderDocx'
import './FileViewer.css'

export default function FileViewer({ file, token, onClose }) {
	const [textContent, setTextContent] = useState(null)
	const [viewerUrl, setViewerUrl] = useState(null)
	const [docxBlob, setDocxBlob] = useState(null)
	const docxRef = useRef(null)
	const [loading, setLoading] = useState(Boolean(file.url))
	const [error, setError] = useState('')

	useEffect(() => {
		if (!file.url || !token) {
			return undefined
		}

		const controller = new AbortController()
		let objectUrl

		apiFetch(file.url, { signal: controller.signal })
			.then(async (response) => {
				if (!response.ok) {
					throw new Error(
						response.status === 403
							? 'No tenés permiso para abrir este archivo.'
							: 'No se pudo descargar el archivo.'
					)
				}
				// El .txt y el .md se muestran como texto: response.text() decodifica en
				// UTF-8 aunque el backend no mande charset, así no se rompen los acentos.
				if (file.type === 'txt' || file.type === 'md') {
					setTextContent(await response.text())
					return
				}
				// El .docx lo dibuja docx-preview en el navegador.
				if (file.type === 'docx') {
					setDocxBlob(await response.blob())
					return
				}
				objectUrl = URL.createObjectURL(await response.blob())
				setViewerUrl(objectUrl)
			})
			.catch((requestError) => {
				if (requestError.name !== 'AbortError') {
					setError(requestError.message)
				}
			})
			.finally(() => setLoading(false))

		return () => {
			controller.abort()
			if (objectUrl) URL.revokeObjectURL(objectUrl)
		}
	}, [file.type, file.url, token])

	// El contenedor recién existe cuando deja de estar `loading`; por eso depende de ambos.
	useEffect(() => {
		if (!docxBlob || loading || !docxRef.current) return
		renderDocx(docxBlob, docxRef.current).catch(() => {
			setError('No se pudo mostrar el documento DOCX.')
		})
	}, [docxBlob, loading])

	return (
		<div className="file-viewer">
			<div className="file-viewer-header">
				<span className="file-viewer-name">{FILE_ICONS[file.type]} {file.name}</span>
				<div className="file-viewer-actions">
					<button className="btn-close" onClick={onClose}>✕</button>
				</div>
			</div>
			<div className="file-viewer-body">
				{loading ? (
					<div className="file-pdf-placeholder">Cargando archivo...</div>
				) : error ? (
					<div className="file-pdf-placeholder">{error}</div>
				) : textContent !== null && file.type === 'md' ? (
					<div className="file-markdown">
						<ReactMarkdown
							remarkPlugins={[remarkGfm, remarkMath]}
							rehypePlugins={[rehypeKatex]}
						>
							{textContent}
						</ReactMarkdown>
					</div>
				) : textContent !== null ? (
					<pre className="file-text">{textContent}</pre>
				) : docxBlob ? (
					<div ref={docxRef} className="file-docx" />
				) : viewerUrl ? (
					<iframe className="file-pdf-iframe" src={viewerUrl} title={file.name} />
				) : (
					<div className="file-pdf-placeholder">
						<span>📕</span>
						<p>Vista previa no disponible.</p>
						<p className="file-pdf-name">{file.name}</p>
					</div>
				)}
			</div>
		</div>
	)
}
