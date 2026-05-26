import { http } from "./http"
import type { AgentModelConfig } from "./types"

export interface PptWorkflowStep {
  code: string
  name: string
  credits: number
  enabled: boolean
}

export interface PptWorkflow {
  integrationMode?: string
  customUiRoute?: string
  creationTypes?: string[]
  steps?: PptWorkflowStep[]
  features?: Record<string, boolean>
  textModelConfigId?: number | null
  imageModelConfigId?: number | null
}

export interface PptAdminWorkflowDetail {
  workflow: PptWorkflow
  textModel: AgentModelConfig | null
  imageModel: AgentModelConfig | null
  engineSynced: boolean
  engineSyncMessage: string | null
}

export interface PptEngineHealth {
  healthy: boolean
  status: string
}

export function fetchPptWorkflow(toolId: number) {
  return http.get<PptAdminWorkflowDetail>(`/api/admin/v1/tools/${toolId}/ppt-workflow`)
}

export function updatePptWorkflow(toolId: number, workflow: PptWorkflow) {
  return http.put<PptAdminWorkflowDetail>(`/api/admin/v1/tools/${toolId}/ppt-workflow`, { workflow })
}

export function syncPptEngineSettings(toolId: number) {
  return http.post<PptAdminWorkflowDetail>(`/api/admin/v1/tools/${toolId}/ppt-workflow/sync-engine`)
}

export function fetchPptEngineHealth() {
  return http.get<PptEngineHealth>("/api/admin/v1/ppt/engine-health")
}
