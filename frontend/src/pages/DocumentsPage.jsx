import { useCallback, useEffect, useState } from 'react'
import { FILE_ICONS } from '../utils/text'
import { fileTypeOf } from '../lib/openFile'
import { apiFetch } from '../lib/session'
import {
	clearUploadError,
	enqueueUploads,
	getUploadQueue,
	setUploadError,
	subscribeToUploads,
} from '../lib/uploadQueue'
import DeleteFileModal from '../components/DeleteFileModal'
import TagEditor from '../components/TagEditor'
import '../styles/library.css'
import './DocumentsPage.css'
import dinaEmptyDocuments from '../assets/dina/dina-empty-documents.png'
import dinaUploadDocuments from '../assets/dina/dina-upload-documents.png'
import dinaSearchingFiles from '../assets/dina/dina-searching-files.png'

const pendingFileLists = new Map()

function loadFileList(url, sessionKey) {
	const requestKey = `${url}:${sessionKey}`
	const existingRequest = pendingFileLists.get(requestKey)

	if (existingRequest) return existingRequest

	const request = apiFetch(url)
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
	return {
		id: fileId,
		name: filename,
		type: fileTypeOf(filename),
		url: `/api/files/download?fileId=${encodeURIComponent(fileId)}`,
		tags: tags,
	}
}

export default function DocumentsPage({ subject, user, onFilesLoaded, onBack, onOpenFile }) {
	const [files, setFiles] = useState([])
	const [loading, setLoading] = useState(Boolean(user?.token))
	// Los archivos elegidos ocupan su lugar en la lista mientras se procesan,
	// uno por uno: son los mismos de la selección, en el mismo orden. La cola vive
	// en un módulo aparte para que salir de la sección no corte el procesamiento.
	const [uploadQueue, setUploadQueue] = useState(() => getUploadQueue(subject.id))
	const { pending: pendingUploads, uploading, error: uploadError } = uploadQueue
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
	const loadFiles = useCallback(({ showLoader = false } = {}) => {
		if (!authorizationHeader) return undefined

		let active = true
		const url = `/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`

		loadFileList(url, authorizationHeader)
			.then(({ data }) => {
				if (!active) return
				if (Array.isArray(data)) {
					const loaded = data.map(({ id, filename, tags }) =>
						makeFileObj(filename, id, Array.isArray(tags) ? tags : [])
					)
					// Una subida puede terminar entre el pedido y la respuesta: la
					// lista del backend es de antes, así que se conserva lo que llegó
					// después en lugar de reemplazarlo.
					setFiles((prev) => {
						const loadedIds = new Set(loaded.map((file) => file.id))
						return [
							...loaded,
							...prev.filter((file) => !loadedIds.has(file.id)),
						]
					})
				}
			})
			.catch((error) => {
				if (active) console.error('Error cargando archivos:', error)
			})
			.finally(() => {
				if (active && showLoader) setLoading(false)
			})

		return () => {
			active = false
		}
	}, [subject.id, authorizationHeader])

	useEffect(() => loadFiles({ showLoader: true }), [loadFiles])

	// El backend sube, indexa y detecta temas dentro del mismo request, así que un archivo
	// puede seguir procesándose después de que la lista ya llegó —sobre todo si se recargó
	// la página a mitad de una subida—. Al volver a la pestaña se vuelve a pedir, sin
	// spinner: si terminó, aparece solo en vez de quedar invisible hasta la próxima visita.
	useEffect(() => {
		const refresh = () => loadFiles()
		window.addEventListener('focus', refresh)
		return () => window.removeEventListener('focus', refresh)
	}, [loadFiles])

	// ── Seguir la cola de subidas de esta materia ──
	// Al montarse toma lo que ya estaba en curso, así que volver a Documentos
	// muestra las mismas filas en proceso; los que terminaron mientras el usuario
	// no estaba ya vienen en la lista que se acaba de pedir al backend.
	useEffect(() => {
		return subscribeToUploads(subject.id, {
			onChange: setUploadQueue,
			onUploaded: ({ id, filename }) => {
				setFiles((prev) =>
					prev.some((file) => file.id === id)
						? prev
						: [...prev, makeFileObj(filename, id)]
				)
			},
		})
	}, [subject.id])

	// Sincronizar la lista con App fuera de los setters de estado.
	useEffect(() => {
		onFilesLoaded?.(subject.id, files)
	}, [files, onFilesLoaded, subject.id])

	// ── Guardar todos los tags asociados a un archivo ──
	const saveTags = async (fileId, tags) => {
	const res = await apiFetch('/api/files/tags', {
		method: 'PATCH',
			headers: { 'Content-Type': 'application/json' },
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
	// La subida la maneja la cola del módulo: el archivo se sigue procesando
	// aunque el usuario se vaya de Documentos antes de que termine.
	const uploadFiles = (rawFiles) => {
		if (!user?.token) {
			setUploadError(
				subject.id,
				'Tu sesión no es válida. Volvé a iniciar sesión para subir documentos.'
			)
			return
		}

		enqueueUploads({
			subjectId: subject.id,
			files: rawFiles,
		})
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
		const res = await apiFetch(`/api/files/${encodeURIComponent(fileId)}`, {
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
						accept=".pdf,.txt,.docx,.md,.markdown"
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
						onClick={() => clearUploadError(subject.id)}
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
				<div className="docs-loading" role="status" aria-live="polite">
					<img
						src={dinaSearchingFiles}
						alt="Dina buscando tus archivos"
					/>
					<p>Buscando tus archivos...</p>
					<span className="docs-loading-bar" aria-hidden="true">
						<span className="docs-loading-bar-fill" />
					</span>
				</div>
			) : (
				<>
					<p className="docs-count">
						{`${filtered.length} ${filtered.length === 1 ? 'documento' : 'documentos'}${search ? ` para "${search}"` : ''}${activeTagFilters.length > 0 ? ` · tags: ${activeTagFilters.join(', ')}` : ''}${pendingUploads.length > 0 ? ` · ${pendingUploads.length} en curso` : ''}`}
					</p>

					{filtered.length === 0 && pendingUploads.length === 0 ? (
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
								accept=".pdf,.txt,.docx,.md,.markdown"
								onChange={handleUpload}
							/>
						</label>
					)}
				</div>
					) : (
				<ul className="file-list">
					{/* Arriba y sin filtrar: son la respuesta a algo que el usuario
					    acaba de hacer, así que no las puede esconder una búsqueda. */}
					{pendingUploads.map((pending) => (
						<li
							key={pending.uploadId}
							className="file-item file-item--pending"
							aria-busy="true"
						>
							<span className="file-item-icon">
								{FILE_ICONS[pending.type] ?? '📄'}
							</span>
							<div className="file-item-info">
								<span className="file-item-name">{pending.name}</span>
								<span className="file-item-status">
									<span className="loader" aria-hidden="true" />
									{pending.processing
										? 'Dina lo está procesando...'
										: 'En espera...'}
								</span>
							</div>
						</li>
					))}
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
