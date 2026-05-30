import { useState, useRef } from 'react'
import flashcardsData from '../data/flashcardsData'
import './FlashcardsPage.css'

export default function FlashcardsPage({ subject, onBack }) {
	const cards = flashcardsData[subject.id] ?? []
	const [index, setIndex] = useState(0)
	const [flipped, setFlipped] = useState(false)
	const [slideDir, setSlideDir] = useState(null) // 'left' | 'right' | null
	const [exiting, setExiting] = useState(false)
	const timerRef = useRef(null)

	if (cards.length === 0) {
		return (
			<div className="fc-page">
				<button className="btn-back" onClick={onBack}>← Volver</button>
				<div className="fc-empty">
					<span>🃏</span>
					<p>No hay flashcards para esta materia todavía.</p>
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
