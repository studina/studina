import './App.css'
import './styles/library.css'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
	BrowserRouter,
	Navigate,
	Route,
	Routes,
	useMatch,
	useNavigate,
	useParams,
} from 'react-router-dom'
import { randomUuid } from './lib/uuid'
import { apiFetch, clearSession, onSessionExpired, restoreSession, setSession } from './lib/session'
import AuthPage from './pages/AuthPage'
import HomePage from './pages/HomePage'
import SubjectsLandingPage from './pages/SubjectsLandingPage'
import DocumentsPage from './pages/DocumentsPage'
import FlashcardsPage from './pages/FlashcardsPage'
import QuizPage from './pages/QuizPage'
import ProgressPage from './pages/ProgressPage'
import FileViewer from './components/FileViewer'
import ChatDina from './components/ChatDina'
import DeleteSubjectModal from './components/DeleteSubjectModal'
import SubjectModal from './components/SubjectModal'
import SubjectMenu from './components/SubjectMenu'
import UserMenu from './components/UserMenu'
import dinaAvatar from './assets/dina/dina-avatar.png'
import dinaFullBody from './assets/dina/dina-full-body.png'
import dinaThinking from './assets/dina/dina-thinking.png'
import StudyPlanner from './pages/study-planner/StudyPlanner'
import ProfilePage from './pages/profile/ProfilePage'
import PomodoroPage from './pages/pomodoro/PomodoroPage'
import PomodoroMiniTimer from './pages/pomodoro/PomodoroMiniTimer'

function App() {
	return (
		<BrowserRouter>
			<AppContent />
		</BrowserRouter>
	)
}

/** La raíz de todas las pantallas de una materia. */
const subjectPath = (subjectId, section = '') =>
	`/materias/${encodeURIComponent(String(subjectId))}${section}`

/**
 * Resuelve la materia de la URL antes de montar la pantalla.
 *
 * Las materias se cargan asincrónicamente, así que en el primer render de `/materias/3` la
 * lista está vacía y todavía no se sabe si esa materia existe. Sin este paso intermedio,
 * abrir un link directo redirigiría al inicio justo antes de que lleguen los datos.
 */
function SubjectRoute({ subjects, subjectsLoading, render }) {
	const { subjectId } = useParams()
	const subject = subjects.find((candidate) => String(candidate.id) === subjectId)

	if (subjectsLoading) {
		return (
			<div className="page-loading">
				<span className="loader loader--lg" />
				<p>Cargando tu materia…</p>
			</div>
		)
	}

	if (!subject) return <Navigate to="/" replace />

	return render(subject)
}

