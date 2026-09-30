import { useState, useRef, useEffect, useCallback } from 'react'
import SelectAllFiles from './SelectAllFiles'
import './ChatDina.css'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'
import { apiFetch } from '../lib/session'
import dinaAvatar from '../assets/dina/dina-avatar.png'
import dinaEmptyChat from '../assets/dina/dina-empty-chat.png'

const CHAT_SUGGESTIONS = [
	'Resumí las ideas principales de mis documentos',
	'Explicame este tema de manera sencilla',
	'Preparame preguntas para practicar',
]

const getSourceFileIds = (sourceFileIds) => {
	if (!sourceFileIds) return []

	if (Array.isArray(sourceFileIds)) {
		return sourceFileIds.map(String)
	}

	if (typeof sourceFileIds === 'string') {
		if (!sourceFileIds.trim()) return []

		try {
			const parsed = JSON.parse(sourceFileIds)

			return Array.isArray(parsed)
				? parsed.map(String)
				: []
		} catch {
			return []
		}
	}

	return []
}

const hasSourceFiles = (sourceFileIds) =>
	getSourceFileIds(sourceFileIds).length > 0

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

	const fetchStoredMessages = useCallback(
		async (id) => {
			const res = await apiFetch(`/api/ai/chats/${id}/messages`)

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
		[]
	)

	const loadConversations = useCallback(async () => {
		if (!user?.token || !subject?.id) {
			setConversations([])
			return
		}

		setHistoryLoading(true)

		try {
			const res = await apiFetch(
				`/api/ai/chats?subjectId=${encodeURIComponent(subject.id)}`
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

							const usesDocuments =
								storedMessages.some(
									(message) =>
										hasSourceFiles(
											message.source_file_ids
										)
								)

							return {
								...conversation,
								storedMessages,
								usesDocuments,
							}
						}
					)
				)

			setConversations(
				conversationsWithMessages
			)
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

			const messagesWithFiles =
				storedMessages.filter(
					(message) =>
						hasSourceFiles(
							message.source_file_ids
						)
				)

			let restoredFileIds = []

			if (messagesWithFiles.length > 0) {
				const lastMessageWithFiles =
					messagesWithFiles[
					messagesWithFiles.length - 1
					]

				restoredFileIds =
					getSourceFileIds(
						lastMessageWithFiles.source_file_ids
					)
			}

			setSelectedFileIds(restoredFileIds)

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

		loadConversations()
	}, [
		subject.id,
		user?.token,
		loadConversations,
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

			const res = await apiFetch(endpoint, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
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
				responseBody.newConversation
			) {
				loadConversations()
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

			<aside className="chat-sidebar">

				<div className="chat-sidebar-top">
					<button
						type="button"
						className="chat-new-button"
						onClick={startNewConversation}
					>
						<span className="chat-new-icon">＋</span>
						Nuevo chat
					</button>
				</div>

				<div className="chat-sidebar-history">

					<div className="chat-sidebar-history-header">
						<span>Historial</span>

						{!historyLoading && (
							<span className="chat-history-count">
								{conversations.length}
							</span>
						)}
					</div>

					<div className="chat-history-list">

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
										<div className="chat-history-title-row">

											<span className="chat-history-title">
												{conversation.title ||
													'Chat sin título'}
											</span>

											{conversation.usesDocuments && (
												<span
													className="chat-history-doc-badge"
													title="Esta conversación utiliza documentos"
												>
													📄
												</span>
											)}

										</div>

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

				</div>

			</aside>


			<section className="chat-main">

				<div className="chat-header">

					<span className="dina-avatar">
						<img
							src={dinaAvatar}
							alt="Dina"
						/>
					</span>

					<div>
						<p className="dina-name">
							Dina
						</p>

						<p className="dina-sub">
							Tu asistente de estudio · {subject.name}
						</p>
					</div>

					<div className="chat-file-selector">

						<details
							className="chat-file-dropdown"
							ref={fileDropdownRef}
						>

							<summary className="chat-file-dropdown-summary">
								📄 Documentos seleccionados: {selectedFileIds.length}
							</summary>

							<div className="chat-file-dropdown-menu">

								<SelectAllFiles
									className="chat-file-option chat-file-option--all"
									files={files}
									selectedFileIds={selectedFileIds}
									onChange={setSelectedFileIds}
								/>

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
												checked={
													selectedFileIds.includes(
														file.id
													)
												}
												onChange={() =>
													toggleFile(
														file.id
													)
												}
											/>

											<span>
												{filename}
											</span>

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

							<img
								src={dinaEmptyChat}
								alt="Dina saludando"
							/>

							<div className="chat-empty-copy">

								<p className="chat-empty-eyebrow">
									Tu copiloto de estudio
								</p>

								<h2>
									¿Qué querés estudiar hoy?
								</h2>

								<p>
									Preguntame sobre {subject.name} o empezá con una de estas ideas.
								</p>

								<div className="chat-suggestions">

									{CHAT_SUGGESTIONS.map(
										(suggestion) => (
											<button
												key={suggestion}
												onClick={() => {
													setInput(
														suggestion
													)

													inputRef.current
														?.focus()
												}}
											>
												{suggestion}
											</button>
										)
									)}

								</div>

							</div>

						</div>
					)}


					{messages.map((m, i) => (

						<div
							key={i}
							className={`message ${m.from}`}
						>

							{m.from === 'dina' && (
								<span className="msg-avatar">
									<img
										src={dinaAvatar}
										alt="Dina"
									/>
								</span>
							)}

							<div className="msg-bubble">

								{m.from === 'dina' ? (

									<ReactMarkdown
										remarkPlugins={[
											remarkGfm,
											remarkMath,
										]}
										rehypePlugins={[
											rehypeKatex,
										]}
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

							<span className="msg-avatar">
								<img
									src={dinaAvatar}
									alt="Dina"
								/>
							</span>

							<div className="msg-bubble">
								⏳ Dina está pensando...
							</div>

						</div>

					)}

					<div ref={bottomRef} />

				</div>


				{error && (
					<p className="chat-error">
						{error}
					</p>
				)}


				<div className="chat-input-row">

					<textarea
						ref={inputRef}
						className="chat-input"
						placeholder="Preguntale algo a Dina..."
						value={input}
						onChange={(e) =>
							setInput(e.target.value)
						}
						onKeyDown={handleKey}
						rows={1}
					/>

					<button
						className="chat-send"
						onClick={send}
						disabled={
							loading ||
							!input.trim()
						}
					>
						➤
					</button>

				</div>

			</section>

		</div>
	)
}
