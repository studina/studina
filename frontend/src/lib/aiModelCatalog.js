export const CUSTOM_MODEL_VALUE = '__custom__'

export const AI_PROVIDER_CATALOG = {
  'google-genai': {
    label: 'Google Gemini',
    shortLabel: 'Google Gemini',
    description: 'Usa la API de Gemini con tu propia API key.',
    needsCredential: true,
    notice: 'La lista incluye modelos probados en Studina. El acceso, la cuota y la disponibilidad dependen de tu proyecto de Google.',
    models: [], // Supplied by /api/ai/settings; the backend owns the Gemini catalog.
  },
  ollama: {
    label: 'Ollama (servidor)',
    shortLabel: 'Ollama',
    description: 'Usa el Ollama configurado en el servidor de Studina.',
    defaultModel: 'llama3.2',
    needsCredential: false,
    notice: 'Los modelos se ejecutan en el servidor de Ollama configurado para Studina. La lista muestra los instalados allí.',
    models: [],
  },
  openai: {
    label: 'OpenAI / compatible',
    shortLabel: 'OpenAI',
    description: 'Usa OpenAI o el endpoint compatible configurado en el servidor.',
    defaultModel: 'gpt-6-luna',
    needsCredential: true,
    notice: 'La API se factura por separado de ChatGPT. Con saldo $0, OpenAI rechazará las consultas.',
    models: [
      { value: 'gpt-6-luna', label: 'GPT-6 Luna — menor costo' },
      { value: 'gpt-6.1-sol', label: 'GPT-6.1 Sol — equilibrado' },
      { value: 'gpt-6-astra', label: 'GPT-6 Astra — máxima capacidad' },
      { value: 'gpt-4.1-mini', label: 'GPT-4.1 Mini' },
      { value: 'gpt-4.1', label: 'GPT-4.1' },
      { value: 'gpt-4o-mini', label: 'GPT-4o Mini' },
      { value: 'gpt-4o', label: 'GPT-4o' },
    ],
  },
}

export function providerCatalog(provider, availableModels) {
  const catalog = AI_PROVIDER_CATALOG[provider] ?? AI_PROVIDER_CATALOG['google-genai']
  if (provider !== 'google-genai') return catalog
  const models = availableModels?.[provider] ?? []
  return { ...catalog, models, defaultModel: models[0]?.value ?? '' }
}

export function isCatalogModel(provider, model, availableModels) {
  return providerCatalog(provider, availableModels).models.some((option) => option.value === model)
}

export function isUnavailableModel(provider, model, availableModels) {
  return provider === 'google-genai' && !isCatalogModel(provider, model, availableModels)
}
