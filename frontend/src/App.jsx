import './App.css'
import { useState } from 'react'
import subjects from './data/subjects'
import subjectFiles from './data/subjectFiles'
import HomePage from './pages/HomePage'
import DocumentsPage from './pages/DocumentsPage'
import FileViewer from './components/FileViewer'
import ChatDina from './components/ChatDina'

function App() {
	const [selected, setSelected] = useState(subjects[0].id)
	const [view, setView] = useState('home') // 'home' | 'docs' | 'dina' | 'file'
	const [openFile, setOpenFile] = useState(null)

	const selectedSubject = subjects.find((s) => s.id === selected)
	const files = subjectFiles[selected] ?? []

	const selectSubject = (id) => {
		setSelected(id)
		setView('home')
		setOpenFile(null)
	}

	return (
		<div className="app">
			<nav className="navbar">
				<span className="navbar-logo">studina</span>
				<div className="navbar-links">
					<span className="nav-user">👤 Mi cuenta</span>
				</div>
			</nav>

			<div className="layout">
				<aside className="sidebar">
					<div className="sidebar-header">
						<span>Mis materias</span>
						<button className="btn-add" title="Nueva materia">＋</button>
					</div>
					<ul className="subject-list">
						{subjects.map((s) => (
							<li
								key={s.id}
								className={`subject-item ${selected === s.id ? 'active' : ''}`}
								onClick={() => selectSubject(s.id)}
							>
								<span className="subject-name">{s.name}</span>
								<span className="subject-count">{(subjectFiles[s.id] ?? []).length}</span>
							</li>
						))}
					</ul>
				</aside>

				<main className="main">
					{view === 'home' && (
						<HomePage
							subject={selectedSubject}
							filesCount={files.length}
							onGoToDocs={() => setView('docs')}
							onGoToDina={() => setView('dina')}
						/>
					)}

					{view === 'docs' && (
						<DocumentsPage
							subject={selectedSubject}
							filesData={files}
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

					{view === 'dina' && (
						<>
							<button className="btn-back" onClick={() => setView('home')}>← Volver</button>
							<ChatDina subject={selectedSubject} />
						</>
					)}
				</main>
			</div>
		</div>
	)
}

export default App
