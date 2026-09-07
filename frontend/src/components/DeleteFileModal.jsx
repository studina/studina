import './DeleteFileModal.css'

export default function DeleteFileModal({ file, onCancel, onConfirm }) {
  return (
    <div
      className="delete-file-overlay"
      onClick={(event) => event.target === event.currentTarget && onCancel()}
    >
      <section
        className="delete-file-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-file-title"
      >
        <div className="delete-file-modal-header">
          <div>
            <p className="delete-file-modal-eyebrow">Eliminar documento</p>
            <h2 id="delete-file-title">¿Eliminar este archivo?</h2>
          </div>
          <button
            type="button"
            className="delete-file-modal-close"
            onClick={onCancel}
            aria-label="Cerrar confirmación"
          >
            ×
          </button>
        </div>

        <p className="delete-file-modal-name">{file.name}</p>
        <p className="delete-file-modal-message">Esta acción no se puede deshacer.</p>

        <div className="delete-file-modal-actions">
          <button type="button" className="delete-file-cancel" onClick={onCancel}>
            Cancelar
          </button>
          <button type="button" className="delete-file-confirm" onClick={onConfirm}>
            Eliminar archivo
          </button>
        </div>
      </section>
    </div>
  )
}