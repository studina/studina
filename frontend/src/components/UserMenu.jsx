import { useState, useRef, useEffect } from 'react'
import './UserMenu.css'

export default function UserMenu({
	user,
	onProfile,
	onLogout,
}) {
	const [open, setOpen] = useState(false)
	const ref = useRef(null)

	useEffect(() => {
		const handler = (e) => {
			if (ref.current && !ref.current.contains(e.target)) setOpen(false)
		}
		document.addEventListener('mousedown', handler)
		return () => document.removeEventListener('mousedown', handler)
	}, [])

	return (
		<div className="user-menu-wrapper" ref={ref}>
			<button className="user-menu-trigger" onClick={() => setOpen((o) => !o)}>
				<span className="user-menu-avatar">👤</span>
				<span className="user-menu-name">{user.name}</span>
				<span className={`user-menu-caret ${open ? 'open' : ''}`}>▾</span>
			</button>

			{open && (
				<div className="user-menu-dropdown">
					<div className="user-menu-info">
						<span className="user-menu-info-name">{user.name}</span>
						{user.email && <span className="user-menu-info-email">{user.email}</span>}
					</div>
					<div className="user-menu-divider" />
					<button
						type="button"
						className="user-menu-item"
						onClick={() => {
							setOpen(false)
							onProfile()
						}}
					>
						<span>🪪</span> Ver perfil
					</button>
					<button className="user-menu-item user-menu-item--danger" onClick={() => { setOpen(false); onLogout() }}>
						<span>🚪</span> Cerrar sesión
					</button>
				</div>
			)}
		</div>
	)
}
