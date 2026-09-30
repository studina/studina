import { useEffect, useRef, useState } from 'react'
import SelectAllFiles from './SelectAllFiles'
import { apiFetch } from '../lib/session'
import './SubjectModal.css'
import './TopicModal.css'

/** El largo de `topics.name` en Directus; el backend rechaza lo que lo supere. */
const MAX_NAME_LENGTH = 160

/**
 * Modal para crear un tema a mano, o editar uno existente, desde la vista de progreso.
 *
 * Al crear, el tema necesita al menos un documento: es de ahí de donde salen después las
 * flashcards y los cuestionarios del tema, así que uno sin material no serviría para practicar.
 *
 * Al editar sólo se tocan el título y la descripción. Los documentos quedan fuera a
 * propósito: las tarjetas y preguntas generadas desde un documento conservan el tema, así que
 * sacárselo dejaría recursos que cuentan para su dominio sin el material de donde salieron.
 *
 * Props:
 *   subject        – la materia del tema
 *   topic          – el tema a editar, como viene en el panorama; sin él, se crea uno nuevo
 *   onSaved(topic) – el tema ya guardado, tal como lo devuelve el backend
 *   onCancel       – cerrar sin guardar
 */
export default function TopicModal({ subject, topic, onSaved, onCancel }) {
	const isEditing = Boolean(topic)
	const initialName = topic?.topic_name ?? ''
	const initialDescription = topic?.description ?? ''
	const [name, setName] = useState(initialName)
	const [description, setDescription] = useState(initialDescription)
	const [files, setFiles] = useState([])
	const [loadingFiles, setLoadingFiles] = useState(!isEditing)
	const [selectedFileIds, setSelectedFileIds] = useState([])
	const [submitting, setSubmitting] = useState(false)
	const [error, setError] = useState('')
	const inputRef = useRef(null)
	// El estado tarda un render en llegar: sin esto, un doble click manda el pedido dos veces.
	const submittingRef = useRef(false)

	useEffect(() => {
		inputRef.current?.focus()
	}, [])

	useEffect(() => {
		if (isEditing) return undefined

		let active = true
		apiFetch(`/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`)
			.then(async (response) => {
				const body = await response.json().catch(() => ({}))
				if (!response.ok) throw new Error(body.error || 'No se pudieron cargar los documentos')
				return Array.isArray(body.data) ? body.data : []
			})
			.then((loadedFiles) => {
				if (active) setFiles(loadedFiles)
			})
			.catch((requestError) => {
				if (active) setError(requestError.message)
			})
			.finally(() => {
				if (active) setLoadingFiles(false)
			})

		return () => {
			active = false
		}
	}, [subject.id, isEditing])

	const toggleFile = (fileId) =>
		setSelectedFileIds((current) =>
			current.includes(fileId) ? current.filter((id) => id !== fileId) : [...current, fileId],
		)

	const changed = name.trim() !== initialName || description.trim() !== initialDescription.trim()
	const canSubmit = Boolean(name.trim())
		&& !submitting
		&& (isEditing ? changed : selectedFileIds.length > 0)

	const handleSubmit = async (event) => {
		event.preventDefault()
		if (!canSubmit || submittingRef.current) return

		submittingRef.current = true
		setSubmitting(true)
		setError('')
		try {
			const response = isEditing
				? await apiFetch(`/api/topics/${encodeURIComponent(String(topic.topic_id))}`, {
					method: 'PATCH',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ name: name.trim(), description: description.trim() }),
				})
				: await apiFetch('/api/topics', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({
						subjectId: String(subject.id),
						name: name.trim(),
						description: description.trim(),
						fileIds: selectedFileIds,
					}),
				})
			const body = await response.json().catch(() => ({}))
			if (!response.ok) {
				throw new Error(body.error || (isEditing ? 'No se pudo guardar el tema' : 'No se pudo crear el tema'))
			}
			onSaved(body)
		} catch (requestError) {
			setError(requestError.message)
		} finally {
			submittingRef.current = false
			setSubmitting(false)
		}
	}

	return (
		<div
			className="subject-modal-overlay"
			onClick={(event) => event.target === event.currentTarget && onCancel()}
		>
			<div className="subject-modal topic-modal" role="dialog" aria-labelledby="topic-modal-title">
				<div className="subject-modal-header">
					<span className="subject-modal-icon">{isEditing ? '✏️' : '🧩'}</span>
					<div>
						<p className="subject-modal-title" id="topic-modal-title">
							{isEditing ? 'Editar tema' : 'Nuevo tema'}
						</p>
						<p className="subject-modal-sub">
							{isEditing ? `Nombre actual: "${initialName}"` : `Agregá un tema a ${subject.name}`}
						</p>
					</div>
					<button type="button" className="subject-modal-close" onClick={onCancel} aria-label="Cerrar">
						✕
					</button>
				</div>

				<form className="subject-modal-body" onSubmit={handleSubmit}>
					<label className="subject-modal-label" htmlFor="topic-modal-name">
						Título
					</label>
					<input
						ref={inputRef}
						id="topic-modal-name"
						className="subject-modal-input"
						type="text"
						placeholder="ej: Algoritmos de consenso"
						value={name}
						onChange={(event) => setName(event.target.value)}
						maxLength={MAX_NAME_LENGTH}
					/>

					<label className="subject-modal-label" htmlFor="topic-modal-description">
						Descripción <span className="topic-modal-optional">(opcional)</span>
					</label>
					<textarea
						id="topic-modal-description"
						className="subject-modal-input topic-modal-description"
						placeholder="¿Qué abarca este tema?"
						value={description}
						onChange={(event) => setDescription(event.target.value)}
						rows={3}
					/>

					{!isEditing && (
						<>
							<p className="subject-modal-label">Documentos</p>
							{loadingFiles && (
								<p className="topic-modal-status" role="status">
									<span className="loader" aria-hidden="true" />
									Cargando documentos…
								</p>
							)}
							{!loadingFiles && files.length === 0 && !error && (
								<p className="topic-modal-status topic-modal-status--empty">
									<span aria-hidden="true">📂</span>
									Todavía no hay documentos en esta materia. Subí uno para poder crear un tema.
								</p>
							)}
							{files.length > 0 && (
								<div className="topic-modal-file-list">
									<SelectAllFiles
										className="topic-modal-file-option topic-modal-file-option--all"
										files={files}
										selectedFileIds={selectedFileIds}
										onChange={setSelectedFileIds}
									/>
									{files.map((file) => (
										<label className="topic-modal-file-option" key={file.id}>
											<input
												type="checkbox"
												checked={selectedFileIds.includes(file.id)}
												onChange={() => toggleFile(file.id)}
											/>
											<span>{file.filename || file.name || 'Documento sin nombre'}</span>
										</label>
									))}
								</div>
							)}
						</>
					)}

					{error && <p className="topic-modal-error" role="alert">{error}</p>}

					<div className="subject-modal-footer">
						<button type="button" className="btn-subject-cancel" onClick={onCancel}>
							Cancelar
						</button>
						<button type="submit" className="btn-subject-save" disabled={!canSubmit}>
							{isEditing
								? (submitting ? 'Guardando…' : 'Guardar cambios')
								: (submitting ? 'Creando…' : 'Crear tema')}
						</button>
					</div>
				</form>
			</div>
		</div>
	)
}
