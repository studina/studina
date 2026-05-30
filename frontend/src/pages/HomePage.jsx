import tools from '../data/tools'

export default function HomePage({ subject, filesCount, onGoToDocs, onGoToDina }) {
	return (
		<>
			<div className="main-header">
				<h1>{subject.name}</h1>
			</div>

			<div className="home-cards">
				<button className="home-card" onClick={onGoToDocs}>
					<span className="home-card-icon">📁</span>
					<div>
						<p className="home-card-title">Documentos</p>
						<p className="home-card-sub">{filesCount} archivos subidos</p>
					</div>
					<span className="home-card-arrow">→</span>
				</button>

				<button className="home-card dina-card" onClick={onGoToDina}>
					<span className="home-card-icon">🦕</span>
					<div>
						<p className="home-card-title">Hablá con Dina</p>
						<p className="home-card-sub">Tu asistente de estudio</p>
					</div>
					<span className="home-card-arrow">→</span>
				</button>
			</div>

			<h2 className="tools-title">Generar recursos con IA</h2>
			<div className="tools-grid">
				{tools.map((t) => (
					<button key={t.label} className={`tool-card ${t.color}`}>
						<span className="tool-icon">{t.icon}</span>
						<div>
							<p className="tool-label">{t.label}</p>
							<p className="tool-desc">{t.description}</p>
						</div>
					</button>
				))}
			</div>
		</>
	)
}