function AppContent() {
	const navigate = useNavigate()
	const [user, setUser] = useState(null)
	// Arranca en true para no mostrar el login encima de una sesión que quizás siga viva:
	// la cookie se comprueba antes de decidir qué pantalla corresponde.
	const [restoringSession, setRestoringSession] = useState(true)
	const [subjects, setSubjects] = useState([])
	// Arranca en true y sólo baja cuando la lista llegó de verdad. Si empezara en false,
	// el render que va entre setUser y el efecto que las pide tendría la lista vacía y
	// SubjectRoute mandaría al inicio, rompiendo cualquier link directo.
	const [subjectsLoading, setSubjectsLoading] = useState(true)
	const [subjectFiles, setSubjectFiles] = useState({})
	const [openFile, setOpenFile] = useState(null)
	const [showNewSubject, setShowNewSubject] = useState(false)
	const [renamingSubject, setRenamingSubject] = useState(null)
	const [subjectPendingDeletion, setSubjectPendingDeletion] = useState(null)
	const [sidebarOpen, setSidebarOpen] = useState(false)
	// Un pedido de generación acotado a un tema, disparado desde la pantalla de progreso.
	// El token lo usa la key de la página destino: cada pedido la remonta, así que el
	// formulario arranca de nuevo con los documentos de ese tema tildados.
	const [generationRequest, setGenerationRequest] = useState(null)
	// Lo mismo para el chat: abrirlo desde un tema lo arranca con sus documentos tildados y
	// la pregunta ya escrita.
	const [chatRequest, setChatRequest] = useState(null)

	// Qué materia está abierta, según la URL y no según un estado aparte.
	const exactSubject = useMatch('/materias/:subjectId')
	const nestedSubject = useMatch('/materias/:subjectId/*')
	const activeSubjectId =
		exactSubject?.params.subjectId ?? nestedSubject?.params.subjectId ?? null

	const goTo = (subjectId, section = '') => navigate(subjectPath(subjectId, section))

	// Navegar "a mano" a una pantalla de generación descarta el pedido anterior; si no, volver
	// a flashcards desde el home reabriría el formulario del último tema.
	const goToGenerator = (subjectId, section) => {
		setGenerationRequest(null)
		goTo(subjectId, section)
	}

	const generateForTopic = (kind, request) => {
		setGenerationRequest({ ...request, token: randomUuid() })
		goTo(activeSubjectId, kind === 'quiz' ? '/cuestionarios' : '/flashcards')
	}

	const chatAboutTopic = (request) => {
		setChatRequest({ ...request, token: randomUuid() })
		goTo(activeSubjectId, '/dina')
	}

	// Entrar al chat desde cualquier otro lado descarta el pedido anterior; si no, volvería a
	// abrirse con el tema de la última vez.
	const goToChat = (subjectId) => {
		setChatRequest(null)
		goTo(subjectId, '/dina')
	}

	// La sesión se recupera de la cookie antes que nada: si sigue viva, recargar cae en la
	// misma pantalla en vez de volver al login.
	useEffect(() => {
		let active = true
		restoreSession()
			.then((restoredUser) => {
				if (active && restoredUser) setUser(restoredUser)
			})
			.catch((error) => console.warn('No se pudo recuperar la sesión:', error.message))
			.finally(() => {
				if (active) setRestoringSession(false)
			})

		return () => {
			active = false
		}
	}, [])

	useEffect(() => {
		if (!user?.token) {
			// Sin usuario no hay lista que mostrar, pero tampoco está "cargada": dejarla en
			// false acá es lo que provocaba el redirect al inicio apenas se inicia sesión.
			return undefined
		}

		let active = true
		setSubjectsLoading(true)
		apiFetch('/api/subjects')
			.then(async (response) => {
				const body = await response.json().catch(() => ({}))
				if (!response.ok) throw new Error(body.error || 'No se pudieron cargar las materias')
				return Array.isArray(body) ? body : []
			})
			.then((loadedSubjects) => {
				if (!active) return
				setSubjects(loadedSubjects)
			})
			.catch((error) => console.error('Error cargando materias:', error))
			.finally(() => {
				if (active) setSubjectsLoading(false)
			})

		return () => {
			active = false
		}
	}, [user])

	const resetSessionView = () => {
		setSubjectFiles({})
		setSubjects([])
		setSubjectsLoading(true)
		setOpenFile(null)
		setShowNewSubject(false)
		setRenamingSubject(null)
		setSubjectPendingDeletion(null)
		setSidebarOpen(false)
		setGenerationRequest(null)
		setChatRequest(null)
	}

	// No navega a ningún lado a propósito: la URL que el usuario pidió sigue en la barra, así
	// que entrar lo deja en esa pantalla en vez de mandarlo al inicio.
	const handleEnter = (nextUser) => {
		resetSessionView()
		setSession(nextUser)
		setUser(nextUser)
	}

	const handleLogout = () => {
		resetSessionView()
		clearSession()
		setUser(null)
		navigate('/')
	}

	// La sesión se puede terminar sola: el refresh token también vence. Cuando pasa no
	// queda nada renovable, así que se vuelve al login en vez de dejar la pantalla
	// fallando request tras request. Va por ref para suscribirse una sola vez sin
	// quedarse con el handler del primer render.
	const logoutRef = useRef(handleLogout)
	useEffect(() => {
		logoutRef.current = handleLogout
	})
	useEffect(() => onSessionExpired(() => logoutRef.current()), [])

	const files = subjectFiles[activeSubjectId] ?? []

	const selectSubject = (id) => {
		setOpenFile(null)
		setSidebarOpen(false)
		goTo(id)
	}

	const goToSubjectsLanding = () => {
		setOpenFile(null)
		setSidebarOpen(false)
		navigate('/')
	}

	// ── Crear nueva materia ──
	const handleCreateSubject = async (name) => {
		if (!user?.token) return

		try {
			const response = await apiFetch('/api/subjects', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ name }),
			})
			const createdSubject = await response.json().catch(() => ({}))
			if (!response.ok) throw new Error(createdSubject.error || 'No se pudo crear la materia')

			setSubjects((previousSubjects) => [...previousSubjects, createdSubject])
			setSubjectFiles((previousFiles) => ({ ...previousFiles, [createdSubject.id]: [] }))
			setShowNewSubject(false)
			selectSubject(createdSubject.id)
		} catch (error) {
			console.error('Error creando materia:', error)
		}
	}

	// ── Renombrar materia ──
	const handleRenameSubject = async (name) => {
		if (!user?.token || !renamingSubject) return

		try {
			const response = await apiFetch(`/api/subjects/${renamingSubject.id}`, {
				method: 'PATCH',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ name }),
			})
			const updatedSubject = await response.json().catch(() => ({}))
			if (!response.ok) throw new Error(updatedSubject.error || 'No se pudo renombrar la materia')

			setSubjects((previousSubjects) =>
				previousSubjects.map((subject) =>
					subject.id === updatedSubject.id ? updatedSubject : subject
				)
			)
			setRenamingSubject(null)
		} catch (error) {
			console.error('Error renombrando materia:', error)
		}
	}

	const handleDeleteSubject = async (subject) => {
		if (!user?.token) return

		try {
			const response = await apiFetch(`/api/subjects/${subject.id}`, {
				method: 'DELETE',
			})
			if (!response.ok) {
				const body = await response.json().catch(() => ({}))
				throw new Error(body.error || 'No se pudo eliminar la materia')
			}

			setSubjects((previousSubjects) =>
				previousSubjects.filter((currentSubject) => currentSubject.id !== subject.id)
			)
			setSubjectFiles((previousFiles) =>
				Object.fromEntries(
					Object.entries(previousFiles).filter(
						([subjectId]) => subjectId !== String(subject.id)
					)
				)
			)
			setOpenFile(null)
			setSubjectPendingDeletion(null)
			// Si la materia borrada era la que estaba abierta, su URL ya no lleva a ningún lado.
			if (String(subject.id) === activeSubjectId) navigate('/')
		} catch (error) {
			console.error('Error eliminando materia:', error)
		}
	}

	// ── Actualizar conteo de archivos ──
	const handleFilesLoaded = useCallback((subjectId, loadedFiles) => {
		setSubjectFiles((prev) => ({ ...prev, [subjectId]: loadedFiles }))
	}, [])

	const handleFileContentSave = (content) => {
		if (!openFile) return

		const updatedFile = { ...openFile, content }
		setOpenFile(updatedFile)
		setSubjectFiles((prev) => ({
			...prev,
			[activeSubjectId]: (prev[activeSubjectId] ?? []).map((file) =>
				file.id === updatedFile.id ? updatedFile : file
			),
		}))
	}

	/** Atajo para no repetir los props del resolvedor en cada ruta de materia. */
	const forSubject = (render) => (
		<SubjectRoute subjects={subjects} subjectsLoading={subjectsLoading} render={render} />
	)

	if (restoringSession) {
		return (
			<div className="page-loading page-loading--full">
				<span className="loader loader--lg" />
				<p>Cargando tu sesión…</p>
			</div>
		)
	}

	if (!user) return <AuthPage onEnter={handleEnter} />

	return (
		<div className="app">
			<nav className="navbar">
				<div className="navbar-left">
					<button
						className={`hamburger ${sidebarOpen ? 'open' : ''}`}
						onClick={() => setSidebarOpen((o) => !o)}
						aria-label="Toggle menu"
					>
						<span /><span /><span />
					</button>
					<button className="navbar-brand" onClick={goToSubjectsLanding}>
						<img src={dinaAvatar} alt="" />
						<span className="navbar-logo">studina</span>
					</button>
				</div>
				<div className="navbar-links">
					<PomodoroMiniTimer
						onOpen={() => {
							navigate('/pomodoro')
							setSidebarOpen(false)
						}}
					/>

					<UserMenu
						user={user}
						onProfile={() => {
							navigate('/perfil')
							setSidebarOpen(false)
						}}
						onLogout={handleLogout}
					/>
				</div>
			</nav>

			{sidebarOpen && (
				<div className="sidebar-overlay" onClick={() => setSidebarOpen(false)} />
			)}

			<div className="layout">
				<aside className={`sidebar ${sidebarOpen ? 'sidebar-open' : ''}`}>
					<div className="sidebar-header">
						<span>Mis materias</span>
						<button
							className="btn-add"
							title="Nueva materia"
							onClick={() => setShowNewSubject(true)}
						>＋</button>
					</div>
					<ul className="subject-list">
						{subjects.map((s) => (
							<li
								key={s.id}
								className={`subject-item ${String(s.id) === activeSubjectId ? 'active' : ''}`}
								onClick={() => selectSubject(s.id)}
							>
								<span className="subject-name">{s.name}</span>
								<div className="subject-item-right">
									<span className="subject-count">{(subjectFiles[s.id] ?? []).length}</span>
									<SubjectMenu
										onRename={() => setRenamingSubject(s)}
										onDelete={() => setSubjectPendingDeletion(s)}
									/>
								</div>
							</li>
						))}
					</ul>
					<div className="sidebar-dina" aria-hidden="true">
						<img src={dinaFullBody} alt="" />
						<p>Aprendamos algo nuevo</p>
					</div>
				</aside>

				<main className="main">
					<Routes>
						<Route
							path="/"
							element={
								<SubjectsHome
									user={user}
									subjects={subjects}
									subjectsLoading={subjectsLoading}
									onSelectSubject={selectSubject}
									onAddSubject={() => setShowNewSubject(true)}
									onRenameSubject={setRenamingSubject}
									onDeleteSubject={setSubjectPendingDeletion}
									onGoToPomodoro={() => navigate('/pomodoro')}
								/>
							}
						/>

						<Route
							path="/perfil"
							element={<ProfilePage user={user} onBack={() => navigate('/')} />}
						/>

						{/* Sin materia: se entra desde el mini timer del navbar y desde el estado vacío. */}
						<Route
							path="/pomodoro"
							element={<PomodoroPage subject={null} onBack={() => navigate('/')} />}
						/>

						{/* Con materia, para no perder su nombre al entrar desde el home. */}
						<Route
							path="/materias/:subjectId/pomodoro"
							element={forSubject((subject) => (
								<PomodoroPage subject={subject} onBack={() => goTo(subject.id)} />
							))}
						/>

						<Route
							path="/materias/:subjectId"
							element={forSubject((subject) => (
								<HomePage
									subject={subject}
									user={user}
									onBack={goToSubjectsLanding}
									onGoToDocs={() => goTo(subject.id, '/documentos')}
									onGoToFlashcards={() => goToGenerator(subject.id, '/flashcards')}
									onGoToQuiz={() => goToGenerator(subject.id, '/cuestionarios')}
									onGoToDina={() => goToChat(subject.id)}
									onGoToStudyPlanner={() => goTo(subject.id, '/plan-estudio')}
									onGoToPomodoro={() => goTo(subject.id, '/pomodoro')}
									onGoToProgress={() => goTo(subject.id, '/progreso')}
								/>
							))}
						/>

						<Route
							path="/materias/:subjectId/documentos"
							element={forSubject((subject) => (
								<DocumentsPage
									subject={subject}
									user={user}
									onFilesLoaded={handleFilesLoaded}
									onBack={() => goTo(subject.id)}
									onOpenFile={(f) => {
										setOpenFile(f)
										goTo(subject.id, '/documentos/archivo')
									}}
								/>
							))}
						/>

						<Route
							path="/materias/:subjectId/documentos/archivo"
							element={forSubject((subject) =>
								// El archivo abierto vive en memoria: entrar directo a esta URL no
								// tiene nada que mostrar, así que vuelve al listado.
								openFile ? (
									<>
										<button
											className="btn-back"
											onClick={() => goTo(subject.id, '/documentos')}
										>
											← Volver a documentos
										</button>
										<FileViewer
											file={openFile}
											token={user.token}
											onClose={() => goTo(subject.id, '/documentos')}
											onSave={handleFileContentSave}
										/>
									</>
								) : (
									<Navigate to={subjectPath(subject.id, '/documentos')} replace />
								)
							)}
						/>

						<Route
							path="/materias/:subjectId/flashcards"
							element={forSubject((subject) => (
								<FlashcardsPage
									key={generationRequest?.token ?? 'flashcards'}
									subject={subject}
									user={user}
									onBack={() => goTo(subject.id)}
									initialGeneration={generationRequest}
								/>
							))}
						/>

						<Route
							path="/materias/:subjectId/cuestionarios"
							element={forSubject((subject) => (
								<QuizPage
									key={generationRequest?.token ?? 'quiz'}
									subject={subject}
									user={user}
									onBack={() => goTo(subject.id)}
									initialGeneration={generationRequest}
								/>
							))}
						/>

						{/* La misma pantalla con y sin tema: el detalle es una vista de sí misma,
						    así que volver al tablero no vuelve a pedir el progreso. */}
						{['/materias/:subjectId/progreso', '/materias/:subjectId/progreso/:topicId'].map(
							(path) => (
								<Route
									key={path}
									path={path}
									element={forSubject((subject) => (
										<ProgressPage
											subject={subject}
											user={user}
											onBack={() => goTo(subject.id)}
											onGoToFlashcards={() => goToGenerator(subject.id, '/flashcards')}
											onGoToQuiz={() => goToGenerator(subject.id, '/cuestionarios')}
											onGenerateForTopic={generateForTopic}
											onChatAboutTopic={chatAboutTopic}
											onOpenTopic={(topicId) =>
												goTo(subject.id, `/progreso/${encodeURIComponent(topicId)}`)
											}
											onCloseTopic={() => goTo(subject.id, '/progreso')}
										/>
									))}
								/>
							)
						)}

						<Route
							path="/materias/:subjectId/plan-estudio"
							element={forSubject((subject) => (
								<StudyPlanner subject={subject} onBack={() => goTo(subject.id)} />
							))}
						/>

						<Route
							path="/materias/:subjectId/dina"
							element={forSubject((subject) => (
								<>
									<button className="btn-back" onClick={() => goTo(subject.id)}>
										← Volver
									</button>
									<ChatDina
										key={chatRequest?.token ?? subject.id}
										subject={subject}
										files={files}
										user={user}
										initialPrompt={chatRequest?.prompt}
										initialFileIds={chatRequest?.fileIds}
									/>
								</>
							))}
						/>

						<Route path="*" element={<Navigate to="/" replace />} />
					</Routes>
				</main>
			</div>

			{showNewSubject && (
				<SubjectModal
					onSave={handleCreateSubject}
					onCancel={() => setShowNewSubject(false)}
				/>
			)}

			{renamingSubject && (
				<SubjectModal
					initialName={renamingSubject.name}
					onSave={handleRenameSubject}
					onCancel={() => setRenamingSubject(null)}
				/>
			)}

			{subjectPendingDeletion && (
				<DeleteSubjectModal
					subject={subjectPendingDeletion}
					onCancel={() => setSubjectPendingDeletion(null)}
					onConfirm={() => handleDeleteSubject(subjectPendingDeletion)}
				/>
			)}
		</div>
	)
}

