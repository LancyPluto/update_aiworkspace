import { http } from "./http"
import type { WorkflowResponse, WorkflowVersionItem, UpsertWorkflowPayload, PageResponse } from "./types"

export function fetchWorkflow(toolId: number): Promise<WorkflowResponse> {
  return http.get<WorkflowResponse>(`/api/admin/v1/tools/${toolId}/workflow`)
}

export function saveWorkflow(toolId: number, payload: UpsertWorkflowPayload): Promise<WorkflowResponse> {
  return http.put<WorkflowResponse>(`/api/admin/v1/tools/${toolId}/workflow`, payload)
}

export function fetchWorkflowVersions(toolId: number, pageNo = 1, pageSize = 20): Promise<WorkflowVersionItem[]> {
  return http.get<WorkflowVersionItem[]>(`/api/admin/v1/tools/${toolId}/workflow/versions`, { pageNo, pageSize })
}

export function restoreWorkflowVersion(toolId: number, version: number): Promise<WorkflowResponse> {
  return http.post<WorkflowResponse>(`/api/admin/v1/tools/${toolId}/workflow/versions/${version}/restore`)
}
