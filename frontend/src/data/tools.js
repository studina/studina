// `kind` conecta cada card con la pantalla que abre; las que no tienen pantalla todavía no
// navegan a ningún lado.
const tools = [
	{ kind: 'flashcards', icon: '🃏', label: 'Flashcards', description: 'Tarjetas de repaso generadas desde tus apuntes.', color: 'tool-green' },
	{ kind: 'quiz', icon: '📝', label: 'Cuestionario', description: 'Preguntas y respuestas para autoevaluarte.', color: 'tool-teal' },
	{ kind: 'trueFalse', icon: '✅', label: 'Verdadero o falso', description: 'Afirmaciones para decidir si son ciertas o no.', color: 'tool-mint' },
	{ kind: 'openQuestions', icon: '✍️', label: 'Preguntas abiertas', description: 'Respondé con tus palabras y Dina corrige tu respuesta.', color: 'tool-rose' },
	{ kind: 'exam', icon: '🎯', label: 'Simulacro de examen', description: 'Practicá con un parcial generado a partir de tus materiales.', color: 'tool-sage' },
]

export default tools
