import './DeleteSubjectModal.css'

export default function DeleteSubjectModal({ subject, onCancel, onConfirm }) {
  return (
    <div
      className="delete-subject-overlay"
      onClick={(event) => event.target === event.currentTarget && onCancel()}
    >
      <section
        className="delete-subject-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-subject-title"
      >
        <div className="delete-subject-modal-header">
          <div>
            <p className="delete-subject-modal-eyebrow">Eliminar materia</p>
            <h2 id="delete-subject-title">¿Eliminar {subject.name}?</h2>
          </div>
          <button
            type="button"
            className="delete-subject-modal-close"
            onClick={onCancel}
            aria-label="Cerrar confirmación"
          >
            ×
          </button>
        </div>

        <p className="delete-subject-modal-message">
          Esta acción eliminará la materia y todos sus documentos asociados. No se puede deshacer.
        </p>

        <div className="delete-subject-modal-actions">
          <button type="button" className="delete-subject-cancel" onClick={onCancel}>
            Cancelar
          </button>
          <button type="button" className="delete-subject-confirm" onClick={onConfirm}>
            Eliminar materia
          </button>
        </div>
      </section>
    </div>
  )
}