import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import './ConfirmDeleteModal.css'

/**
 * Confirmación de borrado sobre un overlay que desenfoca el resto de la pantalla.
 *
 * Es genérico a propósito: `DeleteFileModal` y `DeleteSubjectModal` hacen lo mismo con
 * textos fijos, y podrían migrar a este cuando se los toque.
 *
 * Va por un portal a `document.body` porque `position: fixed` no se posiciona contra el
 * viewport si algún ancestro tiene `transform`, `filter` o `backdrop-filter`: esos crean
 * un bloque contenedor y el overlay queda recortado dentro de la página, desenfocando
 * sólo una parte de la pantalla. `.fc-page` anima las dos cosas con `fill-mode: both`,
 * así que las conserva para siempre.
 */
export default function ConfirmDeleteModal({
	eyebrow,
	title,
	name,
	message = 'Esta acción no se puede deshacer.',
	confirmLabel,
	busy = false,
	onCancel,
	onConfirm,
}) {
	// Escapar es lo que uno intenta primero para salir de un diálogo.
	useEffect(() => {
		const onKeyDown = (event) => {
			if (event.key === 'Escape' && !busy) onCancel()
		}
		window.addEventListener('keydown', onKeyDown)
		return () => window.removeEventListener('keydown', onKeyDown)
	}, [busy, onCancel])

	return createPortal(
		<div
			className="confirm-delete-overlay"
			onClick={(event) => {
				if (event.target === event.currentTarget && !busy) onCancel()
			}}
		>
			<section
				className="confirm-delete-modal"
				role="dialog"
				aria-modal="true"
				aria-labelledby="confirm-delete-title"
			>
				<div className="confirm-delete-header">
					<p className="confirm-delete-eyebrow">{eyebrow}</p>
					<h2 id="confirm-delete-title">{title}</h2>
				</div>

				{name && <p className="confirm-delete-name">{name}</p>}
				<p className="confirm-delete-message">{message}</p>

				<div className="confirm-delete-actions">
					<button
						type="button"
						className="confirm-delete-cancel"
						onClick={onCancel}
						disabled={busy}
					>
						Cancelar
					</button>
					<button
						type="button"
						className="confirm-delete-confirm"
						onClick={onConfirm}
						disabled={busy}
					>
						{busy ? 'Eliminando...' : confirmLabel}
					</button>
				</div>
			</section>
		</div>,
		document.body,
	)
}
