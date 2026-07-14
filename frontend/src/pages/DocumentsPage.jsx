import { useState, useEffect } from 'react'
import { FILE_ICONS } from '../utils/text'
import TagEditor from '../components/TagEditor'
import './DocumentsPage.css'

function makeFileObj(filename, fileId, tags = []) {
	const lower = filename.toLowerCase()
	const type = lower.endsWith('.pdf') ? 'pdf' : lower.endsWith('.txt') ? 'txt' : 'file'
	return {
		id: fileId,
		name: filename,
		type,
		content: null,
		url: `/api/files/download?fileId=${encodeURIComponent(fileId)}`,
		tags: tags,
	}
}

export default function DocumentsPage({ subject, user, onFilesLoaded, onBack, onOpenFile }) {
	const [files, setFiles] = useState([])
	const [loading, setLoading] = useState(true)
	const [search, setSearch] = useState('')
	const [dragging, setDragging] = useState(false)

	// Cola de archivos recién subidos esperando que el usuario les ponga tags
	const [tagQueue, setTagQueue] = useState([])
	// Archivo existente que se está editando
	const [editingFile, setEditingFile] = useState(null)
	// Tags activos como filtro
	const [activeTagFilters, setActiveTagFilters] = useState([])

	// ── Cargar archivos desde la API al montar o cambiar materia ──
	useEffect(() => {
		setFiles([])
		setSearch('')
		setActiveTagFilters([])
		setLoading(true)
		if (!user?.userId) { setLoading(false); return }

		fetch(`/api/files/list?userId=${encodeURIComponent(user.userId)}&subjectId=${encodeURIComponent(String(subject.id))}`)
			.then((r) => r.json())
			.then(({ data }) => {
			if (Array.isArray(data)) {
					const loaded = data.map(({ id, filename, tags }) =>
						makeFileObj(filename, id, Array.isArray(tags) ? tags : [])
					)
					setFiles(loaded)
					onFilesLoaded?.(subject.id, loaded)
				}
			})
			.catch((err) => console.error('Error cargando archivos:', err))
			.finally(() => setLoading(false))
	}, [subject.id, user?.userId])

	// ── Guardar todos los tags asociados a un archivo ──
	const saveTags = async (fileId, tags) => {
	const res = await fetch('/api/files/tags', {
		method: 'PATCH',
		headers: {
			'Content-Type': 'application/json',
		},
		body: JSON.stringify({
			fileId,
			tags,
		}),
	})

	if (!res.ok) {
		const errorBody = await res.json().catch(() => ({}))
		throw new Error(errorBody.error || 'No se pudieron guardar los tags')
	}

	return res.json()
}

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
	const uploadFiles = async (rawFiles) => {
		if (!user?.userId) {
			console.log("el user es:", user)
			return
		} 
		const uploaded = []
		for (const f of rawFiles) {
			const formData = new FormData()
			formData.append('file', f)
			formData.append('userId', user.userId)
			formData.append('subjectId', String(subject.id))
			try {
				const res = await fetch('/api/files/upload', { method: 'POST', body: formData })
				if (res.ok) {
					const { id, filename } = await res.json()
					uploaded.push(makeFileObj(filename, id))
				} else {
					console.error('Error subiendo', f.name, res.status)
				}
			} catch (err) {
				console.error('Error subiendo', f.name, err)
			}
		}
		if (uploaded.length > 0) {
			setFiles((prev) => {
				const next = [...prev, ...uploaded]
				onFilesLoaded?.(subject.id, next)
				return next
			})
			setTagQueue(uploaded)
		}
	}

	const handleUpload = (e) => {
		uploadFiles(Array.from(e.target.files))
	}

	const handleDrop = (e) => {
		e.preventDefault()
		setDragging(false)
		uploadFiles(Array.from(e.dataTransfer.files))
	}

	// ── Guardar tags desde la cola (upload) ──
	const handleQueueSave = async (tags) => {
		const current = tagQueue[0]

		try {
			const result = await saveTags(current.id, tags)
			const savedTags = result.tags ?? tags

			setFiles((prev) =>
				prev.map((f) =>
					f.id === current.id ? { ...f, tags: savedTags }: f
				)
			)

			setTagQueue((prev) => prev.slice(1))
		} catch (err) {
			console.error('Error guardando tags:', err)
			alert('No se pudieron guardar los tags')
		}
	}

	// ── Guardar tags de archivo existente ──
	const handleEditSave = async (tags) => {
		if (!editingFile) return

		try {
			const result = await saveTags(editingFile.id, tags)
			const savedTags = result.tags ?? tags

			setFiles((prev) =>
				prev.map((f) =>
					f.id === editingFile.id ? { ...f, tags: savedTags } : f
				)
			)

			setEditingFile(null)
		} catch (err) {
			console.error('Error guardando tags:', err)
			alert('No se pudieron guardar los tags')
		}
	}

	// ── Toggle filtro de tag ──
	const toggleTagFilter = (tag) => {
		setActiveTagFilters((prev) =>
			prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]
		)
	}

	// ── Eliminar un archivo del backend ──
	const deleteFile = async (fileId) => {
		const res = await fetch(`/api/files/${encodeURIComponent(fileId)}`, {
			method: 'DELETE',
		})

		if (!res.ok) {
			const errorBody = await res.json().catch(() => ({}))
			throw new Error(errorBody.error || 'No se pudo eliminar el archivo')
		}

		return res.json()
	}

	// ── Confirmar y eliminar un archivo ──
	const handleDelete = async (file) => {
		const confirmed = window.confirm(
			`¿Querés eliminar el archivo "${file.name}"?`
		)

		if (!confirmed) return

		try {
			await deleteFile(file.id)

			setFiles((prev) => {
				const next = prev.filter((f) => f.id !== file.id)
				onFilesLoaded?.(subject.id, next)
				return next
			})

			if (editingFile?.id === file.id) {
				setEditingFile(null)
			}

			setTagQueue((prev) =>
				// Nueva lista que contiene a todos los archivos excepto el eliminado
				prev.filter((queuedFile) => queuedFile.id !== file.id) 
			)
		} catch (err) {
			console.error('Error eliminando archivo:', err)
			alert('No se pudo eliminar el archivo')
		}
	}

	// ── Solicitar al backend sugerencias de tags generadas con IA ──
	const suggestTagsWithAI = async (filename) => {
		const params = new URLSearchParams({ filename })

		const res = await fetch(`/api/ai/suggest-tags?${params.toString()}`)

		if (!res.ok) {
			const errorBody = await res.json().catch(() => ({}))
			throw new Error( errorBody.error || 'No se pudieron generar tags')
		}

		return res.json()
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
				{loading
					? 'Cargando...'
					: `${filtered.length} ${filtered.length === 1 ? 'documento' : 'documentos'}${search ? ` para "${search}"` : ''}${activeTagFilters.length > 0 ? ` · tags: ${activeTagFilters.join(', ')}` : ''}`
				}
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

							<button
								className="btn-delete-file"
								title="Eliminar archivo"
								onClick={(e) => { e.stopPropagation(); handleDelete(f) }}
							>
								🗑️
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
					onSuggestTags={suggestTagsWithAI}
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
					onSuggestTags={suggestTagsWithAI}
					onSave={handleEditSave}
					onCancel={() => setEditingFile(null)}
				/>
			)}
		</div>
	)
}
