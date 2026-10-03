import { AI_PROVIDER_CATALOG } from './aiModelCatalog'

export function providerLabel(provider) {
  return AI_PROVIDER_CATALOG[provider]?.shortLabel || provider || 'IA'
}
