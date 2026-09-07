import { useState, useEffect, useRef } from 'react'
import './SubjectMenu.css'

/**
 * Botón ⋮ con dropdown de opciones para una materia.
 * Props:
 *   onRename – callback al elegir "Renombrar"
 *   onDelete – callback al elegir "Eliminar"
 */
export default function SubjectMenu({ onRename, onDelete }) {
	const [open, setOpen] = useState(false)
	const ref = useRef(null)

	// Cerrar al clickear fuera
	useEffect(() => {
		if (!open) return
		const handler = (e) => {
			if (!ref.current?.contains(e.target)) setOpen(false)
		}
		document.addEventListener('mousedown', handler)
		return () => document.removeEventListener('mousedown', handler)
	}, [open])

	const handleOption = (cb) => {
		setOpen(false)
		cb()
	}

	return (
		<div className="subject-menu" ref={ref}>
			<button
				className="subject-menu-trigger"
				title="Opciones"
				onClick={(e) => { e.stopPropagation(); setOpen((v) => !v) }}
			>
				⋮
			</button>

			{open && (
				<ul className="subject-menu-dropdown" onClick={(e) => e.stopPropagation()}>
					<li
						className="subject-menu-item"
						onClick={() => handleOption(onRename)}
					>
						<span className="subject-menu-item-icon">✏️</span>
						Renombrar
					</li>
					<li
						className="subject-menu-item subject-menu-item--danger"
						onClick={() => handleOption(onDelete)}
					>
						<span className="subject-menu-item-icon">🗑️</span>
						Eliminar
					</li>
				</ul>
			)}
		</div>
	)
}
