import { useState, useRef, useEffect } from 'react'
import './ChatDina.css'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import dinaAvatar from '../assets/dina/dina-avatar.png'
import dinaEmptyChat from '../assets/dina/dina-empty-chat.png'

const CHAT_SUGGESTIONS = [
	'Resumí las ideas principales de mis documentos',
	'Explicame este tema de manera sencilla',
	'Preparame preguntas para practicar',
]

export default function ChatDina({ subject, files = [], user }) {
	const [messages, setMessages] = useState([])
	const [input, setInput] = useState('')
	const [selectedFileIds, setSelectedFileIds] = useState([])
	const bottomRef = useRef(null)

	useEffect(() => {
		bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
	}, [messages])

	const [loading, setLoading] = useState(false)
	const [error, setError] = useState('')

	const send = async () => {
		const text = input.trim()

		if (!text || loading) return

		setMessages((prev) => [
			...prev,
			{ from: 'user', text },
		])

		setInput('')
		setLoading(true)
		setError('')

		try {
			const hasSelectedFiles = selectedFileIds.length > 0

			const endpoint = hasSelectedFiles
				? '/api/ai/files/chat'
				: '/api/ai/chat'

			const body = hasSelectedFiles
				? {
						message: text,
						fileIds: selectedFileIds,
					}
				: {
						message: text,
					}

				const res = await fetch(endpoint, {
					method: 'POST',
					headers: {
						'Content-Type': 'application/json',
						...(hasSelectedFiles && user?.token
							? { Authorization: `Bearer ${user.token}` }
							: {}),
					},
				body: JSON.stringify(body),
			})

			const responseBody = await res.json().catch(() => ({}))

			if (!res.ok) {
				throw new Error(
					responseBody.message ||
					responseBody.error ||
					'No se pudo obtener una respuesta'
				)
			}

			setMessages((prev) => [
				...prev,
				{
					from: 'dina',
					text: responseBody.answer,
				},
			])
		} catch (err) {
			console.error('Error consultando a Dina:', err)
			setError(err.message)
		} finally {
			setLoading(false)
		}
	}

	const handleKey = (e) => {
		if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
	}

	const toggleFile = (fileId) => {
		setSelectedFileIds((prev) =>
			prev.includes(fileId)
				? prev.filter((id) => id !== fileId)
				: [...prev, fileId]
		)	
	}

	return (
		<div className="chat-container">
			<div className="chat-header">
				<span className="dina-avatar"><img src={dinaAvatar} alt="Dina" /></span>
				<div>
					<p className="dina-name">Dina</p>
					<p className="dina-sub">Tu asistente de estudio · {subject.name}</p>
				</div>
				<div className="chat-file-selector">
					<details className="chat-file-dropdown">
						<summary className="chat-file-dropdown-summary">
							📄 Documentos seleccionados: {selectedFileIds.length}
						</summary>

						<div className="chat-file-dropdown-menu">
							{files.length === 0 && (
								<span className="chat-file-empty">
									No hay documentos cargados
								</span>
							)}

							{files.map((file) => {
								const filename =
									file.filename ||
									file.name ||
									'Documento'

								return (
									<label
										key={file.id}
										className="chat-file-option"
									>
										<input
											type="checkbox"
											checked={selectedFileIds.includes(file.id)}
											onChange={() => toggleFile(file.id)}
										/>

										<span>{filename}</span>
									</label>
								)
							})}
						</div>
					</details>
				</div>
			</div>
			<div className="chat-messages">
				{messages.length === 0 && !loading && (
					<div className="chat-empty-state">
						<img src={dinaEmptyChat} alt="Dina saludando" />
						<div className="chat-empty-copy">
							<p className="chat-empty-eyebrow">Tu copiloto de estudio</p>
							<h2>¿Qué querés estudiar hoy?</h2>
							<p>Preguntame sobre {subject.name} o empezá con una de estas ideas.</p>
							<div className="chat-suggestions">
								{CHAT_SUGGESTIONS.map((suggestion) => (
									<button key={suggestion} onClick={() => setInput(suggestion)}>
										{suggestion}
									</button>
								))}
							</div>
						</div>
					</div>
				)}
				{messages.map((m, i) => (
					<div key={i} className={`message ${m.from}`}>
						{m.from === 'dina' && (<span className="msg-avatar"><img src={dinaAvatar} alt="Dina" /></span>)}

						<div className="msg-bubble">
							{m.from === 'dina' ? (
								<ReactMarkdown remarkPlugins={[remarkGfm]}>
									{m.text}
								</ReactMarkdown>
							) : (
								m.text
							)}
						</div>
					</div>
				))}
				{loading && (
					<div className="message dina">
						<span className="msg-avatar"><img src={dinaAvatar} alt="Dina" /></span>
						<div className="msg-bubble">
							⏳ Dina está pensando...
						</div>
					</div>
				)}
				<div ref={bottomRef} />
			</div>
			{error && (
				<p className="chat-error">{error}</p>
			)}
			<div className="chat-input-row">
				<textarea
					className="chat-input"
					placeholder="Preguntale algo a Dina..."
					value={input}
					onChange={(e) => setInput(e.target.value)}
					onKeyDown={handleKey}
					rows={1}
					disabled={loading}
				/>
				<button
					className="chat-send"
					onClick={send}
					disabled={loading || !input.trim()}
				>
					➤
				</button>
			</div>
		</div>
	)
}
