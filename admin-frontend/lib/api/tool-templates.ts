import { http } from './http'
import type { ToolField, ToolFieldPayload } from './types'

export interface ToolTemplateSummary {
  id: number
  templateCode: string
  templateName: string
  toolType: string
  executionHandler: string
  inputModality: string
  outputModality: string
  configNote?: string | null
  suggestedModelConfigId?: number | null
  status: string
  sortOrder?: number
  systemTemplate?: boolean
}

export interface ToolTemplateDetail extends ToolTemplateSummary {
  defaultSystemPrompt?: string | null
  defaultUserPromptTemplate?: string | null
  defaultOutputFormat?: string | null
  handlerConfigJson?: string | null
  fields: ToolField[]
}

export interface ApplyToolTemplatePayload {
  templateCode: string
  applyMetadata?: boolean
  overwritePrompt?: boolean
}

export function fetchToolTemplates(params?: { toolType?: string; executionHandler?: string }) {
  const query = new URLSearchParams()
  if (params?.toolType) query.set('toolType', params.toolType)
  if (params?.executionHandler) query.set('executionHandler', params.executionHandler)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<ToolTemplateSummary[]>(`/api/admin/v1/tool-templates${suffix}`)
}

export function fetchToolTemplateDetail(templateCode: string) {
  return http.get<ToolTemplateDetail>(`/api/admin/v1/tool-templates/${templateCode}`)
}

export function applyToolTemplate(toolId: number, payload: ApplyToolTemplatePayload) {
  return http.post<void>(`/api/admin/v1/tools/${toolId}/apply-template`, payload)
}

export function templateFieldsToPayload(fields: ToolField[]): ToolFieldPayload[] {
  return fields.map((field) => ({
    fieldKey: field.fieldKey,
    fieldName: field.fieldName,
    fieldType: field.fieldType,
    placeholder: field.placeholder ?? undefined,
    optionsJson: field.optionsJson ?? (field.options ? JSON.stringify(field.options) : undefined),
    required: field.required,
    sortOrder: field.sortOrder,
  }))
}
