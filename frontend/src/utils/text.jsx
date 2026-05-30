export const FILE_ICONS = { txt: '📄', pdf: '📕' }

export function renderBold(text) {
	const parts = text.split(/\*\*(.+?)\*\*/g)
	return parts.map((part, i) =>
		i % 2 === 1 ? <strong key={i}>{part}</strong> : part
	)
}
