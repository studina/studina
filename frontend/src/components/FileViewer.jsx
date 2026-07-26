import { useEffect, useState } from 'react'
import { FILE_ICONS } from '../utils/text'
import './FileViewer.css'

export default function FileViewer({ file, token, onClose, onSave }) {
	const [content, setContent] = useState(file.content ?? '')
	const [edited, setEdited] = useState(false)
	const [viewerUrl, setViewerUrl] = useState(null)
	const [loading, setLoading] = useState(Boolean(file.url))
	const [error, setError] = useState('')

	useEffect(() => {
		if (!file.url || !token) {
			return undefined
		}

		const controller = new AbortController()
		let objectUrl

		fetch(file.url, {
			headers: { Authorization: `Bearer ${token}` },
			signal: controller.signal,
		})
			.then((response) => {
				if (!response.ok) {
					throw new Error(
						response.status === 403
							? 'No tenés permiso para abrir este archivo.'
							: 'No se pudo descargar el archivo.'
					)
				}
				return response.blob()
			})
			.then((blob) => {
				objectUrl = URL.createObjectURL(blob)
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
	}, [file.url, token])

	const save = () => {
		onSave?.(content)
		setEdited(false)
	}

	return (
		<div className="file-viewer">
			<div className="file-viewer-header">
				<span className="file-viewer-name">{FILE_ICONS[file.type]} {file.name}</span>
				<div className="file-viewer-actions">
					{file.type === 'txt' && edited && (
						<button className="btn-save" onClick={save}>Guardar</button>
					)}
					<button className="btn-close" onClick={onClose}>✕</button>
				</div>
			</div>
			<div className="file-viewer-body">
				{file.type === 'txt' ? (
					<textarea
						className="file-editor"
						value={content}
						onChange={(e) => { setContent(e.target.value); setEdited(true) }}
						spellCheck={false}
					/>
					) : loading ? (
						<div className="file-pdf-placeholder">Cargando archivo...</div>
					) : error ? (
						<div className="file-pdf-placeholder">{error}</div>
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
