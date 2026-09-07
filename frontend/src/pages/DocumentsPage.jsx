import { useState, useEffect } from 'react'
import { FILE_ICONS } from '../utils/text'
import DeleteFileModal from '../components/DeleteFileModal'
import TagEditor from '../components/TagEditor'
import './DocumentsPage.css'
import dinaEmptyDocuments from '../assets/dina/dina-empty-documents.png'
import dinaUploadDocuments from '../assets/dina/dina-upload-documents.png'
import dinaSearchingFiles from '../assets/dina/dina-searching-files.png'

const pendingFileLists = new Map()

function loadFileList(url, authorizationHeader) {
	const requestKey = `${url}:${authorizationHeader}`
	const existingRequest = pendingFileLists.get(requestKey)

	if (existingRequest) return existingRequest

	const request = fetch(url, {
		headers: { Authorization: authorizationHeader },
	})
		.then(async (response) => {
			const body = await response.json().catch(() => ({}))
			if (!response.ok) {
				throw new Error(body.error || 'No se pudieron cargar los archivos')
			}
			return body
		})
		.finally(() => {
			pendingFileLists.delete(requestKey)
		})

	pendingFileLists.set(requestKey, request)
	return request
}

function makeFileObj(filename, fileId, tags = []) {
	const lower = filename.toLowerCase()
	const type = lower.endsWith('.pdf')
		? 'pdf'
		: lower.endsWith('.txt')
			? 'txt'
			: lower.endsWith('.docx')
				? 'docx'
				: 'file'
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
	const [loading, setLoading] = useState(Boolean(user?.token))
	const [loadingExiting, setLoadingExiting] = useState(false)
	const [uploading, setUploading] = useState(false)
	const [uploadError, setUploadError] = useState('')
	const [search, setSearch] = useState('')
	const [dragging, setDragging] = useState(false)

	// Cola de archivos recién subidos esperando que el usuario les ponga tags
	const [tagQueue, setTagQueue] = useState([])
	// Archivo existente que se está editando
	const [editingFile, setEditingFile] = useState(null)
	const [filePendingDeletion, setFilePendingDeletion] = useState(null)
	// Tags activos como filtro
	const [activeTagFilters, setActiveTagFilters] = useState([])
	const authorizationHeader = user?.token
		? `Bearer ${user.token}`
		: null

	// ── Cargar archivos desde la API al montar o cambiar materia ──
	useEffect(() => {
		if (!authorizationHeader) return undefined

		let active = true
		let hideLoadingTimer
		const loadingStartedAt = Date.now()
		setLoading(true)
		setLoadingExiting(false)
		const url = `/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`

		loadFileList(url, authorizationHeader)
			.then(({ data }) => {
				if (!active) return
				if (Array.isArray(data)) {
					const loaded = data.map(({ id, filename, tags }) =>
						makeFileObj(filename, id, Array.isArray(tags) ? tags : [])
					)
					setFiles(loaded)
				}
			})
			.catch((error) => {
				if (active) console.error('Error cargando archivos:', error)
			})
			.finally(() => {
				if (!active) return

				const elapsed = Date.now() - loadingStartedAt
				const remaining = Math.max(0, 2000 - elapsed)
				hideLoadingTimer = window.setTimeout(() => {
					setLoadingExiting(true)
					hideLoadingTimer = window.setTimeout(() => {
						setLoading(false)
						setLoadingExiting(false)
					}, 250)
				}, remaining)
			})

		return () => {
			active = false
			window.clearTimeout(hideLoadingTimer)
		}
	}, [subject.id, authorizationHeader])

	// Sincronizar la lista con App fuera de los setters de estado.
	useEffect(() => {
		onFilesLoaded?.(subject.id, files)
	}, [files, onFilesLoaded, subject.id])

	// ── Guardar todos los tags asociados a un archivo ──
	const saveTags = async (fileId, tags) => {
	const res = await fetch('/api/files/tags', {
		method: 'PATCH',
			headers: {
				'Content-Type': 'application/json',
				Authorization: authorizationHeader,
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
		if (!user?.token) {
			setUploadError('Tu sesión no es válida. Volvé a iniciar sesión para subir documentos.')
			return
		}

		setUploading(true)
		setUploadError('')
		const uploaded = []
		const failed = []
		const warnings = []

		for (const f of rawFiles) {
			const formData = new FormData()
			formData.append('file', f)
			formData.append('subjectId', String(subject.id))
			try {
				const res = await fetch('/api/files/upload', {
					method: 'POST',
					headers: { Authorization: authorizationHeader },
					body: formData,
				})
				if (res.ok) {
					const { id, filename, indexingError } = await res.json()
					uploaded.push(makeFileObj(filename, id))
					if (indexingError) {
						warnings.push(
							`${filename}: ${indexingError}`
						)
					}
				} else {
					const body = await res.json().catch(() => ({}))
					const fallbackMessage =
						res.status === 401
							? 'la sesión venció o no es válida'
							: res.status === 403
								? 'no tenés permiso para subirlo'
								: res.status === 409
									? 'ya existe un archivo con ese nombre'
								: res.status >= 500
									? 'el servidor no pudo procesarlo'
									: 'no se pudo subir'
					failed.push(`${f.name}: ${body.error || fallbackMessage}`)
				}
			} catch (err) {
				console.error('Error subiendo', f.name, err)
				failed.push(`${f.name}: no se pudo conectar con el servidor`)
			}
		}

		if (uploaded.length > 0) {
			setFiles((prev) => [...prev, ...uploaded])
			setTagQueue(uploaded)
		}
		if (failed.length > 0) {
			setUploadError(`No se pudieron subir: ${failed.join(' · ')}`)
		} else if (warnings.length > 0) {
			setUploadError(`Advertencia: ${warnings.join(' · ')}`)
		}
		setUploading(false)
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
			headers: { Authorization: authorizationHeader },
		})

		if (!res.ok) {
			const errorBody = await res.json().catch(() => ({}))
			throw new Error(errorBody.error || 'No se pudo eliminar el archivo')
		}

		return res.json()
	}

	// ── Confirmar y eliminar un archivo ──
	const handleDelete = async (file) => {
		try {
			await deleteFile(file.id)

			setFiles((prev) => prev.filter((currentFile) => currentFile.id !== file.id))

			if (editingFile?.id === file.id) {
				setEditingFile(null)
			}

			setTagQueue((prev) =>
				prev.filter((queuedFile) => queuedFile.id !== file.id)
			)
			setFilePendingDeletion(null)
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
					{uploading ? 'Subiendo...' : '＋ Agregar documento'}
					<input
						type="file"
						multiple
						hidden
						disabled={uploading}
						accept=".pdf,.txt,.docx"
						onChange={handleUpload}
					/>
				</label>
			</div>

			{uploadError && (
				<div className="docs-upload-error" role="alert">
					<span>{uploadError}</span>
					<button
						type="button"
						aria-label="Cerrar error de subida"
						onClick={() => setUploadError('')}
					>
						✕
					</button>
				</div>
			)}

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

			{/* ── Contenido ── */}
			{loading ? (
				<div
					className={`docs-loading ${loadingExiting ? 'is-exiting' : ''}`}
					role="status"
				>
					<img
						src={dinaSearchingFiles}
						alt="Dina buscando tus archivos"
					/>
					<p>Buscando tus archivos...</p>
				</div>
			) : (
				<>
					<p className="docs-count">
						{`${filtered.length} ${filtered.length === 1 ? 'documento' : 'documentos'}${search ? ` para "${search}"` : ''}${activeTagFilters.length > 0 ? ` · tags: ${activeTagFilters.join(', ')}` : ''}`}
					</p>

					{filtered.length === 0 ? (
				<div className="docs-empty">
					<img
						src={files.length === 0 ? dinaUploadDocuments : dinaEmptyDocuments}
						alt="Dina organizando documentos"
					/>
					<h2>{files.length === 0 ? 'Subí tus primeros materiales' : 'No encontramos documentos'}</h2>
					<p>
						{files.length === 0
							? 'Agregá apuntes para que Dina pueda responder usando su contenido.'
							: `Probá con otra búsqueda${search ? ` para reemplazar “${search}”` : ''}.`}
					</p>
					{files.length === 0 && (
						<label className="docs-empty-upload">
							{uploading ? 'Subiendo...' : 'Subir documentos'}
							<input
								type="file"
								multiple
								hidden
								disabled={uploading}
								accept=".pdf,.txt,.docx"
								onChange={handleUpload}
							/>
						</label>
					)}
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
								onClick={(e) => { e.stopPropagation(); setFilePendingDeletion(f) }}
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
				</>
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

			{filePendingDeletion && (
				<DeleteFileModal
					file={filePendingDeletion}
					onCancel={() => setFilePendingDeletion(null)}
					onConfirm={() => handleDelete(filePendingDeletion)}
				/>
			)}
		</div>
	)
}
