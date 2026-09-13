import { useState, useRef, useEffect, useCallback } from 'react'
import './ChatDina.css'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'
import dinaAvatar from '../assets/dina/dina-avatar.png'
import dinaEmptyChat from '../assets/dina/dina-empty-chat.png'

const CHAT_SUGGESTIONS = [
	'Resumí las ideas principales de mis documentos',
	'Explicame este tema de manera sencilla',
	'Preparame preguntas para practicar',
]

const hasSourceFiles = (sourceFileIds) => {
	if (!sourceFileIds) return false

	if (Array.isArray(sourceFileIds)) {
		return sourceFileIds.length > 0
	}

	if (typeof sourceFileIds === 'object') {
		return Object.keys(sourceFileIds).length > 0
	}

	if (typeof sourceFileIds === 'string') {
		if (!sourceFileIds.trim()) return false

		try {
			const parsed = JSON.parse(sourceFileIds)

			if (Array.isArray(parsed)) {
				return parsed.length > 0
			}

			return Boolean(parsed)
		} catch {
			return true
		}
	}

	return false
}

export default function ChatDina({ subject, files = [], user }) {
	const [messages, setMessages] = useState([])
	const [input, setInput] = useState('')
	const [selectedFileIds, setSelectedFileIds] = useState([])
	const [loading, setLoading] = useState(false)
	const [error, setError] = useState('')
	const bottomRef = useRef(null)
	const fileDropdownRef = useRef(null)
	const inputRef = useRef(null)
	const [conversationId, setConversationId] = useState(null)
	const [conversations, setConversations] = useState([])
	const [historyLoading, setHistoryLoading] = useState(false)
	const [openingConversationId, setOpeningConversationId] = useState(null)

	const historyDropdownRef = useRef(null)

	const fetchStoredMessages = useCallback(
		async (id) => {
			const res = await fetch(
				`/api/ai/chats/${id}/messages`,
				{
					headers: {
						...(user?.token
							? {
								Authorization: `Bearer ${user.token}`,
							}
							: {}),
					},
				}
			)

			const body = await res.json().catch(() => [])

			if (!res.ok) {
				throw new Error(
					body.message ||
					body.error ||
					'No se pudieron cargar los mensajes'
				)
			}

			return Array.isArray(body) ? body : []
		},
		[user?.token]
	)

	const loadPlainConversations = useCallback(async () => {
		if (!user?.token || !subject?.id) {
			setConversations([])
			return
		}

		setHistoryLoading(true)

		try {
			const res = await fetch(
				`/api/ai/chats?subjectId=${encodeURIComponent(subject.id)}`,
				{
					headers: {
						Authorization: `Bearer ${user.token}`,
					},
				}
			)

			const body = await res.json().catch(() => [])

			if (!res.ok) {
				throw new Error(
					body.message ||
					body.error ||
					'No se pudo cargar el historial'
				)
			}

			const storedConversations =
				Array.isArray(body) ? body : []

			const conversationsWithMessages =
				await Promise.all(
					storedConversations.map(
						async (conversation) => {
							const storedMessages =
								await fetchStoredMessages(
									conversation.id
								)

							return {
								...conversation,
								storedMessages,
							}
						}
					)
				)

			const plainConversations =
				conversationsWithMessages.filter(
					(conversation) =>
						!conversation.storedMessages.some(
							(message) =>
								hasSourceFiles(
									message.source_file_ids
								)
						)
				)

			setConversations(plainConversations)
		} catch (err) {
			console.error(
				'Error cargando historial:',
				err
			)

			setError(err.message)
		} finally {
			setHistoryLoading(false)
		}
	}, [
		user?.token,
		subject?.id,
		fetchStoredMessages,
	])

	const openConversation = async (conversation) => {
		if (loading || openingConversationId) return

		setOpeningConversationId(conversation.id)
		setError('')

		try {
			const storedMessages =
				conversation.storedMessages ||
				await fetchStoredMessages(
					conversation.id
				)

			const uiMessages =
				storedMessages
					.filter(
						(message) =>
							message.role === 'user' ||
							message.role === 'assistant'
					)
					.map((message) => ({
						from:
							message.role === 'assistant'
								? 'dina'
								: 'user',
						text: message.content,
					}))

			setMessages(uiMessages)

			setConversationId(
				conversation.id
			)

			setSelectedFileIds([])

			historyDropdownRef.current
				?.removeAttribute('open')

			setTimeout(() => {
				bottomRef.current?.scrollIntoView({
					behavior: 'smooth',
				})
			}, 0)
		} catch (err) {
			console.error(
				'Error abriendo conversación:',
				err
			)

			setError(err.message)
		} finally {
			setOpeningConversationId(null)
		}
	}

	const startNewConversation = () => {
		setConversationId(null)
		setMessages([])
		setSelectedFileIds([])
		setInput('')
		setError('')

		historyDropdownRef.current
			?.removeAttribute('open')

		setTimeout(() => {
			inputRef.current?.focus()
		}, 0)
	}

	useEffect(() => {
		bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
	}, [messages])

	useEffect(() => {
		if (!loading) {
			inputRef.current?.focus()
		}
	}, [loading])

	useEffect(() => {
		const handleClickOutside = (e) => {
			if (fileDropdownRef.current && !fileDropdownRef.current.contains(e.target)) {
				fileDropdownRef.current.removeAttribute('open')
			}
		}
		document.addEventListener('mousedown', handleClickOutside)
		return () => document.removeEventListener('mousedown', handleClickOutside)
	}, [])

	useEffect(() => {
		setMessages([])
		setConversationId(null)
		setSelectedFileIds([])
		setConversations([])
		setError('')

		loadPlainConversations()
	}, [
		subject.id,
		user?.token,
		loadPlainConversations,
	])
	const send = async () => {
		const text = input.trim()

		if (!text || loading) return

		setMessages((prev) => [
			...prev,
			{ from: 'user', text },
		])

		setInput('')
		inputRef.current?.focus()
		setLoading(true)
		setError('')

		try {
			const hasSelectedFiles = selectedFileIds.length > 0

			const endpoint = hasSelectedFiles
				? '/api/ai/files/chat'
				: '/api/ai/chat'

			const conversationData = conversationId
				? {
					conversationId,
				}
				: {
					subjectId: subject.id,
				}

			const body = hasSelectedFiles
				? {
					message: text,
					fileIds: selectedFileIds,
					...conversationData,
				}
				: {
					message: text,
					...conversationData,
				}

			const res = await fetch(endpoint, {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					...(user?.token
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

			if (responseBody.conversationId) {
				setConversationId(responseBody.conversationId)
			}

			setMessages((prev) => [
				...prev,
				{
					from: 'dina',
					text: responseBody.answer,
				},
			])
			if (
				!hasSelectedFiles &&
				responseBody.newConversation
			) {
				loadPlainConversations()
			}
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
				<div className="chat-header-actions">

					<button
						type="button"
						className="chat-new-button"
						onClick={startNewConversation}
					>
						＋ Nuevo chat
					</button>

					<details
						className="chat-history-dropdown"
						ref={historyDropdownRef}
					>
						<summary className="chat-history-summary">
							🕘 Historial
							{!historyLoading &&
								` (${conversations.length})`}
						</summary>

						<div className="chat-history-menu">

							{historyLoading && (
								<p className="chat-history-empty">
									Cargando conversaciones...
								</p>
							)}

							{!historyLoading &&
								conversations.length === 0 && (
									<p className="chat-history-empty">
										Todavía no hay conversaciones.
									</p>
								)}

							{!historyLoading &&
								conversations.map(
									(conversation) => (
										<button
											type="button"
											key={conversation.id}
											className={
												`chat-history-item ${conversation.id ===
													conversationId
													? 'active'
													: ''
												}`
											}
											onClick={() =>
												openConversation(
													conversation
												)
											}
										>
											<span className="chat-history-title">
												{conversation.title ||
													'Chat sin título'}
											</span>

											{openingConversationId ===
												conversation.id && (
													<span className="chat-history-opening">
														Cargando...
													</span>
												)}
										</button>
									)
								)}

						</div>
					</details>

					<div className="chat-file-selector">
						{/* Tu <details> actual de documentos va acá */}
					</div>

				</div>
				<div className="chat-file-selector">
					<details className="chat-file-dropdown" ref={fileDropdownRef}>
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
									<button
										key={suggestion}
										onClick={() => {
											setInput(suggestion)
											inputRef.current?.focus()
										}}
									>
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
								<ReactMarkdown
									remarkPlugins={[remarkGfm, remarkMath]}
									rehypePlugins={[rehypeKatex]}
								>
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
					ref={inputRef}
					className="chat-input"
					placeholder="Preguntale algo a Dina..."
					value={input}
					onChange={(e) => setInput(e.target.value)}
					onKeyDown={handleKey}
					rows={1}
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
