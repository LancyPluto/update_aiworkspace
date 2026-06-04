import { http } from './http'
import type { ModelVendor, ModelVendorPayload } from './types'

const BASE = '/api/admin/v1/model-vendors'

export function fetchModelVendors() {
  return http.get<ModelVendor[]>(BASE)
}

export function upsertModelVendor(payload: ModelVendorPayload) {
  return http.put<ModelVendor>(BASE, payload)
}
