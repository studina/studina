import { useEffect, useRef, useState } from 'react'
import './FlashcardsPage.css'
import dinaEmptyFlashcards from '../assets/dina/dina-empty-flashcards.png'

export default function FlashcardsPage({ subject, user, onBack }) {
	const [cards, setCards] = useState([])
	const [files, setFiles] = useState([])
	const [selectedFileIds, setSelectedFileIds] = useState([])
	const [instructions, setInstructions] = useState('')
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
					fileIds: selectedFileIds,
					instructions: instructions.trim(),
				}),
			})
			const body = await response.json().catch(() => ({}))
			if (!response.ok) throw new Error(body.error || 'No se pudieron generar las flashcards')
			if (!Array.isArray(body.cards) || body.cards.length === 0) {
				throw new Error('La IA no devolvió flashcards para los documentos seleccionados')
			}

			setCards(body.cards)
			setIndex(0)
			setFlipped(false)
		} catch (requestError) {
			console.error('Error generando flashcards:', requestError)
			setError(requestError.message)
		} finally {
			setGenerating(false)
		}
	}

	if (cards.length === 0) {
		return (
			<div className="fc-page">
				<button className="btn-back" onClick={onBack}>← Volver</button>
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
						<label className="fc-generator-label" htmlFor="flashcard-instructions">Indicaciones adicionales</label>
						<textarea
							id="flashcard-instructions"
							className="fc-instructions"
							value={instructions}
							onChange={(event) => setInstructions(event.target.value)}
							placeholder="Por ejemplo: focalizá en definiciones y diferencias entre conceptos."
							rows={3}
						/>
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