/** La pantalla de inicio: el listado de materias, o la invitación a crear la primera. */
function SubjectsHome({
	user,
	subjects,
	subjectsLoading,
	onSelectSubject,
	onAddSubject,
	onRenameSubject,
	onDeleteSubject,
	onGoToPomodoro,
}) {
	if (subjectsLoading) {
		return (
			<div className="page-loading">
				<span className="loader loader--lg" />
				<p>Cargando tus materias…</p>
			</div>
		)
	}

	if (subjects.length > 0) {
		return (
			<SubjectsLandingPage
				user={user}
				subjects={subjects}
				onSelectSubject={onSelectSubject}
				onAddSubject={onAddSubject}
				onRenameSubject={onRenameSubject}
				onDeleteSubject={onDeleteSubject}
				onGoToPomodoro={onGoToPomodoro}
			/>
		)
	}

	return (
		<section className="subjects-empty-state">
			<div className="subjects-empty-copy">
				<p className="subjects-empty-eyebrow">Empezá a organizarte</p>
				<h1>Creá tu primera materia</h1>
				<p>
					Agregá una materia para guardar tus apuntes, crear planes de estudio
					y repasar con Dina.
				</p>
				<div className="subjects-empty-actions">
					<button
						type="button"
						className="subjects-empty-create"
						onClick={onAddSubject}
					>
						Agregar materia
					</button>
				</div>
				<button
					type="button"
					className="home-card pomodoro-card subjects-empty-pomodoro"
					onClick={onGoToPomodoro}
				>
					<span className="home-card-icon">⏱️</span>
					<div>
						<p className="home-card-title">Pomodoro</p>
						<p className="home-card-sub">
							Concentrate en bloques de estudio y descanso
						</p>
					</div>
					<span className="home-card-arrow">→</span>
				</button>
			</div>
			<img
				className="subjects-empty-image"
				src={dinaThinking}
				alt="Dina lista para ayudarte a estudiar"
			/>
		</section>
	)
}

export default App
