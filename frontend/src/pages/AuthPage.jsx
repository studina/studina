import { useState } from 'react'
import './AuthPage.css'

/**
 * Página de inicio: login / registro.
 * Props:
 *   onEnter(user) – callback al autenticarse o saltear
 */
export default function AuthPage({ onEnter }) {
	const [mode, setMode] = useState('login') // 'login' | 'register'

	// ── Login state ──
	const [loginEmail, setLoginEmail] = useState('')
	const [loginPassword, setLoginPassword] = useState('')

	// ── Register state ──
	const [regName, setRegName] = useState('')
	const [regLastName, setRegLastName] = useState('')
	const [regEmail, setRegEmail] = useState('')
	const [regPassword, setRegPassword] = useState('')
	const [regConfirm, setRegConfirm] = useState('')

	const [errors, setErrors] = useState({})

	const validateLogin = () => {
		const e = {}
		if (!loginEmail.trim()) e.loginEmail = 'Ingresá tu email.'
		if (!loginPassword) e.loginPassword = 'Ingresá tu contraseña.'
		setErrors(e)
		return Object.keys(e).length === 0
	}

	const validateRegister = () => {
		const e = {}
		if (!regName.trim()) e.regName = 'Ingresá tu nombre.'
		if (!regLastName.trim()) e.regLastName = 'Ingresá tu apellido.'
		if (!regEmail.trim()) e.regEmail = 'Ingresá tu email.'
		if (!regPassword) e.regPassword = 'Ingresá una contraseña.'
		if (regPassword && regPassword.length < 6) e.regPassword = 'Mínimo 6 caracteres.'
		if (regConfirm !== regPassword) e.regConfirm = 'Las contraseñas no coinciden.'
		setErrors(e)
		return Object.keys(e).length === 0
	}

	const handleLogin = (e) => {
		e.preventDefault()
		if (!validateLogin()) return
		onEnter({ name: loginEmail.split('@')[0] })
	}

	const handleRegister = (e) => {
		e.preventDefault()
		if (!validateRegister()) return
		onEnter({ name: regName })
	}

	const switchMode = (m) => {
		setMode(m)
		setErrors({})
	}

	return (
		<div className="auth-page">
			<div className="auth-card">
				{/* ── Logo ── */}
				<div className="auth-logo">
					<span className="auth-logo-icon">🦕</span>
					<span className="auth-logo-text">studina</span>
				</div>
				<p className="auth-tagline">Tu copiloto del aprendizaje</p>

				{/* ── Tabs ── */}
				<div className="auth-tabs">
					<button
						className={`auth-tab ${mode === 'login' ? 'active' : ''}`}
						onClick={() => switchMode('login')}
					>
						Iniciar sesión
					</button>
					<button
						className={`auth-tab ${mode === 'register' ? 'active' : ''}`}
						onClick={() => switchMode('register')}
					>
						Registrarse
					</button>
				</div>

				{/* ── Login ── */}
				{mode === 'login' && (
					<form className="auth-form" onSubmit={handleLogin} noValidate>
						<div className="auth-field">
							<label className="auth-label">Email</label>
							<input
								className={`auth-input ${errors.loginEmail ? 'error' : ''}`}
								type="email"
								placeholder="tu@email.com"
								value={loginEmail}
								onChange={(e) => setLoginEmail(e.target.value)}
							/>
							{errors.loginEmail && <p className="auth-error">{errors.loginEmail}</p>}
						</div>

						<div className="auth-field">
							<label className="auth-label">Contraseña</label>
							<input
								className={`auth-input ${errors.loginPassword ? 'error' : ''}`}
								type="password"
								placeholder="••••••••"
								value={loginPassword}
								onChange={(e) => setLoginPassword(e.target.value)}
							/>
							{errors.loginPassword && <p className="auth-error">{errors.loginPassword}</p>}
						</div>

						<button type="submit" className="btn-auth-primary">
							Iniciar sesión
						</button>
					</form>
				)}

				{/* ── Register ── */}
				{mode === 'register' && (
					<form className="auth-form" onSubmit={handleRegister} noValidate>
						<div className="auth-row">
							<div className="auth-field">
								<label className="auth-label">Nombre</label>
								<input
									className={`auth-input ${errors.regName ? 'error' : ''}`}
									type="text"
									placeholder="Ana"
									value={regName}
									onChange={(e) => setRegName(e.target.value)}
								/>
								{errors.regName && <p className="auth-error">{errors.regName}</p>}
							</div>
							<div className="auth-field">
								<label className="auth-label">Apellido</label>
								<input
									className={`auth-input ${errors.regLastName ? 'error' : ''}`}
									type="text"
									placeholder="García"
									value={regLastName}
									onChange={(e) => setRegLastName(e.target.value)}
								/>
								{errors.regLastName && <p className="auth-error">{errors.regLastName}</p>}
							</div>
						</div>

						<div className="auth-field">
							<label className="auth-label">Email</label>
							<input
								className={`auth-input ${errors.regEmail ? 'error' : ''}`}
								type="email"
								placeholder="tu@email.com"
								value={regEmail}
								onChange={(e) => setRegEmail(e.target.value)}
							/>
							{errors.regEmail && <p className="auth-error">{errors.regEmail}</p>}
						</div>

						<div className="auth-field">
							<label className="auth-label">Contraseña</label>
							<input
								className={`auth-input ${errors.regPassword ? 'error' : ''}`}
								type="password"
								placeholder="Mínimo 6 caracteres"
								value={regPassword}
								onChange={(e) => setRegPassword(e.target.value)}
							/>
							{errors.regPassword && <p className="auth-error">{errors.regPassword}</p>}
						</div>

						<div className="auth-field">
							<label className="auth-label">Confirmar contraseña</label>
							<input
								className={`auth-input ${errors.regConfirm ? 'error' : ''}`}
								type="password"
								placeholder="••••••••"
								value={regConfirm}
								onChange={(e) => setRegConfirm(e.target.value)}
							/>
							{errors.regConfirm && <p className="auth-error">{errors.regConfirm}</p>}
						</div>

						<button type="submit" className="btn-auth-primary">
							Crear cuenta
						</button>
					</form>
				)}

				{/* ── Separador ── */}
				<div className="auth-divider">
					<span>o</span>
				</div>

				{/* ── Botón saltear ── */}
				<button className="btn-auth-skip" onClick={() => onEnter({ name: 'Invitado' })}>
					Entrar sin cuenta →
				</button>
			</div>
		</div>
	)
}
