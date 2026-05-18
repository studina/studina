import './App.css'
import { useState, useRef, useEffect } from 'react'

const subjectFiles = {
	1: [
		{
			id: 'f1',
			name: 'Apunte Ordenamiento.txt',
			type: 'txt',
			content: `# Algoritmos de Ordenamiento

## Bubble Sort
Compara pares adyacentes e intercambia si están en orden incorrecto.
- Complejidad: O(n²) en el peor caso
- Estable: Sí

## Merge Sort
Divide el arreglo en mitades, ordena cada mitad y las fusiona.
- Complejidad: O(n log n) en todos los casos
- Estable: Sí
- Requiere espacio adicional O(n)

## Quick Sort
Elige un pivote y particiona el arreglo en elementos menores y mayores.
- Complejidad promedio: O(n log n)
- Peor caso: O(n²)
- In-place: Sí (generalmente)

## Heap Sort
Usa una estructura de heap para ordenar.
- Complejidad: O(n log n)
- In-place: Sí
- Estable: No
`,
		},
		{
			id: 'f2',
			name: 'Examen 2023.pdf',
			type: 'pdf',
			content: null,
			url: '/Examen2023.pdf',
		},
		{
			id: 'f2b',
			name: 'Examen 2023 (2da oportunidad).pdf',
			type: 'pdf',
			content: null,
			url: '/Examen2023-2op.pdf',
		},
		{
			id: 'f3',
			name: 'Resumen Estructuras.txt',
			type: 'txt',
			content: `# Estructuras de Datos

## Listas enlazadas
Secuencia de nodos donde cada nodo apunta al siguiente.
- Inserción O(1) al frente
- Búsqueda O(n)

## Pilas (Stack)
LIFO - Last In First Out
Operaciones: push, pop, peek — todas O(1)

## Colas (Queue)
FIFO - First In First Out
Operaciones: enqueue, dequeue — ambas O(1)

## Árboles binarios de búsqueda (BST)
- Búsqueda, inserción, eliminación: O(log n) promedio
- Peor caso (árbol degenerado): O(n)

## Tablas Hash
- Búsqueda e inserción promedio: O(1)
- Colisiones: encadenamiento o direccionamiento abierto
`,
		},
	],
	2: [
		{
			id: 'f4',
			name: 'Modelo Relacional.txt',
			type: 'txt',
			content: `# Modelo Relacional

## Conceptos básicos
- **Relación (tabla):** conjunto de tuplas con el mismo esquema
- **Tupla:** fila de la tabla
- **Atributo:** columna de la tabla
- **Dominio:** conjunto de valores posibles para un atributo

## Claves
- **Superclave:** conjunto de atributos que identifican unívocamente una tupla
- **Clave candidata:** superclave mínima
- **Clave primaria (PK):** clave candidata elegida como principal
- **Clave foránea (FK):** atributo que referencia la PK de otra tabla

## Restricciones de integridad
1. Integridad de entidad: la PK no puede ser NULL
2. Integridad referencial: la FK debe existir en la tabla referenciada o ser NULL
`,
		},
		{
			id: 'f5',
			name: 'SQL Cheatsheet.txt',
			type: 'txt',
			content: `# SQL Cheatsheet

## DDL - Definición de datos
CREATE TABLE alumnos (
  id INT PRIMARY KEY,
  nombre VARCHAR(100),
  legajo VARCHAR(20) UNIQUE
);

ALTER TABLE alumnos ADD COLUMN email VARCHAR(200);
DROP TABLE alumnos;

## DML - Manipulación de datos
INSERT INTO alumnos VALUES (1, 'Ana García', 'LG-001');
UPDATE alumnos SET nombre = 'Ana López' WHERE id = 1;
DELETE FROM alumnos WHERE id = 1;

## DQL - Consultas
SELECT nombre, legajo FROM alumnos WHERE id > 5 ORDER BY nombre;

-- JOIN
SELECT a.nombre, m.nota
FROM alumnos a
INNER JOIN materias m ON a.id = m.alumno_id;

-- Agrupación
SELECT carrera, COUNT(*) as total
FROM alumnos
GROUP BY carrera
HAVING COUNT(*) > 10;
`,
		},
		{
			id: 'f6',
			name: 'Parcial 1er cuatrimestre.pdf',
			type: 'pdf',
			content: null,
			url: '/Examen2023.pdf',
		},
		{
			id: 'f7',
			name: 'Normalización.txt',
			type: 'txt',
			content: `# Normalización de Bases de Datos

## Primera Forma Normal (1FN)
- Todos los atributos deben ser atómicos (sin grupos repetitivos)
- Cada celda contiene un único valor

## Segunda Forma Normal (2FN)
- Cumple 1FN
- Todo atributo no clave depende funcionalmente de TODA la clave primaria
- Elimina dependencias parciales

## Tercera Forma Normal (3FN)
- Cumple 2FN
- No existen dependencias transitivas entre atributos no clave
- Todo atributo no clave depende DIRECTAMENTE de la PK

## Forma Normal de Boyce-Codd (FNBC)
- Versión más estricta de 3FN
- Para toda dependencia funcional X → Y, X debe ser superclave
`,
		},
		{
			id: 'f8',
			name: 'Resumen Transacciones.pdf',
			type: 'pdf',
			content: null,
			url: '/Examen2023-2op.pdf',
		},
	],
	3: [
		{
			id: 'f9',
			name: 'Modelo OSI.txt',
			type: 'txt',
			content: `# Modelo OSI - 7 Capas

## Capa 7 - Aplicación
Interacción con el usuario final. Protocolos: HTTP, FTP, SMTP, DNS.

## Capa 6 - Presentación
Traducción, cifrado y compresión de datos. Formatos: JPEG, MP4, SSL/TLS.

## Capa 5 - Sesión
Gestión de sesiones entre aplicaciones. Protocolos: NetBIOS, RPC.

## Capa 4 - Transporte
Transferencia confiable de datos extremo a extremo.
- TCP: orientado a conexión, confiable, control de flujo
- UDP: sin conexión, rápido, sin garantías

## Capa 3 - Red
Enrutamiento de paquetes. Protocolo IP. Routers.

## Capa 2 - Enlace de datos
Transmisión confiable entre nodos adyacentes. MAC Address. Switches.

## Capa 1 - Física
Transmisión de bits. Cables, señales eléctricas/ópticas.
`,
		},
		{
			id: 'f10',
			name: 'Ejercicio Routing Table.pdf',
			type: 'pdf',
			content: null,
			url: '/Ejercicio_Routing_Table.pdf',
		},
		{
			id: 'f11',
			name: 'Ejercicio Subnetting.pdf',
			type: 'pdf',
			content: null,
			url: '/Ejercicio_Subnetting.pdf',
		},
		{
			id: 'f12',
			name: 'Ejercicio Subnetting (v2).pdf',
			type: 'pdf',
			content: null,
			url: '/Ejercicio_Subnetting (1).pdf',
		},
		{
			id: 'f13',
			name: 'Ejercicios Fragmentación.pdf',
			type: 'pdf',
			content: null,
			url: '/Ejercicios_Fragmentacion.pdf',
		},
		{
			id: 'f14',
			name: 'Ejercicios Fragmentación (v2).pdf',
			type: 'pdf',
			content: null,
			url: '/Ejercicios_Fragmentacion (1).pdf',
		},
	],
}

