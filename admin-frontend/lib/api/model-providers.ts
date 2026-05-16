import { http } from './http'
import type { ModelProviderDescriptor } from './types'

const BASE = '/api/admin/v1/model-providers'

export function fetchModelProviders(capability?: string) {
  const q =
    capability && capability.trim() !== ''
      ? `?capability=${encodeURIComponent(capability.trim())}`
      : ''
  return http.get<ModelProviderDescriptor[]>(`${BASE}${q}`)
}
