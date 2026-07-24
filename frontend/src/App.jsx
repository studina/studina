import './App.css'
import { useState } from 'react'
import initialSubjects from './data/subjects'
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

function App() {
	const [user, setUser] = useState(null)
	const [subjects, setSubjects] = useState(initialSubjects)
	const [subjectFiles, setSubjectFiles] = useState({})
	const [selected, setSelected] = useState(initialSubjects[0].id)
	const [view, setView] = useState('home')
	const [openFile, setOpenFile] = useState(null)
	const [showNewSubject, setShowNewSubject] = useState(false)
	const [renamingSubject, setRenamingSubject] = useState(null)
	const [sidebarOpen, setSidebarOpen] = useState(false)

	const selectedSubject = subjects.find((s) => s.id === selected)
	const files = subjectFiles[selected] ?? []

	const selectSubject = (id) => {
		setSelected(id)
		setView('home')
		setOpenFile(null)
		setSidebarOpen(false)
	}

	// ── Crear nueva materia ──
	const handleCreateSubject = (name) => {
		const newId = Date.now()
		setSubjects((prev) => [...prev, { id: newId, name }])
		setSubjectFiles((prev) => ({ ...prev, [newId]: [] }))
		setShowNewSubject(false)
		selectSubject(newId)
	}

	// ── Renombrar materia ──
	const handleRenameSubject = (name) => {
		setSubjects((prev) =>
			prev.map((s) => (s.id === renamingSubject.id ? { ...s, name } : s))
		)
		setRenamingSubject(null)
	}

	// ── Actualizar conteo de archivos ──
	const handleFilesLoaded = (subjectId, loadedFiles) => {
		setSubjectFiles((prev) => ({ ...prev, [subjectId]: loadedFiles }))
	}

	return (
		<>
			{!user && <AuthPage onEnter={(u) => setUser(u)} />}
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
					<UserMenu
						user={user}
						onProfile={() => {
							setView('profile')
							setSidebarOpen(false)
						}}
						onLogout={() => setUser(null)}
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
							<FileViewer file={openFile} onClose={() => setView('docs')} />
						</>
					)}

					{view === 'dina' && selectedSubject && (
						<>
							<button className="btn-back" onClick={() => setView('home')}>← Volver</button>
							<ChatDina
								key={selectedSubject.id}
								subject={selectedSubject}
								files={files}
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
