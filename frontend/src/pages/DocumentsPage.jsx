import { useState } from 'react'
import { FILE_ICONS } from '../utils/text'
import TagEditor from '../components/TagEditor'
import './DocumentsPage.css'

export default function DocumentsPage({ subject, filesData, onBack, onOpenFile }) {
	const [search, setSearch] = useState('')
	const [dragging, setDragging] = useState(false)
	const [files, setFiles] = useState(filesData.map((f) => ({ ...f, tags: f.tags ?? [] })))

	// Cola de archivos recién subidos esperando que el usuario les ponga tags
	const [tagQueue, setTagQueue] = useState([])
	// Archivo existente que se está editando
	const [editingFile, setEditingFile] = useState(null)
	// Tags activos como filtro
	const [activeTagFilters, setActiveTagFilters] = useState([])

	// ── Todos los tags únicos usados en esta materia ──
	const allTags = [...new Set(files.flatMap((f) => f.tags))].sort()

	// ── Filtrado: texto + tags ──
	const filtered = files.filter((f) => {
		const matchesSearch = f.name.toLowerCase().includes(search.toLowerCase())
		const matchesTags =
			activeTagFilters.length === 0 ||
			activeTagFilters.every((t) => f.tags.includes(t))
		return matchesSearch && matchesTags
	})

	// ── Upload ──
	const handleUpload = (e) => {
		const newFiles = Array.from(e.target.files).map((f, i) => ({
			id: `upload-${Date.now()}-${i}`,
			name: f.name,
			type: f.name.endsWith('.pdf') ? 'pdf' : 'txt',
			content: null,
			url: URL.createObjectURL(f),
			tags: [],
		}))
		setFiles((prev) => [...prev, ...newFiles])
		setTagQueue(newFiles) // abrir modal para el primero
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
			tags: [],
		}))
		setFiles((prev) => [...prev, ...newFiles])
		setTagQueue(newFiles)
	}

	// ── Guardar tags desde la cola (upload) ──
	const handleQueueSave = (tags) => {
		const current = tagQueue[0]
		setFiles((prev) =>
			prev.map((f) => (f.id === current.id ? { ...f, tags } : f))
		)
		const remaining = tagQueue.slice(1)
		setTagQueue(remaining) // avanza al siguiente o cierra
	}

	// ── Guardar tags de archivo existente ──
	const handleEditSave = (tags) => {
		setFiles((prev) =>
			prev.map((f) => (f.id === editingFile.id ? { ...f, tags } : f))
		)
		setEditingFile(null)
	}

	// ── Toggle filtro de tag ──
	const toggleTagFilter = (tag) => {
		setActiveTagFilters((prev) =>
			prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]
		)
	}

	return (
		<div className="docs-page">
			{/* ── Header ── */}
			<div className="docs-header">
				<button className="btn-back" onClick={onBack}>← Volver</button>
				<div>
					<h1 className="docs-title">Documentos</h1>
					<p className="docs-sub">{subject.name}</p>
				</div>
			</div>

			{/* ── Toolbar: buscador + subir ── */}
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

			{/* ── Filtros por tag ── */}
			{allTags.length > 0 && (
				<div className="tag-filters">
					<span className="tag-filters-label">Filtrar por tag:</span>
					<div className="tag-filters-list">
						{allTags.map((tag) => (
							<button
								key={tag}
								className={`tag tag--filter ${activeTagFilters.includes(tag) ? 'active' : ''}`}
								onClick={() => toggleTagFilter(tag)}
							>
								{tag}
							</button>
						))}
						{activeTagFilters.length > 0 && (
							<button className="tag-filters-clear" onClick={() => setActiveTagFilters([])}>
								Limpiar filtros
							</button>
						)}
					</div>
				</div>
			)}

			{/* ── Conteo ── */}
			<p className="docs-count">
				{filtered.length} {filtered.length === 1 ? 'documento' : 'documentos'}
				{search && ` para "${search}"`}
				{activeTagFilters.length > 0 && ` · tags: ${activeTagFilters.join(', ')}`}
			</p>

			{/* ── Lista ── */}
			{filtered.length === 0 ? (
				<div className="docs-empty">
					<span>📂</span>
					<p>No se encontraron documentos{search ? ` para "${search}"` : ''}.</p>
				</div>
			) : (
				<ul className="file-list">
					{filtered.map((f) => (
						<li key={f.id} className="file-item">
							<span
								className="file-item-icon"
								onClick={() => onOpenFile(f)}
							>
								{FILE_ICONS[f.type] ?? '📄'}
							</span>

							<div className="file-item-info" onClick={() => onOpenFile(f)}>
								<span className="file-item-name">{f.name}</span>
								{f.tags.length > 0 && (
									<div className="file-item-tags">
										{f.tags.map((t) => (
											<span key={t} className="tag">{t}</span>
										))}
									</div>
								)}
							</div>

							{f.type === 'txt' && (
								<span className="file-item-badge">editable</span>
							)}

							<button
								className="btn-edit-tags"
								title="Editar tags"
								onClick={(e) => { e.stopPropagation(); setEditingFile(f) }}
							>
								🏷️
							</button>

							<span
								className="file-item-arrow"
								onClick={() => onOpenFile(f)}
							>
								→
							</span>
						</li>
					))}
				</ul>
			)}

			{/* ── Modal: tags al subir (cola) ── */}
			{tagQueue.length > 0 && (
				<TagEditor
					fileName={tagQueue[0].name}
					initialTags={tagQueue[0].tags}
					suggestions={allTags}
					onSave={handleQueueSave}
					onCancel={() => setTagQueue(tagQueue.slice(1))}
				/>
			)}

			{/* ── Modal: editar tags de archivo existente ── */}
			{editingFile && (
				<TagEditor
					fileName={editingFile.name}
					initialTags={editingFile.tags}
					suggestions={allTags}
					onSave={handleEditSave}
					onCancel={() => setEditingFile(null)}
				/>
			)}
		</div>
	)
}
