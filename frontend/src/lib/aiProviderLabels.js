const PROVIDER_LABELS = {
  'google-genai': 'Google Gemini',
  ollama: 'Ollama',
  openai: 'OpenAI',
}

export function providerLabel(provider) {
  return PROVIDER_LABELS[provider] || provider || 'IA'
}
