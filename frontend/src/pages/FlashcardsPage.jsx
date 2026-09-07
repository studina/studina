import { useEffect, useRef, useState } from 'react'
import './FlashcardsPage.css'
import dinaEmptyFlashcards from '../assets/dina/dina-empty-flashcards.png'
import dinaSearchingFlashcards from '../assets/dina/dina-searching-flashcards.png'

export default function FlashcardsPage({ subject, user, onBack }) {
	const [cards, setCards] = useState([])
	const [savedSets, setSavedSets] = useState([])
	const [loadingSets, setLoadingSets] = useState(true)
	const [showGenerator, setShowGenerator] = useState(false)
	const [editingSetId, setEditingSetId] = useState(null)
	const [editingTitle, setEditingTitle] = useState('')
	const [savingTitle, setSavingTitle] = useState(false)
	const [deletingSetId, setDeletingSetId] = useState(null)
	const [confirmingDeleteId, setConfirmingDeleteId] = useState(null)
	const [files, setFiles] = useState([])
	const [selectedFileIds, setSelectedFileIds] = useState([])
	const [title, setTitle] = useState('')
	const [instructions, setInstructions] = useState('')
	const [cardCount, setCardCount] = useState(10)
	const [cardFormat, setCardFormat] = useState('term_definition')
	const [difficulty, setDifficulty] = useState('basic')
	const [loadingFiles, setLoadingFiles] = useState(true)
	const [generating, setGenerating] = useState(false)
	const [error, setError] = useState('')
	const [index, setIndex] = useState(0)
	const [flipped, setFlipped] = useState(false)
	const [slideDir, setSlideDir] = useState(null) // 'left' | 'right' | null
	const [exiting, setExiting] = useState(false)
	const timerRef = useRef(null)
	const authorizationHeader = user?.token ? `Bearer ${user.token}` : null

	useEffect(() => {
		if (!authorizationHeader) {
			setLoadingFiles(false)
			return undefined
		}

		let active = true
		setLoadingFiles(true)
		fetch(`/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`, {
			headers: { Authorization: authorizationHeader },
		})
			.then(async (response) => {
				const body = await response.json().catch(() => ({}))
				if (!response.ok) throw new Error(body.error || 'No se pudieron cargar los documentos')
				return Array.isArray(body.data) ? body.data : []
			})
			.then((loadedFiles) => {
				if (!active) return
				setFiles(loadedFiles)
			})
			.catch((requestError) => {
				if (active) setError(requestError.message)
			})
			.finally(() => {
				if (active) setLoadingFiles(false)
			})

		return () => { active = false }
	}, [authorizationHeader, subject.id])

	useEffect(() => {
		if (!authorizationHeader) {
			setLoadingSets(false)
			return undefined
		}

		let active = true
		setLoadingSets(true)
		fetch(`/api/flashcards?subjectId=${encodeURIComponent(String(subject.id))}`, {
			headers: { Authorization: authorizationHeader },
		})
			.then(async (response) => {
				const body = await response.json().catch(() => ({}))
				if (!response.ok) throw new Error(body.error || 'No se pudieron cargar las flashcards')
				return Array.isArray(body) ? body : []
			})
			.then((sets) => {
				if (active) {
					setSavedSets([...sets].sort((firstSet, secondSet) =>
						new Date(secondSet.date_created || 0) - new Date(firstSet.date_created || 0),
					))
				}
			})
			.catch((requestError) => {
				if (active) setError(requestError.message)
			})
			.finally(() => {
				if (active) setLoadingSets(false)
			})

		return () => { active = false }
	}, [authorizationHeader, subject.id])

	const toggleFile = (fileId) => {
		setSelectedFileIds((previousIds) =>
			previousIds.includes(fileId)
				? previousIds.filter((id) => id !== fileId)
				: [...previousIds, fileId]
		)
	}

	const generate = async () => {
		if (!authorizationHeader || selectedFileIds.length === 0 || generating) return

		setGenerating(true)
		setError('')
		try {
			const response = await fetch('/api/ai/files/flashcards', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					Authorization: authorizationHeader,
				},
				body: JSON.stringify({
					subjectId: String(subject.id),
					title: title.trim(),
					fileIds: selectedFileIds,
					instructions: instructions.trim(),
					cardCount,
					cardFormat,
					difficulty,
				}),
			})
			const body = await response.json().catch(() => ({}))
			if (!response.ok) throw new Error(body.error || 'No se pudieron generar las flashcards')
			if (!Array.isArray(body.cards) || body.cards.length === 0) {
				throw new Error('La IA no devolvió flashcards para los documentos seleccionados')
			}

			setCards(body.cards)
			setSavedSets((previousSets) => [body, ...previousSets])
			setIndex(0)
			setFlipped(false)
		} catch (requestError) {
			console.error('Error generando flashcards:', requestError)
			setError(requestError.message)
		} finally {
			setGenerating(false)
		}
	}

	const openSet = (flashcardSet) => {
		if (!Array.isArray(flashcardSet.cards) || flashcardSet.cards.length === 0) return
		setCards(flashcardSet.cards)
		setIndex(0)
		setFlipped(false)
	}

	const startEditingTitle = (flashcardSet) => {
		setEditingSetId(flashcardSet.id)
		setEditingTitle(flashcardSet.title || '')
		setError('')
	}

	const saveTitle = async () => {
		const trimmedTitle = editingTitle.trim()
		if (!trimmedTitle || !editingSetId || savingTitle) return

		setSavingTitle(true)
		setError('')
		try {
			const response = await fetch(
				`/api/flashcards/${encodeURIComponent(editingSetId)}?subjectId=${encodeURIComponent(String(subject.id))}`,
				{
					method: 'PATCH',
					headers: {
						'Content-Type': 'application/json',
						Authorization: authorizationHeader,
					},
					body: JSON.stringify({ title: trimmedTitle }),
				},
			)
			const body = await response.json().catch(() => ({}))
			if (!response.ok) throw new Error(body.error || 'No se pudo actualizar el nombre del set')

			setSavedSets((previousSets) => previousSets.map((flashcardSet) =>
				flashcardSet.id === editingSetId ? { ...flashcardSet, ...body } : flashcardSet,
			))
			setEditingSetId(null)
		} catch (requestError) {
			setError(requestError.message)
		} finally {
			setSavingTitle(false)
		}
	}

	const deleteSet = async (flashcardSetId) => {
		if (!authorizationHeader || deletingSetId) return

		setDeletingSetId(flashcardSetId)
		setError('')
		try {
			const response = await fetch(
				`/api/flashcards/${encodeURIComponent(flashcardSetId)}?subjectId=${encodeURIComponent(String(subject.id))}`,
				{ method: 'DELETE', headers: { Authorization: authorizationHeader } },
			)
			if (!response.ok) {
				const body = await response.json().catch(() => ({}))
				throw new Error(body.error || 'No se pudo eliminar el set de flashcards')
			}

			setSavedSets((previousSets) => previousSets.filter((flashcardSet) => flashcardSet.id !== flashcardSetId))
			setConfirmingDeleteId(null)
		} catch (requestError) {
			setError(requestError.message)
		} finally {
			setDeletingSetId(null)
		}
	}

	if (loadingSets && !showGenerator) {
		return (
			<div className="fc-page fc-page--loading">
				<button className="btn-back" onClick={onBack}>← Volver</button>
				<div className="fc-loading" role="status">
					<img src={dinaSearchingFlashcards} alt="Dina buscando tus sets de flashcards" />
					<p>Cargando tus sets...</p>
				</div>
			</div>
		)
	}

	if (cards.length === 0 && !showGenerator) {
		const hasNoDocuments = !loadingFiles && files.length === 0
		const hasNoSavedSets = !loadingSets && savedSets.length === 0

		return (
			<div className="fc-page">
				<button className="btn-back" onClick={onBack}>← Volver</button>
				<div className="fc-header">
					<h1 className="fc-title">Flashcards</h1>
					<p className="fc-sub">{subject.name}</p>
				</div>
				<div className="fc-library-header">
					<h2>Mis sets</h2>
					<button
						type="button"
						className="fc-generate-btn"
						onClick={() => setShowGenerator(true)}
						disabled={hasNoDocuments}
					>
						Nuevo set
					</button>
				</div>
				{hasNoSavedSets && (
					<div className="fc-empty">
						<img src={dinaEmptyFlashcards} alt="Dina con tarjetas de estudio" />
						<h2>Todavía no hay sets</h2>
						<p>
							{hasNoDocuments
								? 'Para crear flashcards, primero tenés que cargar documentos en esta materia.'
								: 'Creá uno a partir de los documentos de esta materia.'}
						</p>
					</div>
				)}
				<div className="fc-set-list">
					{savedSets.map((flashcardSet) => (
						<div className="fc-set-item" key={flashcardSet.id}>
							{editingSetId === flashcardSet.id ? (
								<div className="fc-set-edit">
									<input
										aria-label={`Nombre de ${flashcardSet.title}`}
										value={editingTitle}
										onChange={(event) => setEditingTitle(event.target.value)}
										maxLength={160}
									/>
									<div>
										<button type="button" onClick={saveTitle} disabled={!editingTitle.trim() || savingTitle}>
											{savingTitle ? 'Guardando...' : 'Guardar'}
										</button>
										<button type="button" onClick={() => setEditingSetId(null)} disabled={savingTitle}>Cancelar</button>
									</div>
								</div>
							) : confirmingDeleteId === flashcardSet.id ? (
								<div className="fc-set-edit">
									<span>¿Eliminar “{flashcardSet.title}”? Esta acción no se puede deshacer.</span>
									<div>
										<button
											type="button"
											onClick={() => deleteSet(flashcardSet.id)}
											disabled={deletingSetId === flashcardSet.id}
										>
											{deletingSetId === flashcardSet.id ? 'Eliminando...' : 'Confirmar'}
										</button>
										<button
											type="button"
											onClick={() => setConfirmingDeleteId(null)}
											disabled={deletingSetId === flashcardSet.id}
										>
											Cancelar
										</button>
									</div>
								</div>
							) : (
								<>
									<button type="button" className="fc-set-open" onClick={() => openSet(flashcardSet)}>
										<span className="fc-set-title">{flashcardSet.title}</span>
										<span className="fc-set-meta">{Array.isArray(flashcardSet.cards) ? flashcardSet.cards.length : 0} tarjetas</span>
									</button>
									<button type="button" className="fc-set-rename" onClick={() => startEditingTitle(flashcardSet)}>
										Editar nombre
									</button>
									<button type="button" className="fc-set-delete" onClick={() => setConfirmingDeleteId(flashcardSet.id)}>
										Eliminar
									</button>
								</>
							)}
						</div>
					))}
				</div>
				{error && <p className="fc-generator-error">{error}</p>}
			</div>
		)
	}

	if (cards.length === 0) {
		return (
			<div className="fc-page">
				<button className="btn-back" onClick={onBack}>← Volver</button>
				<button type="button" className="fc-cancel-generator" onClick={() => setShowGenerator(false)}>← Ver mis sets</button>
				<div className="fc-generator">
					<div className="fc-empty">
						<img src={dinaEmptyFlashcards} alt="Dina con tarjetas de estudio" />
						<h2>Generá tus flashcards</h2>
						<p>Elegí los documentos de {subject.name} que querés repasar.</p>
					</div>
					<div className="fc-generator-form">
						<p className="fc-generator-label">Documentos</p>
						{loadingFiles && <p className="fc-generator-status">Cargando documentos...</p>}
						{!loadingFiles && files.length === 0 && (
							<p className="fc-generator-status">Todavía no hay documentos cargados para esta materia.</p>
						)}
						<div className="fc-file-list">
							{files.map((file) => (
								<label className="fc-file-option" key={file.id}>
									<input
										type="checkbox"
										checked={selectedFileIds.includes(file.id)}
										onChange={() => toggleFile(file.id)}
									/>
									<span>{file.filename || file.name || 'Documento sin nombre'}</span>
								</label>
							))}
						</div>
						<label className="fc-generator-label" htmlFor="flashcard-title">Nombre del set</label>
						<input
							id="flashcard-title"
							className="fc-instructions"
							value={title}
							onChange={(event) => setTitle(event.target.value)}
							placeholder="Flashcards #1"
							maxLength={160}
						/>
						<label className="fc-generator-label" htmlFor="flashcard-instructions">Indicaciones adicionales</label>
						<textarea
							id="flashcard-instructions"
							className="fc-instructions"
							value={instructions}
							onChange={(event) => setInstructions(event.target.value)}
							placeholder="Por ejemplo: focalizá en definiciones y diferencias entre conceptos."
							rows={3}
						/>
						<label className="fc-generator-label" htmlFor="flashcard-count">Cantidad de flashcards: {cardCount}</label>
						<input
							id="flashcard-count"
							type="range"
							className="fc-count-slider"
							min={5}
							max={20}
							value={cardCount}
							onChange={(event) => setCardCount(Number(event.target.value))}
						/>
						<label className="fc-generator-label" htmlFor="flashcard-format">Formato de tarjeta</label>
						<select
							id="flashcard-format"
							className="fc-select"
							value={cardFormat}
							onChange={(event) => setCardFormat(event.target.value)}
						>
							<option value="term_definition">Término / Definición</option>
							<option value="question_answer">Pregunta / Respuesta</option>
						</select>
						<label className="fc-generator-label" htmlFor="flashcard-difficulty">Dificultad</label>
						<select
							id="flashcard-difficulty"
							className="fc-select"
							value={difficulty}
							onChange={(event) => setDifficulty(event.target.value)}
						>
							<option value="basic">Básica</option>
							<option value="intermediate">Intermedia</option>
							<option value="advanced">Avanzada</option>
						</select>
						{error && <p className="fc-generator-error">{error}</p>}
						<button
							type="button"
							className="fc-generate-btn"
							onClick={generate}
							disabled={loadingFiles || generating || selectedFileIds.length === 0}
						>
							{generating ? 'Generando...' : 'Generar flashcards'}
						</button>
					</div>
				</div>
			</div>
		)
	}

	const current = cards[index]

	const navigate = (dir) => {
		if (exiting) return
		clearTimeout(timerRef.current)
		setSlideDir(dir)
		setExiting(true)
		timerRef.current = setTimeout(() => {
			setFlipped(false)
			setIndex((i) =>
				dir === 'left'
					? (i + 1) % cards.length
					: (i - 1 + cards.length) % cards.length
			)
			setExiting(false)
		}, 260)
	}

	const goNext = () => navigate('left')
	const goPrev = () => navigate('right')
	const handleFlip = () => { if (!exiting) setFlipped((f) => !f) }

	const slideClass = exiting
		? `fc-slide-exit-${slideDir}`
		: slideDir ? `fc-slide-enter-${slideDir}` : ''

	return (
		<div className="fc-page">
			<button className="btn-back" onClick={onBack}>← Volver</button>
			<button type="button" className="fc-cancel-generator" onClick={() => { setCards([]); setShowGenerator(false) }}>← Ver mis sets</button>

			<div className="fc-header">
				<h1 className="fc-title">Flashcards</h1>
				<p className="fc-sub">{subject.name}</p>
			</div>

			{/* ── Progreso ── */}
			<div className="fc-progress-row">
				<span className="fc-counter">{index + 1} / {cards.length}</span>
				<div className="fc-progress-bar">
					<div
						className="fc-progress-fill"
						style={{ width: `${((index + 1) / cards.length) * 100}%` }}
					/>
				</div>
			</div>

			{/* ── Tarjeta ──
			    fc-scene-wrapper: limita el ancho y clipea el slide (no el flip)
			    fc-slide:         maneja translateX (slide entre cards)
			    fc-card:          maneja rotateY (flip), tiene preserve-3d
			-->*/}
			<div className={`fc-scene-wrapper ${exiting ? 'is-sliding' : ''}`}>
				<div className="fc-scene" onClick={handleFlip}>
					{/* El slide va en este div, separado del flip */}
					<div key={index} className={`fc-slide ${slideClass}`}>
						{/* El flip va acá, sin interferencia del translateX */}
						<div className={`fc-card ${flipped ? 'flipped' : ''}`}>
							<div className="fc-face fc-front">
								<span className="fc-face-label">Término</span>
								<p className="fc-term">{current.term}</p>
								<span className="fc-hint">Tocá para ver la definición</span>
							</div>
							<div className="fc-face fc-back">
								<span className="fc-face-label">Definición</span>
								<p className="fc-definition">{current.definition}</p>
								<span className="fc-hint">Tocá para volver al término</span>
							</div>
						</div>
					</div>
				</div>
			</div>

			{/* ── Navegación ── */}
			<div className="fc-nav">
				<button className="fc-nav-btn" onClick={goPrev} disabled={cards.length <= 1}>
					← Anterior
				</button>
				<button
					className="fc-nav-btn fc-nav-btn--flip"
					onClick={handleFlip}
				>
					{flipped ? '🔄 Ver término' : '🔄 Ver definición'}
				</button>
				<button className="fc-nav-btn" onClick={goNext} disabled={cards.length <= 1}>
					Siguiente →
				</button>
			</div>
		</div>
	)
}