const subjects = [
	{ id: 1, name: 'Algoritmos y Estructuras' },
	{ id: 2, name: 'Base de Datos' },
	{ id: 3, name: 'Redes' },
]

const tools = [
	{ icon: '🃏', label: 'Flashcards', description: 'Tarjetas de repaso generadas desde tus apuntes.', color: 'tool-green' },
	{ icon: '📝', label: 'Cuestionario', description: 'Preguntas y respuestas para autoevaluarte.', color: 'tool-teal' },
	{ icon: '🎯', label: 'Simulacro de examen', description: 'Practicá con un parcial generado a partir de tus materiales.', color: 'tool-sage' },
]

const DINA_WELCOME = (subjectName) =>
	`¡Hola! Soy Dina 🦕 Estoy lista para ayudarte con **${subjectName}**. Preguntame lo que quieras sobre tus apuntes.`

const FILE_ICONS = { txt: '📄', pdf: '📕' }

// Renderiza **negrita** en el texto de los mensajes
function renderBold(text) {
	const parts = text.split(/\*\*(.+?)\*\*/g)
	return parts.map((part, i) =>
		i % 2 === 1 ? <strong key={i}>{part}</strong> : part
	)
}

function FileViewer({ file, onClose }) {
	const [content, setContent] = useState(file.content ?? '')
	const [edited, setEdited] = useState(false)

	const save = () => {
		file.content = content
		setEdited(false)
	}

	return (
		<div className="file-viewer">
			<div className="file-viewer-header">
				<span className="file-viewer-name">{FILE_ICONS[file.type]} {file.name}</span>
				<div className="file-viewer-actions">
					{file.type === 'txt' && edited && (
						<button className="btn-save" onClick={save}>Guardar</button>
					)}
					<button className="btn-close" onClick={onClose}>✕</button>
				</div>
			</div>
			<div className="file-viewer-body">
				{file.type === 'txt' ? (
					<textarea
						className="file-editor"
						value={content}
						onChange={(e) => { setContent(e.target.value); setEdited(true) }}
						spellCheck={false}
					/>
				) : file.url ? (
					<iframe
						className="file-pdf-iframe"
						src={file.url}
						title={file.name}
					/>
				) : (
					<div className="file-pdf-placeholder">
						<span>📕</span>
						<p>Vista previa de PDF no disponible en esta versión.</p>
						<p className="file-pdf-name">{file.name}</p>
					</div>
				)}
			</div>
		</div>
	)
}

