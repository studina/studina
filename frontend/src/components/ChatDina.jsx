import { useState, useRef, useEffect } from 'react'
import { renderBold } from '../utils/text'
import './ChatDina.css'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const DINA_WELCOME = (subjectName) =>
	`¡Hola! Soy Dina 🦕 Estoy lista para ayudarte con **${subjectName}**. Preguntame lo que quieras sobre tus apuntes.`

export default function ChatDina({ subject, files = [] }) {
	const [messages, setMessages] = useState([
		{ from: 'dina', text: DINA_WELCOME(subject.name) },
	])
	const [input, setInput] = useState('')
	const [selectedFileId, setSelectedFileId] = useState('')
	const bottomRef = useRef(null)

	useEffect(() => {
		setMessages([
			{ from: 'dina', text: DINA_WELCOME(subject.name) },
		])
		setSelectedFileId('')
		setError('')
	}, [subject.id, subject.name])

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
			const endpoint = selectedFileId
				? `/api/ai/files/${encodeURIComponent(selectedFileId)}/chat`
				: '/api/ai/chat'

			const res = await fetch(endpoint, {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
				},
				body: JSON.stringify({
					message: text,
				}),
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

	return (
		<div className="chat-container">
			<div className="chat-header">
				<span className="dina-avatar">🦕</span>
				<div>
					<p className="dina-name">Dina</p>
					<p className="dina-sub">Tu asistente de estudio · {subject.name}</p>
				</div>
				<select
					className="chat-file-select"
					value={selectedFileId}
					onChange={(e) => {
						const fileId = e.target.value
						setSelectedFileId(fileId)

						setMessages([
							{
								from: 'dina',
								text: fileId
									? '📄 Documento seleccionado. Podés preguntarme sobre su contenido.'
									: DINA_WELCOME(subject.name),
							},
						])
					}}
				>
					<option value="">Chat general</option>

					{files.map((file) => (
						<option key={file.id} value={file.id}>
							{file.filename || file.name || 'Documento'}
						</option>
					))}
				</select>
			</div>
			<div className="chat-messages">
				{messages.map((m, i) => (
					<div key={i} className={`message ${m.from}`}>
						{m.from === 'dina' && <span className="msg-avatar">🦕</span>}

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
						<span className="msg-avatar">🦕</span>
						<div className="msg-bubble">⏳ Dina está pensando...</div>
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
