import { useEffect, useState } from 'react'
import {
    loadAiSettings,
    resetAiSettings,
    saveAiSettings,
} from '../../services/aiSettingsService'
import {
    AI_PROVIDER_CATALOG,
    CUSTOM_MODEL_VALUE,
    isCatalogModel,
    providerCatalog,
} from '../../lib/aiModelCatalog'
import './ProfilePage.css'

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

function ModelSelect({ provider, label, value, onChange, required = false }) {
    const catalog = providerCatalog(provider)
    const catalogModel = isCatalogModel(provider, value)

    return (
        <label className="ai-settings-field">
            <span>{label}</span>
            <select
                aria-label={label}
                value={catalogModel ? value : CUSTOM_MODEL_VALUE}
                onChange={(event) => onChange(
                    event.target.value === CUSTOM_MODEL_VALUE ? '' : event.target.value,
                )}
            >
                {catalog.models.map((model) => (
                    <option key={model.value} value={model.value}>{model.label}</option>
                ))}
                <option value={CUSTOM_MODEL_VALUE}>Otro modelo compatible…</option>
            </select>
            {!catalogModel && (
                <input
                    aria-label={`${label}: modelo personalizado`}
                    required={required}
                    value={value}
                    onChange={(event) => onChange(event.target.value)}
                    placeholder="Escribí el identificador exacto del modelo"
                />
            )}
        </label>
    )
}

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
        loadAiSettings()
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
        const defaultModel = providerCatalog(provider).defaultModel
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
            const saved = await saveAiSettings(form)
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
            const restored = await resetAiSettings()
            setSettings(restored)
            setForm(toForm(restored))
            setMessage('Volviste a la configuración general de Studina.')
        } catch (requestError) {
            setError(requestError.message)
        } finally {
            setSaving(false)
        }
    }

    const selectedProvider = form ? providerCatalog(form.provider) : null
    const credentialConfiguredForProvider = Boolean(
        settings?.source === 'user'
        && settings.provider === form?.provider
        && settings.credentialConfigured,
    )

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
                                        {Object.entries(AI_PROVIDER_CATALOG).map(([value, provider]) => (
                                            <option key={value} value={value}>{provider.label}</option>
                                        ))}
                                    </select>
                                    <small>{selectedProvider.description}</small>
                                </label>

                                <div className={`ai-provider-notice ai-provider-notice--${form.provider}`}>
                                    <strong>{selectedProvider.label}</strong>
                                    <span>{selectedProvider.notice}</span>
                                </div>

                                <ModelSelect
                                    provider={form.provider}
                                    label="Modelo para conversar"
                                    required
                                    value={form.chatModel}
                                    onChange={(value) => updateField('chatModel', value)}
                                />

                                <div className="ai-model-grid">
                                    <ModelSelect
                                        provider={form.provider}
                                        label="Flashcards y quizzes"
                                        value={form.generationModel}
                                        onChange={(value) => updateField('generationModel', value)}
                                    />
                                    <ModelSelect
                                        provider={form.provider}
                                        label="Clasificar documentos"
                                        value={form.classificationModel}
                                        onChange={(value) => updateField('classificationModel', value)}
                                    />
                                    <ModelSelect
                                        provider={form.provider}
                                        label="Resumir conversaciones"
                                        value={form.summaryModel}
                                        onChange={(value) => updateField('summaryModel', value)}
                                    />
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
                                            placeholder={credentialConfiguredForProvider
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
