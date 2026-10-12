export const CUSTOM_MODEL_VALUE = '__custom__'

export const AI_PROVIDER_CATALOG = {
  'google-genai': {
    label: 'Google Gemini',
    shortLabel: 'Google Gemini',
    description: 'Usa la API de Gemini con tu propia API key.',
    defaultModel: 'gemini-3.8-flash',
    needsCredential: true,
    notice: 'Gemini puede ofrecer una cuota gratuita según tu cuenta y región.',
    models: [
      { value: 'gemini-3.8-flash', label: 'Gemini 3.8 Flash — recomendado' },
      { value: 'gemini-3.5-flash-lite', label: 'Gemini 3.5 Flash-Lite — económico' },
      { value: 'gemini-3.7-flash', label: 'Gemini 3.7 Flash' },
      { value: 'gemini-3.6-flash', label: 'Gemini 3.6 Flash' },
      { value: 'gemini-3.5-flash', label: 'Gemini 3.5 Flash' },
      { value: 'gemini-3.1-flash-lite', label: 'Gemini 3.1 Flash-Lite' },
      { value: 'gemini-2.5-flash', label: 'Gemini 2.5 Flash — acceso legado' },
      { value: 'gemini-2.5-flash-lite', label: 'Gemini 2.5 Flash-Lite — acceso legado' },
      { value: 'gemini-2.5-pro', label: 'Gemini 2.5 Pro — acceso legado' },
    ],
  },
  ollama: {
    label: 'Ollama local',
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

export function providerCatalog(provider) {
  return AI_PROVIDER_CATALOG[provider] ?? AI_PROVIDER_CATALOG['google-genai']
}

export function isCatalogModel(provider, model) {
  return providerCatalog(provider).models.some((option) => option.value === model)
}
