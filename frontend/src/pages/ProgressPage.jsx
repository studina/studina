import { useEffect, useRef, useState } from 'react'
import MasteryRing from '../components/MasteryRing'
import { openFileInNewTab } from '../lib/openFile'
import { apiFetch } from '../lib/session'
import { levelClass, levelLabel, percent } from '../lib/mastery'
import '../styles/library.css'
import './ProgressPage.css'
import dinaThinking from '../assets/dina/dina-thinking.png'

/** Los grupos en el orden en que se leen: primero lo que hay que atender. */
const groups = [
	{ level: 'weak', title: 'Necesitan repaso' },
	{ level: 'progressing', title: 'En progreso' },
	{ level: 'strong', title: 'Dominados' },
	{ level: null, title: 'Sin empezar' },
]

export default function ProgressPage({ subject, user, onBack, onGoToFlashcards, onGoToQuiz }) {
	const [state, setState] = useState({ status: 'loading', overview: null, error: '' })
	// El fallo al abrir un archivo es del click, no de la carga: se muestra en el mismo lugar
	// pero no tiene por qué borrar el progreso que ya está en pantalla.
	const [fileError, setFileError] = useState('')
	// Los object URLs de los archivos abiertos: revocarlos al abrirlos rompería la pestaña,
	// así que se juntan acá y se sueltan al desmontar.
	const objectUrlsRef = useRef([])

	useEffect(() => () => {
		objectUrlsRef.current.forEach((url) => URL.revokeObjectURL(url))
		objectUrlsRef.current = []
	}, [])

	useEffect(() => {
		if (!user?.token) return undefined

		let active = true

		apiFetch(`/api/progress?subjectId=${encodeURIComponent(String(subject.id))}`)
			.then(async (response) => {
				const body = await response.json().catch(() => ({}))
				if (!response.ok) throw new Error(body.error || 'No se pudo cargar tu progreso')
				return body
			})
			.then((body) => {
				if (active) setState({ status: 'ready', overview: body, error: '' })
			})
			.catch((requestError) => {
				if (active) setState({ status: 'failed', overview: null, error: requestError.message })
			})

		return () => {
			active = false
		}
	}, [subject.id, user?.token])

	const { status, overview } = state
	const loading = status === 'loading'
	const error = fileError || state.error

	const openFile = async (file) => {
		if (!user?.token) return
		try {
			setFileError('')
			const objectUrl = await openFileInNewTab({
				fileId: file.id,
				filename: file.filename,
			})
			objectUrlsRef.current.push(objectUrl)
		} catch (requestError) {
			setFileError(requestError.message)
		}
	}

	const topics = overview?.topics ?? []
	const byMastery = (first, second) =>
		(first.mastery_score ?? 0) - (second.mastery_score ?? 0)
			|| String(first.topic_name).localeCompare(String(second.topic_name))
	const toReview = topics.filter((topic) => topic.level === 'weak').sort(byMastery)

	const renderFiles = (topic) => {
		if (!topic.files?.length) return null
		return (
			<div className="topic-files">
				<span className="topic-files-label">Material:</span>
				{topic.files.map((file) => (
					<button
						key={file.id}
						type="button"
						className="topic-file"
						onClick={() => openFile(file)}
					>
						{file.filename}
					</button>
				))}
			</div>
		)
	}

	const renderTopic = (topic) => (
		<li className="topic-row" key={topic.topic_id}>
			<div className="topic-row-head">
				<span className="topic-name">{topic.topic_name || 'Tema sin nombre'}</span>
				<span className={`topic-chip ${levelClass(topic.level)}`}>
					{levelLabel(topic.level)}
				</span>
			</div>

			{topic.mastery_score == null ? (
				// Sin barra a propósito: no es 0% de dominio, es que todavía no hay evidencia.
				<p className="topic-meta">
					{topic.total_resources > 0
						? `${topic.total_resources} ${topic.total_resources === 1 ? 'recurso disponible' : 'recursos disponibles'} para practicar`
						: 'Todavía no hay tarjetas ni preguntas de este tema'}
				</p>
			) : (
				<>
					<div className="topic-row-bar">
						<div className="progress-bar">
							<div
								className="progress-fill"
								style={{ width: `${percent(topic.mastery_score)}%` }}
							/>
						</div>
						<span className="topic-percent">{percent(topic.mastery_score)}%</span>
					</div>
					<p className="topic-meta">
						Practicaste {topic.unique_resources_seen} de {topic.total_resources}{' '}
						{topic.total_resources === 1 ? 'recurso' : 'recursos'} · {topic.total_attempts}{' '}
						{topic.total_attempts === 1 ? 'intento' : 'intentos'}
					</p>
				</>
			)}

			{renderFiles(topic)}
		</li>
	)

	return (
		<div className="progress-page">
			{onBack && (
				<button className="btn-back" onClick={onBack}>
					← Volver
				</button>
			)}

			<div className="main-header">
				<h1>Tu progreso</h1>
				<span className="progress-page-sub">{subject.name}</span>
			</div>

			{error && <p className="progress-page-error" role="alert">{error}</p>}

			{loading && (
				<div className="page-loading">
					<span className="loader loader--lg" />
					<p>Cargando tu progreso…</p>
				</div>
			)}

			{!loading && overview && topics.length === 0 && (
				<section className="progress-empty">
					<img src={dinaThinking} alt="" />
					<div>
						<h2>Todavía no hay temas en esta materia</h2>
						<p>
							Los temas se detectan solos cuando subís un documento. Subí un apunte y
							después generá flashcards o un cuestionario para empezar a medir tu dominio.
						</p>
					</div>
				</section>
			)}

			{!loading && overview && topics.length > 0 && (
				<>
					<section className="progress-summary">
						<div className="progress-summary-score">
							<MasteryRing value={overview.average_mastery} size={118} caption="dominio" />
							<p className="progress-summary-note">
								{overview.practiced_topics === 0
									? 'Todavía no practicaste ningún tema.'
									: `Promedio sobre los ${overview.practiced_topics} ${
										overview.practiced_topics === 1 ? 'tema practicado' : 'temas practicados'
									}, no sobre los ${overview.total_topics} de la materia.`}
							</p>
						</div>

						<div className="progress-tiles">
							<div className="progress-tile">
								<span>Temas dominados</span>
								<strong>{overview.strong_topics} / {overview.total_topics}</strong>
							</div>
							<div className="progress-tile">
								<span>Temas practicados</span>
								<strong>{overview.practiced_topics} / {overview.total_topics}</strong>
							</div>
							<div className="progress-tile">
								<span>Cobertura del material</span>
								<strong>{percent(overview.coverage)}%</strong>
							</div>
							<div className="progress-tile">
								<span>Intentos registrados</span>
								<strong>{overview.total_attempts}</strong>
							</div>
						</div>
					</section>

					{toReview.length > 0 && (
						<section className="progress-section progress-section--review">
							<h2>Qué te conviene repasar</h2>
							<p className="progress-section-sub">
								Del más flojo al menos flojo, con el material de donde salió cada tema.
							</p>
							<ul className="topic-list">{toReview.map(renderTopic)}</ul>
						</section>
					)}

					<section className="progress-section">
						<h2>Todos los temas</h2>
						{groups.map(({ level, title }) => {
							const groupTopics = topics
								.filter((topic) => (topic.level ?? null) === level)
								.sort(byMastery)
							if (groupTopics.length === 0) return null
							return (
								<div className="topic-group" key={title}>
									<h3>
										{title} <span>({groupTopics.length})</span>
									</h3>
									<ul className="topic-list">{groupTopics.map(renderTopic)}</ul>
								</div>
							)
						})}
					</section>

					<section className="progress-actions">
						{/* La ronda de repaso se arma por set, no por tema, así que desde acá no se
						    puede lanzar un repaso filtrado: estos botones llevan a la biblioteca. */}
						<p>¿Listo para practicar?</p>
						<div className="progress-actions-buttons">
							{onGoToFlashcards && (
								<button type="button" onClick={onGoToFlashcards}>
									Ir a flashcards
								</button>
							)}
							{onGoToQuiz && (
								<button type="button" onClick={onGoToQuiz}>
									Ir a cuestionarios
								</button>
							)}
						</div>
					</section>
				</>
			)}
		</div>
	)
}
