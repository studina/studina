import { useEffect, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'
import { FILE_ICONS } from '../utils/text'
import { apiFetch } from '../lib/session'
import './FileViewer.css'

export default function FileViewer({ file, token, onClose }) {
	const [textContent, setTextContent] = useState(null)
	const [viewerUrl, setViewerUrl] = useState(null)
	const [loading, setLoading] = useState(Boolean(file.url))
	const [error, setError] = useState('')

	useEffect(() => {
		if (!file.url || !token) {
			return undefined
		}

		const controller = new AbortController()
		let objectUrl

		const requestUrl = file.type === 'docx'
			? `/api/files/preview?fileId=${encodeURIComponent(file.id)}`
			: file.url

		apiFetch(requestUrl, { signal: controller.signal })
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
	}, [file.id, file.type, file.url, token])

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
