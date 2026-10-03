import { useEffect, useState } from 'react'
import {
    loadAiSettings,
    resetAiSettings,
    saveAiSettings,
} from '../../services/aiSettingsService'
import './ProfilePage.css'

const PROVIDERS = {
    'google-genai': {
        label: 'Google Gemini',
        description: 'Usa la API de Gemini con tu propia API key.',
        defaultModel: 'gemini-2.5-flash',
        needsCredential: true,
    },
    ollama: {
        label: 'Ollama local',
        description: 'Usa el Ollama configurado en el servidor de Studina.',
        defaultModel: 'llama3.2',
        needsCredential: false,
    },
    openai: {
        label: 'OpenAI / compatible',
        description: 'Usa OpenAI o el endpoint compatible configurado en el servidor.',
        defaultModel: 'gpt-4.1-mini',
        needsCredential: true,
    },
}

const getInitials = (name = '') => {
    const words = name.trim().split(/\s+/).filter(Boolean)
    if (words.length === 0) return 'U'
    return words.slice(0, 2).map((word) => word[0].toUpperCase()).join('')
}

const toForm = (settings) => ({
    provider: settings.provider,
    chatModel: settings.chatModel,
    generationModel: settings.generationModel,
    classificationModel: settings.classificationModel,
    summaryModel: settings.summaryModel,
    apiKey: '',
})

