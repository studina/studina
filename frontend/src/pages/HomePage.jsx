import tools from '../data/tools'
import ProgressWidget from '../components/ProgressWidget'
import './HomePage.css'
import dinaFullBody from '../assets/dina/dina-full-body.png'

export default function HomePage({ subject, user, onBack, onGoToDocs, onGoToDina, onGoToFlashcards, onGoToQuiz, onGoToStudyPlanner, onGoToPomodoro, onGoToProgress }) {
	return (
		<>
			{onBack && (
				<button className="btn-back" onClick={onBack}>
					← Mis materias
				</button>
			)}

			<div className="main-header">
				<h1>{subject.name}</h1>
			</div>

			<div className="home-layout">
				<div className="home-main-column">
					<div className="home-cards">
						<button className="home-card dina-card" onClick={onGoToDina}>
							<img className="home-card-dina" src={dinaFullBody} alt="" />
							<div>
								<p className="home-card-title">Hablá con Dina</p>
								<p className="home-card-sub">Tu asistente de estudio</p>
							</div>
							<span className="home-card-arrow">→</span>
						</button>
						<button className="home-card" onClick={onGoToDocs}>
							<span className="home-card-icon">📁</span>
							<div>
								<p className="home-card-title">Documentos</p>
								<p className="home-card-sub">Archivos subidos</p>
							</div>
							<span className="home-card-arrow">→</span>
						</button>
						<button
							className="home-card"
							onClick={onGoToStudyPlanner}
						>
							<span className="home-card-icon">📅</span>

							<div>
								<p className="home-card-title">
									Planificador de estudio
								</p>

								<p className="home-card-sub">
									Organizá tus temas antes del examen
								</p>
							</div>

							<span className="home-card-arrow">→</span>
						</button>
						<button
							className="home-card pomodoro-card"
							onClick={onGoToPomodoro}
						>
							<span className="home-card-icon">
								⏱️
							</span>

							<div>
								<p className="home-card-title">
									Pomodoro
								</p>

								<p className="home-card-sub">
									Concentrate en bloques de estudio y
									descanso
								</p>
							</div>

							<span className="home-card-arrow">
								→
							</span>
						</button>
					</div>

					<h2 className="tools-title">Generar recursos con IA</h2>
					<div className="tools-grid">
						{tools.map((t) => (
							<button
								key={t.label}
								className={`tool-card ${t.color}`}
								onClick={t.label === 'Flashcards' ? onGoToFlashcards : t.label === 'Cuestionario' ? onGoToQuiz : undefined}
							>
								<span className="tool-icon">{t.icon}</span>
								<div>
									<p className="tool-label">{t.label}</p>
									<p className="tool-desc">{t.description}</p>
								</div>
							</button>
						))}
					</div>
				</div>

				<aside className="home-side-column">
					<ProgressWidget
						key={subject.id}
						subject={subject}
						user={user}
						onOpen={onGoToProgress}
					/>
				</aside>
			</div>
		</>
	)
}
