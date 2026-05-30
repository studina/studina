import { useState } from 'react'
import { FILE_ICONS } from '../utils/text'

export default function DocumentsPage({ subject, filesData, onBack, onOpenFile }) {
	const [search, setSearch] = useState('')
	const [dragging, setDragging] = useState(false)
	const [files, setFiles] = useState(filesData)

	const filtered = files.filter((f) =>
		f.name.toLowerCase().includes(search.toLowerCase())
	)

	const handleUpload = (e) => {
		const newFiles = Array.from(e.target.files).map((f, i) => ({
			id: `upload-${Date.now()}-${i}`,
			name: f.name,
			type: f.name.endsWith('.pdf') ? 'pdf' : 'txt',
			content: null,
			url: URL.createObjectURL(f),
		}))
		setFiles((prev) => [...prev, ...newFiles])
	}

	const handleDrop = (e) => {
		e.preventDefault()
		setDragging(false)
		const newFiles = Array.from(e.dataTransfer.files).map((f, i) => ({
			id: `drop-${Date.now()}-${i}`,
			name: f.name,
			type: f.name.endsWith('.pdf') ? 'pdf' : 'txt',
			content: null,
			url: URL.createObjectURL(f),
		}))
		setFiles((prev) => [...prev, ...newFiles])
	}

	return (
		<div className="docs-page">
			<div className="docs-header">
				<button className="btn-back" onClick={onBack}>← Volver</button>
				<div>
					<h1 className="docs-title">Documentos</h1>
					<p className="docs-sub">{subject.name}</p>
				</div>
			</div>

			<div className="docs-toolbar">
				<div className="search-box">
					<span className="search-icon">🔍</span>
					<input
						className="search-input"
						type="text"
						placeholder="Buscar documentos..."
						value={search}
						onChange={(e) => setSearch(e.target.value)}
					/>
					{search && (
						<button className="search-clear" onClick={() => setSearch('')}>✕</button>
					)}
				</div>
				<label
					className={`btn-upload-doc ${dragging ? 'dragging' : ''}`}
					onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
					onDragLeave={() => setDragging(false)}
					onDrop={handleDrop}
				>
					＋ Agregar documento
					<input type="file" multiple hidden accept=".pdf,.txt,.docx" onChange={handleUpload} />
				</label>
			</div>

			<p className="docs-count">
				{filtered.length} {filtered.length === 1 ? 'documento' : 'documentos'}
				{search && ` para "${search}"`}
			</p>

			{filtered.length === 0 ? (
				<div className="docs-empty">
					<span>📂</span>
					<p>No se encontraron documentos{search ? ` para "${search}"` : ''}.</p>
				</div>
			) : (
				<ul className="file-list">
					{filtered.map((f) => (
						<li key={f.id} className="file-item" onClick={() => onOpenFile(f)}>
							<span className="file-item-icon">{FILE_ICONS[f.type] ?? '📄'}</span>
							<span className="file-item-name">{f.name}</span>
							{f.type === 'txt' && <span className="file-item-badge">editable</span>}
							<span className="file-item-arrow">→</span>
						</li>
					))}
				</ul>
			)}
		</div>
	)
}
