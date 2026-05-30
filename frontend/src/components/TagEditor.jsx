import { useState, useRef, useEffect } from 'react'
import './TagEditor.css'

/**
 * Modal para agregar/editar tags de un archivo.
 * Props:
 *   fileName    – nombre del archivo (se muestra en el título)
 *   initialTags – array de strings ya asignados al archivo
 *   suggestions – array de strings (todos los tags usados en la materia)
 *   onSave(tags) – callback al confirmar
 *   onCancel     – callback al cancelar
 */
export default function TagEditor({ fileName, initialTags = [], suggestions = [], onSave, onCancel }) {
	const [tags, setTags] = useState([...initialTags])
	const [input, setInput] = useState('')
	const inputRef = useRef(null)

	useEffect(() => {
		inputRef.current?.focus()
	}, [])

	const addTag = (raw) => {
		const value = raw.trim().toLowerCase().replace(/\s+/g, '-')
		if (!value || tags.includes(value)) return
		setTags((prev) => [...prev, value])
		setInput('')
		inputRef.current?.focus()
	}

	const removeTag = (tag) => setTags((prev) => prev.filter((t) => t !== tag))

	const handleKeyDown = (e) => {
		if (e.key === 'Enter' || e.key === ',') {
			e.preventDefault()
			addTag(input)
		} else if (e.key === 'Backspace' && input === '' && tags.length > 0) {
			setTags((prev) => prev.slice(0, -1))
		}
	}

	// Sugerencias: tags de la materia que aún no están en el archivo
	// y que coinciden con lo que el usuario está escribiendo
	const filteredSuggestions = suggestions.filter(
		(s) => !tags.includes(s) && s.includes(input.trim().toLowerCase())
	)

	return (
		<div className="tag-editor-overlay" onClick={(e) => e.target === e.currentTarget && onCancel()}>
			<div className="tag-editor-modal">
				<div className="tag-editor-header">
					<span className="tag-editor-icon">🏷️</span>
					<div>
						<p className="tag-editor-title">Editar tags</p>
						<p className="tag-editor-filename">{fileName}</p>
					</div>
					<button className="tag-editor-close" onClick={onCancel}>✕</button>
				</div>

				<div className="tag-editor-body">
					<p className="tag-editor-hint">
						Escribí un tag y presioná <kbd>Enter</kbd> o <kbd>,</kbd> para agregarlo.
					</p>

					<div className="tag-input-box" onClick={() => inputRef.current?.focus()}>
						{tags.map((t) => (
							<span key={t} className="tag tag--editor">
								{t}
								<button
									className="tag-remove"
									onClick={() => removeTag(t)}
									title={`Quitar "${t}"`}
								>✕</button>
							</span>
						))}
						<input
							ref={inputRef}
							className="tag-text-input"
							value={input}
							onChange={(e) => setInput(e.target.value)}
							onKeyDown={handleKeyDown}
							placeholder={tags.length === 0 ? 'ej: examen, parcial, 2024...' : ''}
						/>
					</div>

					{/* ── Sugerencias de la materia ── */}
					{filteredSuggestions.length > 0 && (
						<div className="tag-suggestions">
							<span className="tag-suggestions-label">Tags de esta materia:</span>
							<div className="tag-suggestions-list">
								{filteredSuggestions.map((s) => (
									<button
										key={s}
										className="tag tag--suggestion"
										onClick={() => addTag(s)}
									>
										＋ {s}
									</button>
								))}
							</div>
						</div>
					)}
				</div>

				<div className="tag-editor-footer">
					<button className="btn-tag-cancel" onClick={onCancel}>Cancelar</button>
					<button className="btn-tag-save" onClick={() => onSave(tags)}>
						Guardar tags
					</button>
				</div>
			</div>
		</div>
	)
}
