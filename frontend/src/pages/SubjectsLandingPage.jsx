import './SubjectsLandingPage.css'
import SubjectMenu from '../components/SubjectMenu'

export default function SubjectsLandingPage({
	user,
	subjects = [],
	onSelectSubject,
	onAddSubject,
	onRenameSubject,
	onDeleteSubject,
	onGoToPomodoro,
}) {
	const firstName = user?.name ? user.name.split(' ')[0] : 'Estudiante'

	return (
		<div className="landing-container">
			<header className="landing-header">
				<div className="landing-welcome">
					<p className="landing-eyebrow">Tu espacio de estudio</p>
					<h1>¡Hola, {firstName}! 👋</h1>
					<p className="landing-sub">
						Seleccioná una materia para ver sus documentos, flashcards y estudiar con Dina, o creá una nueva para empezar.
					</p>
				</div>
				<button className="landing-btn-add" onClick={onAddSubject}>
					＋ Nueva materia
				</button>
			</header>

			<section className="landing-section">
				<div className="landing-section-header">
					<h2>Mis materias <span className="landing-badge">{subjects.length}</span></h2>
				</div>

				<div className="landing-subjects-grid">
					{subjects.map((s) => (
						<div
							key={s.id}
							className="subject-card"
							onClick={() => onSelectSubject(s.id)}
							role="button"
							tabIndex={0}
							onKeyDown={(e) => {
								if (e.key === 'Enter' || e.key === ' ') {
									e.preventDefault()
									onSelectSubject(s.id)
								}
							}}
						>
							<div className="subject-card-top">
								<div className="subject-card-icon">📚</div>
								<div className="subject-card-menu" onClick={(e) => e.stopPropagation()}>
									<SubjectMenu
										onRename={() => onRenameSubject(s)}
										onDelete={() => onDeleteSubject(s)}
									/>
								</div>
							</div>

							<div className="subject-card-body">
								<h3 className="subject-card-title">{s.name}</h3>
							</div>

							<div className="subject-card-footer">
								<span className="subject-card-link">Entrar a la materia</span>
								<span className="subject-card-arrow">→</span>
							</div>
						</div>
					))}

					<button
						type="button"
						className="subject-card subject-card-new"
						onClick={onAddSubject}
					>
						<span className="subject-card-new-icon">＋</span>
						<span className="subject-card-new-text">Agregar otra materia</span>
					</button>
				</div>
			</section>

			<section className="landing-section">
				<div className="landing-section-header">
					<h2>Herramientas rápidas</h2>
				</div>

				<div className="landing-tools-grid">
					<button
						type="button"
						className="home-card pomodoro-card"
						onClick={onGoToPomodoro}
					>
						<span className="home-card-icon">⏱️</span>
						<div>
							<p className="home-card-title">Temporizador Pomodoro</p>
							<p className="home-card-sub">
								Concentrate en bloques de estudio y descansos
							</p>
						</div>
						<span className="home-card-arrow">→</span>
					</button>
				</div>
			</section>
		</div>
	)
}