function ChatDina({ subject }) {
	const [messages, setMessages] = useState([
		{ from: 'dina', text: DINA_WELCOME(subject.name) },
	])
	const [input, setInput] = useState('')
	const bottomRef = useRef(null)

	useEffect(() => {
		setMessages([{ from: 'dina', text: DINA_WELCOME(subject.name) }])
	}, [subject.id])

	useEffect(() => {
		bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
	}, [messages])

	const send = () => {
		const text = input.trim()
		if (!text) return
		setMessages((prev) => [
			...prev,
			{ from: 'user', text },
			{ from: 'dina', text: '⏳ Estoy procesando tu consulta... (próximamente conectado al backend)' },
		])
		setInput('')
	}

	const handleKey = (e) => {
		if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
	}

	return (
		<div className="chat-container">
			<div className="chat-header">
				<span className="dina-avatar">🦕</span>
				<div>
					<p className="dina-name">Dina</p>
					<p className="dina-sub">Tu asistente de estudio · {subject.name}</p>
				</div>
			</div>
			<div className="chat-messages">
				{messages.map((m, i) => (
					<div key={i} className={`message ${m.from}`}>
						{m.from === 'dina' && <span className="msg-avatar">🦕</span>}
						<div className="msg-bubble">{renderBold(m.text)}</div>
					</div>
				))}
				<div ref={bottomRef} />
			</div>
			<div className="chat-input-row">
				<textarea
					className="chat-input"
					placeholder="Preguntale algo a Dina..."
					value={input}
					onChange={(e) => setInput(e.target.value)}
					onKeyDown={handleKey}
					rows={1}
				/>
				<button className="chat-send" onClick={send} disabled={!input.trim()}>➤</button>
			</div>
		</div>
	)
}

function App() {
	const [selected, setSelected] = useState(subjects[0].id)
	const [dragging, setDragging] = useState(false)
	const [view, setView] = useState('home') // 'home' | 'dina' | 'file'
	const [openFile, setOpenFile] = useState(null)

	const selectedSubject = subjects.find((s) => s.id === selected)
	const files = subjectFiles[selected] ?? []

	const selectSubject = (id) => {
		setSelected(id)
		setView('home')
		setOpenFile(null)
	}

	const handleFileClick = (file) => {
		setOpenFile(file)
		setView('file')
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
					{view === 'dina' && (
						<>
							<button className="btn-back" onClick={() => setView('home')}>← Volver</button>
							<ChatDina subject={selectedSubject} />
						</>
					)}

					{view === 'file' && openFile && (
						<>
							<button className="btn-back" onClick={() => setView('home')}>← Volver</button>
							<FileViewer file={openFile} onClose={() => setView('home')} />
						</>
					)}

					{view === 'home' && (
						<>
							<div className="main-header">
								<h1>{selectedSubject.name}</h1>
								<span className="file-count">{files.length} archivos</span>
							</div>

							{/* Files */}
							<section className="files-section">
								<h2 className="section-label">Archivos</h2>
								<ul className="file-list">
									{files.map((f) => (
										<li key={f.id} className="file-item" onClick={() => handleFileClick(f)}>
											<span className="file-item-icon">{FILE_ICONS[f.type]}</span>
											<span className="file-item-name">{f.name}</span>
											{f.type === 'txt' && <span className="file-item-badge">editable</span>}
											<span className="file-item-arrow">→</span>
										</li>
									))}
								</ul>

								<div
									className={`upload-area upload-area--compact ${dragging ? 'dragging' : ''}`}
									onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
									onDragLeave={() => setDragging(false)}
									onDrop={(e) => { e.preventDefault(); setDragging(false) }}
								>
									<span>📂</span>
									<p className="upload-sub">Arrastrá archivos acá o</p>
									<label className="btn-upload">
										Subir archivo
										<input type="file" multiple hidden />
									</label>
									<p className="upload-formats">PDF, DOCX, TXT · hasta 20 MB</p>
								</div>
							</section>

							{/* Dina banner */}
							<button className="dina-banner" onClick={() => setView('dina')}>
								<span className="dina-banner-avatar">🦕</span>
								<div className="dina-banner-text">
									<p className="dina-banner-title">Hablá con Dina</p>
									<p className="dina-banner-sub">
										Preguntale cualquier cosa sobre {selectedSubject.name} — responde con tus apuntes como contexto.
									</p>
								</div>
								<span className="dina-banner-arrow">→</span>
							</button>

							{/* Tools */}
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
					)}
				</main>
			</div>
		</div>
	)
}

export default App
