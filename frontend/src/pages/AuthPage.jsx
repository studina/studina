import { useState } from 'react'
import './AuthPage.css'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

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
	const [apiError, setApiError] = useState('')
	const [loading, setLoading] = useState(false)

	const validateLogin = () => {
		const e = {}
		if (!loginEmail.trim()) e.loginEmail = 'Ingresá tu email.'
		else if (!EMAIL_PATTERN.test(loginEmail.trim())) e.loginEmail = 'Ingresá un email válido.'
		if (!loginPassword) e.loginPassword = 'Ingresá tu contraseña.'
		setErrors(e)
		return Object.keys(e).length === 0
	}

	const validateRegister = () => {
		const e = {}
		if (!regName.trim()) e.regName = 'Ingresá tu nombre.'
		if (!regLastName.trim()) e.regLastName = 'Ingresá tu apellido.'
		if (!regEmail.trim()) e.regEmail = 'Ingresá tu email.'
		else if (!EMAIL_PATTERN.test(regEmail.trim())) e.regEmail = 'Ingresá un email válido.'
		if (!regPassword) e.regPassword = 'Ingresá una contraseña.'
		if (regPassword && regPassword.length < 6) e.regPassword = 'Mínimo 6 caracteres.'
		if (regConfirm !== regPassword) e.regConfirm = 'Las contraseñas no coinciden.'
		setErrors(e)
		return Object.keys(e).length === 0
	}

	const handleLogin = async (e) => {
		e.preventDefault()
		if (!validateLogin()) return
		setApiError('')
		setLoading(true)
		try {
			const res = await fetch('/api/auth/login', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ email: loginEmail.trim(), password: loginPassword }),
			})
			const data = await res.json()
			if (!res.ok) { setApiError(data.error ?? 'Error al iniciar sesión'); return }
			onEnter(data)
		} catch {
			setApiError('No se pudo conectar con el servidor')
		} finally {
			setLoading(false)
		}
	}

	const handleRegister = async (e) => {
		e.preventDefault()
		if (!validateRegister()) return
		setApiError('')
		setLoading(true)
		try {
			const res = await fetch('/api/auth/register', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({
					firstName: regName.trim(),
					lastName: regLastName.trim(),
					email: regEmail.trim(),
					password: regPassword,
				}),
			})
			const data = await res.json()
			if (!res.ok) { setApiError(data.error ?? 'Error al crear la cuenta'); return }
			onEnter(data)
		} catch {
			setApiError('No se pudo conectar con el servidor')
		} finally {
			setLoading(false)
		}
	}

	const switchMode = (m) => {
		setMode(m)
		setErrors({})
		setApiError('')
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

{apiError && <p className="auth-error auth-error--api">{apiError}</p>}
					<button type="submit" className="btn-auth-primary" disabled={loading}>
						{loading ? 'Ingresando...' : 'Iniciar sesión'}
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

{apiError && <p className="auth-error auth-error--api">{apiError}</p>}
					<button type="submit" className="btn-auth-primary" disabled={loading}>
						{loading ? 'Creando cuenta...' : 'Crear cuenta'}
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
