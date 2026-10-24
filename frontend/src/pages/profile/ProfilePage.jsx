import { useEffect, useState } from 'react'
import {
    loadAiSettings,
    loadOllamaModels,
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

const modelFields = ['chatModel', 'generationModel', 'classificationModel', 'summaryModel']

const canonicalOllamaName = (name) => (
    !name || name.split('/').at(-1).includes(':') ? name : `${name}:latest`
)

const installedOllamaName = (models, name) => (
    name ? models.find((model) => canonicalOllamaName(model) === canonicalOllamaName(name)) : undefined
)

function ModelSelect({ provider, label, value, onChange, required = false, ollamaCatalog }) {
    const catalog = providerCatalog(provider)
    const catalogModel = isCatalogModel(provider, value)
    const local = provider === 'ollama'
    const installedName = local ? installedOllamaName(ollamaCatalog.models, value) : undefined
    const missing = local && ollamaCatalog.status === 'ready' && value && !installedName
    const options = local
        ? ollamaCatalog.models.map((name) => ({ value: name, label: name }))
        : catalog.models
    const selectedValue = local
        ? (ollamaCatalog.status === 'ready' ? installedName || value || '' : '')
        : (catalogModel ? value : CUSTOM_MODEL_VALUE)

    return (
        <label className="ai-settings-field">
            <span>{label}</span>
            <select
                aria-label={label}
                value={selectedValue}
                disabled={local && (ollamaCatalog.status !== 'ready' || options.length === 0)}
                onChange={(event) => onChange(
                    event.target.value === CUSTOM_MODEL_VALUE ? '' : event.target.value,
                )}
            >
                {local && (
                    <option value="" disabled>Elegí un modelo instalado</option>
                )}
                {missing && <option value={value} disabled>{value} (no instalado)</option>}
                {options.map((model) => (
                    <option key={model.value} value={model.value}>{model.label}</option>
                ))}
                {!local && <option value={CUSTOM_MODEL_VALUE}>Otro modelo compatible…</option>}
            </select>
            {!local && !catalogModel && (
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
    const [ollamaCatalog, setOllamaCatalog] = useState({ status: 'loading', models: [], error: '' })
    const [catalogRefresh, setCatalogRefresh] = useState(0)
    const provider = form?.provider

    useEffect(() => {
        let active = true
        loadAiSettings()
            .then((loaded) => {
                if (!active) return
                setSettings(loaded)
                setForm(toForm(loaded))
                setOllamaCatalog({ status: 'loading', models: [], error: '' })
                setCatalogRefresh((current) => current + 1)
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

    useEffect(() => {
        if (provider !== 'ollama') return
        let active = true
        loadOllamaModels()
            .then((models) => {
                if (!active) return
                setOllamaCatalog({ status: 'ready', models, error: '' })
                setForm((current) => {
                    if (current?.provider !== 'ollama' || !models.length) return current
                    const defaultModel = current.chatModel || models[0]
                    return {
                        ...current,
                        ...Object.fromEntries(modelFields.map((field) => [field, current[field] || defaultModel])),
                    }
                })
            })
            .catch((requestError) => {
                if (active) setOllamaCatalog({ status: 'error', models: [], error: requestError.message })
            })
        return () => {
            active = false
        }
    }, [provider, user.userId, catalogRefresh])

    const refreshOllamaModels = () => {
        setOllamaCatalog({ status: 'loading', models: [], error: '' })
        setCatalogRefresh((current) => current + 1)
    }

    const updateField = (field, value) => {
        setForm((current) => ({ ...current, [field]: value }))
        setMessage('')
        setError('')
    }

    const changeProvider = (provider) => {
        const defaultModel = provider === 'ollama' ? '' : providerCatalog(provider).defaultModel
        setOllamaCatalog({ status: 'loading', models: [], error: '' })
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
        setMessage('')
        setError('')
        if (missingCredential) {
            setError(credentialHelp)
            return
        }
        setSaving(true)
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
    const validOllamaModels = provider !== 'ollama' || (
        ollamaCatalog.status === 'ready'
        && ollamaCatalog.models.length > 0
        && modelFields.every((field) => installedOllamaName(ollamaCatalog.models, form[field]))
    )
    const credentialConfiguredForProvider = Boolean(
        settings?.source === 'user'
        && settings.provider === form?.provider
        && settings.credentialConfigured,
    )
    const credentialRequired = Boolean(selectedProvider?.needsCredential && !credentialConfiguredForProvider)
    const missingCredential = credentialRequired && !form.apiKey.trim()
    const credentialHelp = credentialConfiguredForProvider
        ? 'Dejá el campo vacío para conservar tu API key guardada.'
        : `Ingresá tu API key de ${selectedProvider?.shortLabel} para guardar una configuración personal.`

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
                        {error && <p className="ai-settings-feedback ai-settings-error" role="alert">{error}</p>}

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

                                {form.provider === 'ollama' && (
                                    <div className="ai-installed-models">
                                        {ollamaCatalog.status === 'loading' && (
                                            <p className="ai-settings-state" role="status">Consultando modelos instalados…</p>
                                        )}
                                        {ollamaCatalog.status === 'error' && (
                                            <p className="ai-settings-feedback ai-settings-error" role="alert">
                                                {ollamaCatalog.error}
                                            </p>
                                        )}
                                        {ollamaCatalog.status === 'ready' && ollamaCatalog.models.length === 0 && (
                                            <p className="ai-settings-state" role="status">
                                                No hay modelos descargados. Pedile al responsable de Studina que instale uno y actualizá la lista.
                                            </p>
                                        )}
                                        {ollamaCatalog.status === 'ready' && ollamaCatalog.models.length > 0 && !validOllamaModels && (
                                            <p className="ai-settings-feedback ai-settings-error" role="alert">
                                                Algún modelo de tu configuración ya no está instalado. Elegí un modelo de la lista para poder guardar.
                                            </p>
                                        )}
                                        <button
                                            type="button"
                                            className="ai-settings-reset"
                                            disabled={ollamaCatalog.status === 'loading' || saving}
                                            onClick={refreshOllamaModels}
                                        >
                                            Actualizar modelos
                                        </button>
                                    </div>
                                )}

                                <ModelSelect
                                    provider={form.provider}
                                    label="Modelo para conversar"
                                    required
                                    value={form.chatModel}
                                    onChange={(value) => updateField('chatModel', value)}
                                    ollamaCatalog={ollamaCatalog}
                                />

                                <div className="ai-model-grid">
                                    <ModelSelect
                                        provider={form.provider}
                                        label="Flashcards y quizzes"
                                        value={form.generationModel}
                                        onChange={(value) => updateField('generationModel', value)}
                                        ollamaCatalog={ollamaCatalog}
                                    />
                                    <ModelSelect
                                        provider={form.provider}
                                        label="Clasificar documentos"
                                        value={form.classificationModel}
                                        onChange={(value) => updateField('classificationModel', value)}
                                        ollamaCatalog={ollamaCatalog}
                                    />
                                    <ModelSelect
                                        provider={form.provider}
                                        label="Resumir conversaciones"
                                        value={form.summaryModel}
                                        onChange={(value) => updateField('summaryModel', value)}
                                        ollamaCatalog={ollamaCatalog}
                                    />
                                </div>

                                {selectedProvider.needsCredential && (
                                    <label className="ai-settings-field">
                                        <span>Tu API key</span>
                                        <input
                                            aria-label="Tu API key"
                                            type="password"
                                            autoComplete="new-password"
                                            required={credentialRequired}
                                            aria-describedby="ai-api-key-help"
                                            aria-invalid={missingCredential || undefined}
                                            value={form.apiKey}
                                            onChange={(event) => updateField('apiKey', event.target.value)}
                                            placeholder={credentialConfiguredForProvider
                                                ? 'Ya configurada; dejá vacío para conservarla'
                                                : 'Pegá acá tu API key'}
                                        />
                                        <small id="ai-api-key-help">{credentialHelp}</small>
                                        <small>
                                            Se envía al backend, se cifra antes de guardarse y nunca vuelve a mostrarse.
                                        </small>
                                    </label>
                                )}

                                {message && <p className="ai-settings-feedback ai-settings-success">{message}</p>}

                                <div className="ai-settings-actions">
                                    <button className="ai-settings-save" disabled={saving || !validOllamaModels || missingCredential} type="submit">
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
