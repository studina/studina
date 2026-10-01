import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import MasteryRing from '../components/MasteryRing'
import TopicModal from '../components/TopicModal'
import { openFileInNewTab } from '../lib/openFile'
import { apiFetch } from '../lib/session'
import { levelClass, levelLabel, percent } from '../lib/mastery'
import { timeAgo } from '../lib/timeAgo'
import '../styles/library.css'
import './ProgressPage.css'
import dinaThinking from '../assets/dina/dina-thinking.png'

/** Las columnas del tablero, en el orden en que se leen: primero lo que hay que atender. */
const columns = [
	{ level: 'weak', title: 'Necesitan repaso' },
	{ level: 'progressing', title: 'En progreso' },
	{ level: 'strong', title: 'Dominados' },
	{ level: null, title: 'Sin empezar' },
]

export default function ProgressPage({
	subject,
	user,
	onBack,
	onGoToFlashcards,
	onGoToQuiz,
	onGenerateForTopic,
	onChatAboutTopic,
	onOpenTopic,
	onCloseTopic,
}) {
	const { topicId: openTopicId } = useParams()
	const [state, setState] = useState({ status: 'loading', overview: null, error: '' })
	// El fallo al abrir un archivo es del click, no de la carga: se muestra en el mismo lugar
	// pero no tiene por qué borrar el progreso que ya está en pantalla.
	const [fileError, setFileError] = useState('')
	const [openMenuTopicId, setOpenMenuTopicId] = useState(null)
	// null con el modal cerrado; `{ topic: null }` para crear uno y `{ topic }` para editarlo.
	const [topicModal, setTopicModal] = useState(null)
	// Crear o renombrar un tema cambia el tablero —columnas, conteos, el orden por nombre—, así
	// que en vez de parchearlo a mano se vuelve a pedir el panorama.
	const [reloadKey, setReloadKey] = useState(0)
	// Los object URLs de los archivos abiertos: revocarlos al abrirlos rompería la pestaña,
	// así que se juntan acá y se sueltan al desmontar.
	const objectUrlsRef = useRef([])

	useEffect(() => () => {
		objectUrlsRef.current.forEach((url) => URL.revokeObjectURL(url))
		objectUrlsRef.current = []
	}, [])

	// Un solo menú abierto a la vez, y se cierra al clickear afuera.
	useEffect(() => {
		if (!openMenuTopicId) return undefined
		const close = () => setOpenMenuTopicId(null)
		document.addEventListener('mousedown', close)
		return () => document.removeEventListener('mousedown', close)
	}, [openMenuTopicId])

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
	}, [subject.id, user?.token, reloadKey])

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
	const topicsWithoutResources = topics.filter((topic) => !topic.total_resources).length
	const byMastery = (first, second) =>
		(first.mastery_score ?? 0) - (second.mastery_score ?? 0)
			|| String(first.topic_name).localeCompare(String(second.topic_name))

	// El tema destacado: el más flojo de los practicados. Uno sin practicar no compite —no hay
	// evidencia de que esté flojo— y si el peor ya está dominado no hay nada que destacar.
	const weakest = topics.filter((topic) => topic.mastery_score != null).sort(byMastery)[0]
	const focus = weakest && weakest.level !== 'strong' ? weakest : null

	// Un topicId que no está en los datos es un link viejo, no un error: se muestra el
	// tablero como si no se hubiera pedido ningún tema.
	const openTopic = openTopicId
		? topics.find((topic) => String(topic.topic_id) === openTopicId)
		: null

	const generateFor = (topic, kind) => {
		setOpenMenuTopicId(null)
		onGenerateForTopic?.(kind, {
			fileIds: (topic.files ?? []).map((file) => file.id),
			topicName: topic.topic_name,
		})
	}

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

	/**
	 * Generar un recurso acotado al tema: abre la pantalla de creación de siempre con los
	 * documentos de donde salió el tema ya tildados. Sin archivos no hay de dónde generar,
	 * así que el botón queda deshabilitado con el motivo a la vista.
	 */
	const renderGenerate = (topic) => {
		const hasFiles = Boolean(topic.files?.length)
		const isOpen = openMenuTopicId === topic.topic_id

		if (!hasFiles) {
			return (
				<p className="topic-generate-blocked">
					Sin documentos asociados: subí uno para poder generar recursos de este tema.
				</p>
			)
		}

		return (
			<div className="topic-generate" onMouseDown={(event) => event.stopPropagation()}>
				<button
					type="button"
					className="topic-generate-trigger"
					aria-expanded={isOpen}
					onClick={() => setOpenMenuTopicId(isOpen ? null : topic.topic_id)}
				>
					Generar recurso de este tema ▾
				</button>
				{isOpen && (
					<div className="topic-generate-menu" role="menu">
						<button type="button" role="menuitem" onClick={() => generateFor(topic, 'flashcards')}>
							🃏 Flashcards
						</button>
						<button type="button" role="menuitem" onClick={() => generateFor(topic, 'quiz')}>
							📝 Cuestionario
						</button>
					</div>
				)}
			</div>
		)
	}

	const renderChatAction = (topic) => (
		<button
			type="button"
			className="topic-chat-button"
			onClick={() =>
				onChatAboutTopic?.({
					fileIds: (topic.files ?? []).map((file) => file.id),
					// Queda escrito en el input, sin enviarse: el estudiante lo edita o lo
					// manda como está.
					prompt: `Quiero aprender más sobre "${topic.topic_name}". ¿Me explicás los conceptos principales?`,
				})
			}
		>
			💬 Hablá con Dina sobre este tema
		</button>
	)

	const handleTopicSaved = () => {
		setTopicModal(null)
		setReloadKey((key) => key + 1)
	}

	const renderNewTopicButton = () => (
		<button type="button" className="progress-new-topic" onClick={() => setTopicModal({ topic: null })}>
			+ Nuevo tema
		</button>
	)

	const renderTopicDetail = (topic) => (
		<section className="topic-detail">
			<button type="button" className="btn-back" onClick={onCloseTopic}>
				← Todos los temas
			</button>

			<header className="topic-detail-head">
				<h2>{topic.topic_name || 'Tema sin nombre'}</h2>
				<span className={`topic-chip ${levelClass(topic.level)}`}>
					{levelLabel(topic.level)}
				</span>
				<button
					type="button"
					className="topic-edit-button"
					onClick={() => setTopicModal({ topic })}
				>
					✏️ Editar
				</button>
			</header>

			{/* La descripción es opcional —los temas creados a mano pueden no tenerla—, así que
			    sin ella no se muestra nada. */}
			{topic.description && (
				<p className="topic-detail-description">{topic.description}</p>
			)}

			{topic.mastery_score == null ? (
				<p className="topic-detail-meta">
					Todavía no practicaste este tema.{' '}
					{topic.total_resources > 0
						? `Hay ${topic.total_resources} ${topic.total_resources === 1 ? 'recurso' : 'recursos'} para empezar.`
						: 'Generá flashcards o un cuestionario para empezar a medirlo.'}
				</p>
			) : (
				<>
					<div className="topic-bar-row topic-detail-bar">
						<div className="progress-bar">
							<div
								className="progress-fill"
								style={{ width: `${percent(topic.mastery_score)}%` }}
							/>
						</div>
						<span className="topic-percent">{percent(topic.mastery_score)}%</span>
					</div>
					<div className="topic-detail-stats">
						<div>
							<span>Recursos practicados</span>
							<strong>
								{topic.unique_resources_seen} / {topic.total_resources}
							</strong>
						</div>
						<div>
							<span>Último repaso</span>
							<strong>{timeAgo(topic.last_seen_at) ?? 'Nunca'}</strong>
						</div>
					</div>
				</>
			)}

			<div className="topic-detail-section">
				<h3>Material de este tema</h3>
				{topic.files?.length ? (
					renderFiles(topic)
				) : (
					<p className="topic-detail-meta">
						Ningún documento quedó asociado a este tema.
					</p>
				)}
			</div>

			<div className="topic-detail-actions">
				{renderGenerate(topic)}
				{onChatAboutTopic && renderChatAction(topic)}
			</div>
		</section>
	)

	const renderTopic = (topic) => (
		<li className="topic-card" key={topic.topic_id}>
			<button
				type="button"
				className="topic-name topic-name-link"
				onClick={() => onOpenTopic?.(topic.topic_id)}
			>
				{topic.topic_name || 'Tema sin nombre'}
			</button>

			{topic.mastery_score == null ? (
				// Sin barra a propósito: no es 0% de dominio, es que todavía no hay evidencia.
				<p className="topic-meta">
					{topic.total_resources > 0
						? `${topic.total_resources} ${topic.total_resources === 1 ? 'recurso disponible' : 'recursos disponibles'}`
						: 'Todavía no hay tarjetas ni preguntas'}
				</p>
			) : (
				<>
					<div className="topic-bar-row">
						<div className="progress-bar">
							<div
								className="progress-fill"
								style={{ width: `${percent(topic.mastery_score)}%` }}
							/>
						</div>
						<span className="topic-percent">{percent(topic.mastery_score)}%</span>
					</div>
					<p className="topic-meta">
						{topic.unique_resources_seen} de {topic.total_resources}{' '}
						{topic.total_resources === 1 ? 'recurso' : 'recursos'} · {topic.total_attempts}{' '}
						{topic.total_attempts === 1 ? 'intento' : 'intentos'}
					</p>
				</>
			)}
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
							Los temas se detectan solos cuando subís un documento, o podés crear uno a
							mano con los documentos que ya subiste. Después generá flashcards o un
							cuestionario para empezar a medir tu dominio.
						</p>
						{renderNewTopicButton()}
					</div>
				</section>
			)}

			{!loading && overview && topics.length > 0 && (
				<>
					{/* El detalle es la pantalla de un tema: las métricas de la materia y el
					    cierre con los accesos a la biblioteca serían ruido ahí. */}
					{!openTopic && (
					<section className="progress-summary">
						<div className="progress-summary-score">
							<MasteryRing value={overview.average_mastery} size={124} caption="dominio" />
							<p className="progress-summary-note">
								{overview.practiced_topics === 0
									? 'Todavía no practicaste ningún tema.'
									: `Promedio sobre ${
										overview.total_topics === 1 ? 'el tema' : `los ${overview.total_topics} temas`
									} de la materia.`}
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
							{/* Cuenta sólo las tarjetas y preguntas que existen: un tema sin recursos no
							    suma a ningún lado, así que la nota avisa cuántos quedan afuera para que
							    un "todo practicado" no se lea como la materia entera recorrida. */}
							<div className="progress-tile">
								<span>Recursos practicados</span>
								<strong>{overview.resources_seen} / {overview.total_resources}</strong>
								{topicsWithoutResources > 0 && (
									<p className="progress-tile-note">
										{topicsWithoutResources === 1
											? '1 tema todavía no tiene recursos'
											: `${topicsWithoutResources} temas todavía no tienen recursos`}
									</p>
								)}
							</div>
							<div className="progress-tile">
								<span>Último repaso</span>
								<strong>{timeAgo(overview.last_seen_at) ?? 'Nunca'}</strong>
							</div>
						</div>
					</section>
					)}

					{/* Con un tema abierto el destacado sobra: repetiría su nombre y sus acciones
					    justo arriba del detalle. */}
					{focus && !openTopic && (
						<section className="progress-focus" aria-labelledby="progress-focus-title">
							<div className="progress-focus-main">
								<p className="progress-focus-eyebrow" id="progress-focus-title">
									Empezá por acá
								</p>
								<h2>{focus.topic_name}</h2>
								<p className="progress-focus-lead">
									Es el tema que más repaso necesita: lo tenés al{' '}
									<strong>{percent(focus.mastery_score)}%</strong> de dominio sobre{' '}
									{focus.unique_resources_seen} de {focus.total_resources}{' '}
									{focus.total_resources === 1 ? 'recurso practicado' : 'recursos practicados'}.
								</p>
								<div className="topic-bar-row progress-focus-bar">
									<div className="progress-bar">
										<div
											className="progress-fill"
											style={{ width: `${percent(focus.mastery_score)}%` }}
										/>
									</div>
									<span className="topic-percent">{percent(focus.mastery_score)}%</span>
								</div>
								{renderFiles(focus)}
							</div>
							<div className="progress-focus-action">{renderGenerate(focus)}</div>
						</section>
					)}

					{openTopic ? renderTopicDetail(openTopic) : (
					<section className="progress-board-section">
						<header className="progress-board-head">
							<h2>Todos los temas</h2>
							{renderNewTopicButton()}
						</header>
						<div className="progress-board">
							{columns.map(({ level, title }) => {
								const columnTopics = topics
									.filter((topic) => (topic.level ?? null) === level)
									.sort(byMastery)
								return (
									<div className={`progress-column ${levelClass(level)}`} key={title}>
										<header className="progress-column-head">
											<span className="progress-column-title">{title}</span>
											<span className="progress-column-count">{columnTopics.length}</span>
										</header>
										{columnTopics.length === 0 ? (
											<p className="progress-column-empty">
												{level === null ? 'Arrancaste todos' : 'Ningún tema acá'}
											</p>
										) : (
											<ul className="topic-list">{columnTopics.map(renderTopic)}</ul>
										)}
									</div>
								)
							})}
						</div>
					</section>
					)}

					{!openTopic && (
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
					)}
				</>
			)}

			{topicModal && (
				<TopicModal
					subject={subject}
					topic={topicModal.topic}
					onSaved={handleTopicSaved}
					onCancel={() => setTopicModal(null)}
				/>
			)}
		</div>
	)
}
