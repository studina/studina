import { useEffect, useState } from 'react'
import MasteryRing from './MasteryRing'
import './ProgressWidget.css'
import dinaAnalyzing from '../assets/dina/dina-analyzing.png'

/**
 * El resumen de progreso que vive en la pantalla de la materia.
 *
 * Es deliberadamente chico: un número, una línea de contexto y el tema más flojo. Todo el
 * detalle —cobertura por tema, archivos asociados, qué conviene repasar— vive en la pantalla
 * de progreso, a un click de acá.
 *
 * Si el pedido falla, el widget no se renderiza. Es información de apoyo: no vale romper la
 * pantalla principal de la materia por un agregado que no cargó.
 */
export default function ProgressWidget({ subject, user, onOpen }) {
	const [state, setState] = useState({ status: 'loading', overview: null })

	useEffect(() => {
		if (!user?.token) return undefined

		let active = true

		fetch(`/api/progress?subjectId=${encodeURIComponent(String(subject.id))}`, {
			headers: { Authorization: `Bearer ${user.token}` },
		})
			.then(async (response) => {
				const body = await response.json().catch(() => ({}))
				if (!response.ok) throw new Error(body.error || 'No se pudo cargar tu progreso')
				return body
			})
			.then((body) => {
				if (active) setState({ status: 'ready', overview: body })
			})
			.catch((error) => {
				if (!active) return
				console.error('Error cargando el progreso:', error)
				setState({ status: 'failed', overview: null })
			})

		return () => {
			active = false
		}
	}, [subject.id, user?.token])

	const { status, overview } = state

	if (status === 'failed') return null

	if (status === 'loading') {
		return (
			<section className="progress-widget progress-widget--loading" aria-busy="true">
				<span className="loader" />
				<p>Cargando tu progreso…</p>
			</section>
		)
	}

	const topics = overview?.topics ?? []
	const practiced = overview?.practiced_topics ?? 0

	// Entre los practicados, el más flojo primero. Los que no se practicaron no compiten:
	// no hay evidencia de que estén flojos.
	const weakest = topics
		.filter((topic) => topic.mastery_score != null)
		.sort((first, second) => first.mastery_score - second.mastery_score)[0]

	return (
		<button type="button" className="progress-widget" onClick={onOpen}>
			<header className="progress-widget-head">
				<h2>Tu progreso</h2>
				<span className="progress-widget-arrow" aria-hidden="true">→</span>
			</header>

			{practiced === 0 ? (
				<div className="progress-widget-empty">
					<MasteryRing value={null} size={92} />
					<p>
						Todavía no practicaste nada de esta materia. Generá flashcards o un
						cuestionario para empezar a medir tu dominio.
					</p>
				<img className="progress-widget-dina" src={dinaAnalyzing} alt="" />
				</div>
			) : (
				<>
					<div className="progress-widget-score">
						<MasteryRing value={overview.average_mastery} size={112} caption="dominio" />
						<div className="progress-widget-facts">
							<p>
								<strong>{overview.strong_topics}</strong> de{' '}
								<strong>{overview.total_topics}</strong>{' '}
								{overview.total_topics === 1 ? 'tema dominado' : 'temas dominados'}
							</p>
							<p className="progress-widget-muted">
								Practicaste {practiced} de {overview.total_topics}
							</p>
						</div>
					</div>

					{weakest && (
						<p className="progress-widget-weakest">
							A repasar: <strong>{weakest.topic_name}</strong>
						</p>
					)}
				</>
			)}

			<footer className="progress-widget-foot">
				<span className="progress-widget-cta">Ver detalle →</span>
			</footer>
		</button>
	)
}
