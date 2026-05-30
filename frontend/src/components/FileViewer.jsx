import { useState } from 'react'
import { FILE_ICONS } from '../utils/text'
import './FileViewer.css'

export default function FileViewer({ file, onClose }) {
	const [content, setContent] = useState(file.content ?? '')
	const [edited, setEdited] = useState(false)

	const save = () => { file.content = content; setEdited(false) }

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
				) : file.url ? (
					<iframe className="file-pdf-iframe" src={file.url} title={file.name} />
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
