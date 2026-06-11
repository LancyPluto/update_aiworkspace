import { http } from "./http"
import type {
  WorkflowResponse,
  WorkflowValidationResult,
  WorkflowVersionItem,
  UpsertWorkflowPayload,
  PageResponse,
} from "./types"

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

/** 校验当前已保存的工作流 DAG，返回错误列表（不修改数据） */
export function validateWorkflow(toolId: number): Promise<WorkflowValidationResult> {
  return http.post<WorkflowValidationResult>(`/api/admin/v1/tools/${toolId}/workflow/validate`)
}

/** 校验并发布工作流（status=PUBLISHED）；运行端只执行 PUBLISHED 工作流 */
export function publishWorkflow(toolId: number): Promise<WorkflowResponse> {
  return http.post<WorkflowResponse>(`/api/admin/v1/tools/${toolId}/workflow/publish`)
}

/** 将工作流退回 DRAFT（运行端回退到工具原有 handler） */
export function unpublishWorkflow(toolId: number): Promise<WorkflowResponse> {
  return http.post<WorkflowResponse>(`/api/admin/v1/tools/${toolId}/workflow/unpublish`)
}
