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
import AiUsageBadge from './AiUsageBadge'
import { providerLabel } from '../lib/aiProviderLabels'
import { loadAiSettings } from '../services/aiSettingsService'

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

/**
 * `initialPrompt` e `initialFileIds` llegan cuando se entra desde un tema de la pantalla de
 * progreso: el chat abre con sus documentos tildados y la pregunta escrita, sin enviarla.
 */
export default function ChatDina({ subject, files = [], user, initialPrompt, initialFileIds }) {
	const token = user?.token
	const subjectId = subject?.id
	const [messages, setMessages] = useState([])
	const [input, setInput] = useState(initialPrompt ?? '')
	const [selectedFileIds, setSelectedFileIds] = useState(initialFileIds ?? [])
	// Los documentos de la materia, cuando no llegan por prop. `subjectFiles` en App sólo se
	// llena al montar la sección Documentos, así que entrar directo acá mostraba "No hay
	// documentos cargados" aunque la materia tuviera archivos.
	const [ownFiles, setOwnFiles] = useState([])
	const [loading, setLoading] = useState(false)
	const [error, setError] = useState('')
	const bottomRef = useRef(null)
	const fileDropdownRef = useRef(null)
	const inputRef = useRef(null)
	const [conversationId, setConversationId] = useState(null)
	const [conversations, setConversations] = useState([])
	const [historyLoading, setHistoryLoading] = useState(Boolean(token && subjectId))
	const [openingConversationId, setOpeningConversationId] = useState(null)
	const [aiSettings, setAiSettings] = useState(null)
	const [aiSettingsLoading, setAiSettingsLoading] = useState(Boolean(user?.token))

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

	const fetchConversations = useCallback(async () => {
		if (!token || !subjectId) return []

		const res = await apiFetch(
			`/api/ai/chats?subjectId=${encodeURIComponent(subjectId)}`
		)

		const body = await res.json().catch(() => [])

		if (!res.ok) {
			throw new Error(
				body.message ||
				body.error ||
				'No se pudo cargar el historial'
			)
		}

		const storedConversations = Array.isArray(body) ? body : []

		return Promise.all(
			storedConversations.map(async (conversation) => {
				const storedMessages = await fetchStoredMessages(conversation.id)
				const usesDocuments = storedMessages.some((message) =>
					hasSourceFiles(message.source_file_ids)
				)

				return {
					...conversation,
					storedMessages,
					usesDocuments,
				}
			})
		)
	}, [token, subjectId, fetchStoredMessages])

	const loadConversations = useCallback(async () => {
		setHistoryLoading(true)
		try {
			setConversations(await fetchConversations())
		} catch (err) {
			console.error('Error cargando historial:', err)
			setError(err.message)
		} finally {
			setHistoryLoading(false)
		}
	}, [fetchConversations])

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
						provider: message.provider,
						model: message.model,
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
		let active = true

		if (!user?.token) {
			return undefined
		}

		loadAiSettings()
			.then((settings) => {
				if (active) setAiSettings(settings)
			})
			.catch((settingsError) => {
				console.error('No se pudo cargar el proveedor de IA:', settingsError)
				if (active) setAiSettings(null)
			})
			.finally(() => {
				if (active) setAiSettingsLoading(false)
			})

		return () => {
			active = false
		}
	}, [user?.token])

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
		let active = true

		fetchConversations()
			.then((storedConversations) => {
				if (active) setConversations(storedConversations)
			})
			.catch((err) => {
				if (!active) return
				console.error('Error cargando historial:', err)
				setError(err.message)
			})
			.finally(() => {
				if (active) setHistoryLoading(false)
			})

		return () => {
			active = false
		}
	}, [fetchConversations])

	// Los documentos propios sólo se piden si no llegaron por prop: quien viene de la sección
	// Documentos ya los trae y no hace falta un pedido más.
	useEffect(() => {
		if (files.length > 0 || !user?.token) return undefined

		let active = true
		apiFetch(`/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`)
			.then(async (response) => {
				const body = await response.json().catch(() => ({}))
				if (!response.ok) throw new Error(body.error || 'No se pudieron cargar los documentos')
				return Array.isArray(body.data) ? body.data : []
			})
			.then((loadedFiles) => {
				if (active) setOwnFiles(loadedFiles)
			})
			.catch((requestError) => {
				console.warn('No se pudieron cargar los documentos del chat:', requestError.message)
			})

		return () => {
			active = false
		}
	}, [subject.id, user?.token, files.length])
	/** Los documentos elegibles: los que llegaron por prop o, si no hubo, los propios. */
	const availableFiles = files.length > 0 ? files : ownFiles

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
					provider: responseBody.provider,
					model: responseBody.model,
				},
			])
			if (responseBody.provider && responseBody.model) {
				setAiSettings((current) => current
					? {
						...current,
						provider: responseBody.provider,
						chatModel: responseBody.model,
					}
					: current)
			}
			if (
				responseBody.newConversation
			) {
				setHistoryLoading(true)
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

					<AiUsageBadge
						className="chat-ai-usage"
						provider={aiSettings?.provider}
						model={aiSettings?.chatModel}
						source={aiSettings?.source}
						loading={aiSettingsLoading}
					/>

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
									files={availableFiles}
									selectedFileIds={selectedFileIds}
									onChange={setSelectedFileIds}
								/>

								{availableFiles.length === 0 && (
									<span className="chat-file-empty">
										No hay documentos cargados
									</span>
								)}

								{availableFiles.map((file) => {
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

								{m.from === 'dina' && m.provider && m.model && (
									<span className="message-ai-model">
										{providerLabel(m.provider)} · {m.model}
									</span>
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
