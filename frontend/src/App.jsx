import './App.css'
import { useCallback, useEffect, useState } from 'react'
import { BrowserRouter, useLocation, useNavigate } from 'react-router-dom'
import AuthPage from './pages/AuthPage'
import HomePage from './pages/HomePage'
import DocumentsPage from './pages/DocumentsPage'
import FlashcardsPage from './pages/FlashcardsPage'
import FileViewer from './components/FileViewer'
import ChatDina from './components/ChatDina'
import SubjectModal from './components/SubjectModal'
import SubjectMenu from './components/SubjectMenu'
import UserMenu from './components/UserMenu'
import dinaAvatar from './assets/dina/dina-avatar.png'
import dinaFullBody from './assets/dina/dina-full-body.png'
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

const viewPaths = {
	home: '/',
	profile: '/perfil',
	docs: '/documentos',
	flashcards: '/flashcards',
	'study-planner': '/plan-estudio',
	pomodoro: '/pomodoro',
	dina: '/dina',
	file: '/documentos/archivo',
}

function viewForPath(pathname) {
	return Object.entries(viewPaths).find(([, path]) => path === pathname)?.[0] ?? 'home'
}

function AppContent() {
	const navigate = useNavigate()
	const { pathname } = useLocation()
	const [user, setUser] = useState(null)
	const [subjects, setSubjects] = useState([])
	const [subjectFiles, setSubjectFiles] = useState({})
	const [selected, setSelected] = useState(null)
	const [openFile, setOpenFile] = useState(null)
	const [showNewSubject, setShowNewSubject] = useState(false)
	const [renamingSubject, setRenamingSubject] = useState(null)
	const [sidebarOpen, setSidebarOpen] = useState(false)
	const view = viewForPath(pathname)

	const setView = (nextView) => {
		navigate(viewPaths[nextView])
	}

	useEffect(() => {
		if (!user?.token) return undefined

		let active = true
		fetch('/api/subjects', {
			headers: { Authorization: `Bearer ${user.token}` },
		})
			.then(async (response) => {
				const body = await response.json().catch(() => ({}))
				if (!response.ok) throw new Error(body.error || 'No se pudieron cargar las materias')
				return Array.isArray(body) ? body : []
			})
			.then((loadedSubjects) => {
				if (!active) return
				setSubjects(loadedSubjects)
				setSelected(loadedSubjects[0]?.id ?? null)
			})
			.catch((error) => console.error('Error cargando materias:', error))

		return () => {
			active = false
		}
	}, [user])

	const resetSessionView = () => {
		setSubjectFiles({})
		setSubjects([])
		setSelected(null)
		setView('home')
		setOpenFile(null)
		setShowNewSubject(false)
		setRenamingSubject(null)
		setSidebarOpen(false)
	}

	const handleEnter = (nextUser) => {
		resetSessionView()
		setUser(nextUser)
	}

	const handleLogout = () => {
		resetSessionView()
		setUser(null)
	}

	const selectedSubject = subjects.find((s) => s.id === selected)
	const files = subjectFiles[selected] ?? []

	const selectSubject = (id) => {
		setSelected(id)
		setView('home')
		setOpenFile(null)
		setSidebarOpen(false)
	}

	// ── Crear nueva materia ──
	const handleCreateSubject = async (name) => {
		if (!user?.token) return

		try {
			const response = await fetch('/api/subjects', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					Authorization: `Bearer ${user.token}`,
				},
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
	const handleRenameSubject = (name) => {
		setSubjects((prev) =>
			prev.map((s) => (s.id === renamingSubject.id ? { ...s, name } : s))
		)
		setRenamingSubject(null)
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
			[selected]: (prev[selected] ?? []).map((file) =>
				file.id === updatedFile.id ? updatedFile : file
			),
		}))
	}

	return (
		<>
				{!user && <AuthPage onEnter={handleEnter} />}
			{user && (
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
					<button className="navbar-brand" onClick={() => setView('home')}>
						<img src={dinaAvatar} alt="" />
						<span className="navbar-logo">studina</span>
					</button>
				</div>
					<div className="navbar-links">
						<PomodoroMiniTimer
							onOpen={() => {
								setView('pomodoro')
								setSidebarOpen(false)
							}}
						/>

						<UserMenu
							user={user}
							onProfile={() => {
								setView('profile')
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
								className={`subject-item ${selected === s.id ? 'active' : ''}`}
								onClick={() => selectSubject(s.id)}
							>
								<span className="subject-name">{s.name}</span>
								<div className="subject-item-right">
									<span className="subject-count">{(subjectFiles[s.id] ?? []).length}</span>
									<SubjectMenu onRename={() => setRenamingSubject(s)} />
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
					{view === 'profile' && (
						<ProfilePage
							user={user}
							onBack={() => setView('home')}
						/>
					)}
					{view === 'home' && selectedSubject && (
						<HomePage
							subject={selectedSubject}
							filesCount={files.length}
							onGoToDocs={() => setView('docs')}
							onGoToFlashcards={() => setView('flashcards')}
							onGoToDina={() => setView('dina')}
							onGoToStudyPlanner={() => setView('study-planner')}
							onGoToPomodoro={() => setView('pomodoro')}
						/>
					)}

					{view === 'flashcards' && selectedSubject && (
						<FlashcardsPage
							subject={selectedSubject}
							onBack={() => setView('home')}
						/>
					)}

					{view === "study-planner" && (
					<StudyPlanner
						subject={selectedSubject}
						onBack={() => setView("home")}
					/>
					)}

					{view === 'pomodoro' && selectedSubject && (
					<PomodoroPage
						subject={selectedSubject}
						onBack={() => setView('home')}
					/>
					)}

					{view === 'docs' && selectedSubject && (
						<DocumentsPage
							subject={selectedSubject}
							user={user}
							onFilesLoaded={handleFilesLoaded}
							onBack={() => setView('home')}
							onOpenFile={(f) => { setOpenFile(f); setView('file') }}
						/>
					)}

					{view === 'file' && openFile && (
						<>
							<button className="btn-back" onClick={() => setView('docs')}>← Volver a documentos</button>
								<FileViewer
									file={openFile}
									token={user.token}
									onClose={() => setView('docs')}
									onSave={handleFileContentSave}
								/>
						</>
					)}

					{view === 'dina' && selectedSubject && (
						<>
							<button className="btn-back" onClick={() => setView('home')}>← Volver</button>
							<ChatDina
								key={selectedSubject.id}
									subject={selectedSubject}
									files={files}
									user={user}
								/>
						</>
					)}
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
		</div>
			)}
		</>
	)
}

export default App
