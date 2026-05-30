import { useState, useRef, useEffect } from 'react'
import './SubjectModal.css'

/**
 * Modal para crear o renombrar una materia.
 * Props:
 *   initialName – string (vacío = crear nueva, con valor = renombrar)
 *   onSave(name) – callback con el nombre ingresado
 *   onCancel     – callback al cerrar sin guardar
 */
export default function SubjectModal({ initialName = '', onSave, onCancel }) {
	const isEditing = initialName !== ''
	const [name, setName] = useState(initialName)
	const inputRef = useRef(null)

	useEffect(() => {
		inputRef.current?.focus()
		inputRef.current?.select()
	}, [])

	const handleSubmit = (e) => {
		e.preventDefault()
		const trimmed = name.trim()
		if (!trimmed) return
		onSave(trimmed)
	}

	return (
		<div
			className="subject-modal-overlay"
			onClick={(e) => e.target === e.currentTarget && onCancel()}
		>
			<div className="subject-modal">
				<div className="subject-modal-header">
					<span className="subject-modal-icon">{isEditing ? '✏️' : '📚'}</span>
					<div>
						<p className="subject-modal-title">
							{isEditing ? 'Renombrar materia' : 'Nueva materia'}
						</p>
						<p className="subject-modal-sub">
							{isEditing ? `Nombre actual: "${initialName}"` : 'Agregá una nueva materia a tu lista'}
						</p>
					</div>
					<button className="subject-modal-close" onClick={onCancel}>✕</button>
				</div>

				<form className="subject-modal-body" onSubmit={handleSubmit}>
					<label className="subject-modal-label" htmlFor="subject-name">
						{isEditing ? 'Nuevo nombre' : 'Nombre de la materia'}
					</label>
					<input
						ref={inputRef}
						id="subject-name"
						className="subject-modal-input"
						type="text"
						placeholder="ej: Sistemas Operativos"
						value={name}
						onChange={(e) => setName(e.target.value)}
						maxLength={60}
					/>

					<div className="subject-modal-footer">
						<button type="button" className="btn-subject-cancel" onClick={onCancel}>
							Cancelar
						</button>
						<button
							type="submit"
							className="btn-subject-save"
							disabled={!name.trim() || name.trim() === initialName}
						>
							{isEditing ? 'Guardar nombre' : 'Crear materia'}
						</button>
					</div>
				</form>
			</div>
		</div>
	)
}
