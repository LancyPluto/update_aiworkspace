import { http, unwrap } from './http'
import type { FieldItem, FieldSchema } from '@/types'

export interface CreateFieldSchemaPayload {
  schemaVersion: string
  fields: FieldItem[]
}

export function fetchFieldSchemas(toolId: number) {
  return unwrap<FieldSchema[]>(http.get(`/api/admin/v1/tools/${toolId}/field-schemas`))
}

export function createFieldSchema(toolId: number, payload: CreateFieldSchemaPayload) {
  return unwrap<FieldSchema>(http.post(`/api/admin/v1/tools/${toolId}/field-schemas`, payload))
}

export function publishFieldSchema(schemaId: number) {
  return unwrap<FieldSchema>(http.post(`/api/admin/v1/field-schemas/${schemaId}/publish`))
}
