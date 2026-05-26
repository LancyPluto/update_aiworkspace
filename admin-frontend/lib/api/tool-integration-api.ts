import { http } from "./http"

export type ToolApiFieldType = "SECRET" | "URL" | "TEXT"

export interface ToolEngineApiFieldDefinition {
  key: string
  label: string
  description: string
  fieldType: ToolApiFieldType
  enginePayloadKey: string
  endpointHint: string | null
  docUrl: string | null
}

export interface ToolModelBindingDefinition {
  bindingKey: string
  label: string
  description: string
  requiredCapability: string
  endpointHint: string | null
}

export interface ToolIntegrationApiCatalog {
  pluginId: string
  displayName: string
  syncHint: string
  modelBindings: ToolModelBindingDefinition[]
  engineApiFields: ToolEngineApiFieldDefinition[]
}

export function fetchIntegrationApiCatalog(pluginId: string) {
  return http.get<ToolIntegrationApiCatalog>(`/api/admin/v1/integration-plugins/${pluginId}/api-catalog`)
}