export default function ProfilePage({ user, onBack }) {
    const initials = getInitials(user?.name)
    const [settings, setSettings] = useState(null)
    const [form, setForm] = useState(null)
    const [loading, setLoading] = useState(true)
    const [saving, setSaving] = useState(false)
    const [message, setMessage] = useState('')
    const [error, setError] = useState('')

    useEffect(() => {
        let active = true
        loadAiSettings(user.token)
            .then((loaded) => {
                if (!active) return
                setSettings(loaded)
                setForm(toForm(loaded))
            })
            .catch((requestError) => {
                if (active) setError(requestError.message)
            })
            .finally(() => {
                if (active) setLoading(false)
            })
        return () => {
            active = false
        }
    }, [user.token])

    const updateField = (field, value) => {
        setForm((current) => ({ ...current, [field]: value }))
        setMessage('')
        setError('')
    }

    const changeProvider = (provider) => {
        const defaultModel = PROVIDERS[provider].defaultModel
        setForm((current) => ({
            ...current,
            provider,
            chatModel: defaultModel,
            generationModel: defaultModel,
            classificationModel: defaultModel,
            summaryModel: defaultModel,
            apiKey: '',
        }))
        setMessage('')
        setError('')
    }

    const handleSave = async (event) => {
        event.preventDefault()
        setSaving(true)
        setMessage('')
        setError('')
        try {
            const saved = await saveAiSettings(user.token, form)
            setSettings(saved)
            setForm(toForm(saved))
            setMessage('Configuración de IA guardada.')
        } catch (requestError) {
            setError(requestError.message)
        } finally {
            setSaving(false)
        }
    }

    const handleReset = async () => {
        setSaving(true)
        setMessage('')
        setError('')
        try {
            const restored = await resetAiSettings(user.token)
            setSettings(restored)
            setForm(toForm(restored))
            setMessage('Volviste a la configuración general de Studina.')
        } catch (requestError) {
            setError(requestError.message)
        } finally {
            setSaving(false)
        }
    }

    const selectedProvider = form ? PROVIDERS[form.provider] : null

    return (
        <section className="profile-page">
            <button type="button" className="btn-back" onClick={onBack}>
                ← Volver
            </button>

            <header className="profile-header">
                <div>
                    <h1 className="profile-title">Mi perfil</h1>
                    <p className="profile-subtitle">
                        Administrá tu cuenta y la inteligencia artificial que usa Dina.
                    </p>
                </div>
                <div className="profile-header-icon" aria-hidden="true">🪪</div>
            </header>

            <div className="profile-content">
                <aside className="profile-summary-card">
                    <div className="profile-avatar">{initials}</div>
                    <h2 className="profile-name">{user?.name || 'Usuario'}</h2>
                    <p className="profile-email">{user?.email || 'Email no disponible'}</p>
                    <span className="profile-status">Cuenta activa</span>
                </aside>

                <div className="profile-main-column">
                    <section className="profile-details-card">
                        <div className="profile-card-heading">
                            <h2>Información personal</h2>
                            <p>Estos son los datos registrados en Studina.</p>
                        </div>
                        <dl className="profile-details">
                            <div className="profile-detail">
                                <dt>Nombre completo</dt>
                                <dd>{user?.name || 'No disponible'}</dd>
                            </div>
                            <div className="profile-detail">
                                <dt>Email</dt>
                                <dd>{user?.email || 'No disponible'}</dd>
                            </div>
                        </dl>
                    </section>

                    <section className="profile-details-card ai-settings-card">
                        <div className="profile-card-heading ai-settings-heading">
                            <div>
                                <h2>Proveedor de inteligencia artificial</h2>
                                <p>Elegí quién responde y qué modelos usa cada tarea.</p>
                            </div>
                            {settings && (
                                <span className="ai-settings-source">
                                    {settings.source === 'user' ? 'Personalizada' : 'General'}
                                </span>
                            )}
                        </div>

                        {loading && <p className="ai-settings-state">Cargando configuración…</p>}

                        {!loading && form && (
                            <form className="ai-settings-form" onSubmit={handleSave}>
                                <label className="ai-settings-field">
                                    <span>Proveedor</span>
                                    <select
                                        aria-label="Proveedor"
                                        value={form.provider}
                                        onChange={(event) => changeProvider(event.target.value)}
                                    >
                                        {Object.entries(PROVIDERS).map(([value, provider]) => (
                                            <option key={value} value={value}>{provider.label}</option>
                                        ))}
                                    </select>
                                    <small>{selectedProvider.description}</small>
                                </label>

                                <label className="ai-settings-field">
                                    <span>Modelo para conversar</span>
                                    <input
                                        aria-label="Modelo para conversar"
                                        required
                                        value={form.chatModel}
                                        onChange={(event) => updateField('chatModel', event.target.value)}
                                        placeholder={selectedProvider.defaultModel}
                                    />
                                </label>

                                <div className="ai-model-grid">
                                    <label className="ai-settings-field">
                                        <span>Flashcards y quizzes</span>
                                        <input
                                            aria-label="Flashcards y quizzes"
                                            value={form.generationModel}
                                            onChange={(event) => updateField('generationModel', event.target.value)}
                                        />
                                    </label>
                                    <label className="ai-settings-field">
                                        <span>Clasificar documentos</span>
                                        <input
                                            aria-label="Clasificar documentos"
                                            value={form.classificationModel}
                                            onChange={(event) => updateField('classificationModel', event.target.value)}
                                        />
                                    </label>
                                    <label className="ai-settings-field">
                                        <span>Resumir conversaciones</span>
                                        <input
                                            aria-label="Resumir conversaciones"
                                            value={form.summaryModel}
                                            onChange={(event) => updateField('summaryModel', event.target.value)}
                                        />
                                    </label>
                                </div>

                                {selectedProvider.needsCredential && (
                                    <label className="ai-settings-field">
                                        <span>Tu API key</span>
                                        <input
                                            aria-label="Tu API key"
                                            type="password"
                                            autoComplete="new-password"
                                            value={form.apiKey}
                                            onChange={(event) => updateField('apiKey', event.target.value)}
                                            placeholder={settings?.credentialConfigured
                                                ? 'Ya configurada; dejá vacío para conservarla'
                                                : 'Pegá acá tu API key'}
                                        />
                                        <small>
                                            Se envía al backend, se cifra antes de guardarse y nunca vuelve a mostrarse.
                                        </small>
                                    </label>
                                )}

                                {error && <p className="ai-settings-feedback ai-settings-error">{error}</p>}
                                {message && <p className="ai-settings-feedback ai-settings-success">{message}</p>}

                                <div className="ai-settings-actions">
                                    <button className="ai-settings-save" disabled={saving} type="submit">
                                        {saving ? 'Guardando…' : 'Guardar configuración'}
                                    </button>
                                    {settings?.source === 'user' && (
                                        <button
                                            className="ai-settings-reset"
                                            disabled={saving}
                                            type="button"
                                            onClick={handleReset}
                                        >
                                            Usar configuración general
                                        </button>
                                    )}
                                </div>
                            </form>
                        )}
                    </section>
                </div>
            </div>
        </section>
    )
}
