import { useState, useRef, useEffect } from 'react'
import { renderBold } from '../utils/text'

const DINA_WELCOME = (subjectName) =>
	`¡Hola! Soy Dina 🦕 Estoy lista para ayudarte con **${subjectName}**. Preguntame lo que quieras sobre tus apuntes.`

export default function ChatDina({ subject }) {
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
