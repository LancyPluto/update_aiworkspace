import { apiRequest } from "./client"
import type { ToolField } from "./types"

export type WorkflowRunStatus =
  | "QUEUED"
  | "RUNNING"
  | "AWAITING_USER"
  | "AWAITING_FUNDS"
  | "CANCELLING"
  | "SUCCESS"
  | "FAILED"
  | "TIMEOUT"
  | "CANCELLED"
  | (string & {})

export type WorkflowStepStatus =
  | "PENDING"
  | "READY"
  | "QUEUED"
  | "RUNNING"
  | "AWAITING_USER"
  | "SUCCESS"
  | "FAILED"
  | "SKIPPED"
  | "CANCELLED"
  | (string & {})

export type WorkflowFeedbackAction = "APPROVE" | "REJECT" | "CONTINUE_WITH_FEEDBACK" | "CANCEL"
export type WorkflowArtifactType = "TEXT" | "JSON" | "IMAGE" | "AUDIO" | "VIDEO" | "FILE" | (string & {})

export interface WorkflowToolSummary {
  id?: number | string
  toolCode: string
  toolName: string
  description?: string | null
  categoryName?: string | null
  coverUrl?: string | null
  status?: string | null
  estimatedCreditCost?: number | null
  minimumRequiredCredits?: number | null
  variableCreditPricing?: boolean | null
  adapterKey?: string | null
}

export interface WorkflowToolDetail extends WorkflowToolSummary {
  fields?: ToolField[]
  inputSchema?: Record<string, unknown> | null
  workflowVersion?: number | string | null
  estimatedDurationSeconds?: number | null
  adapterData?: Record<string, unknown> | null
}

export interface WorkflowToolPage {
  items: WorkflowToolSummary[]
  total: number
  page?: number
  pageSize?: number
  hasNext?: boolean
}

export interface WorkflowArtifact {
  id?: string | number
  artifactId?: string | number
  stepId?: string | number | null
  type: WorkflowArtifactType
  name?: string | null
  title?: string | null
  url?: string | null
  downloadUrl?: string | null
  content?: unknown
  value?: unknown
  mimeType?: string | null
  size?: number | null
  adapterKey?: string | null
  adapterData?: Record<string, unknown> | null
}

export interface WorkflowStepCharge {
  id?: string | number
  stepId?: string | number | null
  status?: "RESERVED" | "CAPTURED" | "RELEASED" | string | null
  reservedCredits?: number | null
  capturedCredits?: number | null
  actualCredits?: number | null
}

export interface WorkflowFeedbackField {
  fieldKey: string
  fieldName: string
  fieldType?: "text" | "textarea" | "select" | "checkbox" | string
  required?: boolean
  placeholder?: string | null
  defaultValue?: unknown
  options?: Array<string | { label: string; value: string }> | null
}

export interface WorkflowUserAction {
  stepId: string | number
  prompt?: string | null
  fields?: WorkflowFeedbackField[] | null
  allowedActions?: WorkflowFeedbackAction[] | null
  confirmationToken: string
  expiresAt?: string | null
}

export interface WorkflowRunStep {
  id?: string | number
  stepId: string | number
  stepCode?: string | null
  stepName?: string | null
  nodeType?: string | null
  status: WorkflowStepStatus
  progress?: number | null
  progressMessage?: string | null
  startedAt?: string | null
  completedAt?: string | null
  errorCode?: string | null
  errorMessage?: string | null
  artifacts?: WorkflowArtifact[] | null
  charges?: WorkflowStepCharge[] | null
}

export interface WorkflowCostData {
  totalCredits?: number | null
  reservedCredits?: number | null
  capturedCredits?: number | null
  releasedCredits?: number | null
  charges?: WorkflowStepCharge[] | null
}

export interface WorkflowRun {
  taskId: string | number
  runId?: string | number
  taskNo?: string | null
  toolCode?: string | null
  toolName?: string | null
  status: WorkflowRunStatus
  progress?: number | null
  progressMessage?: string | null
  currentStepId?: string | number | null
  createdAt?: string | null
  startedAt?: string | null
  updatedAt?: string | null
  completedAt?: string | null
  errorCode?: string | null
  errorMessage?: string | null
  steps?: WorkflowRunStep[] | null
  artifacts?: WorkflowArtifact[] | null
  cost?: WorkflowCostData | null
  totalCredits?: number | null
  reservedCredits?: number | null
  userAction?: WorkflowUserAction | null
  confirmation?: WorkflowUserAction | null
  adapterKey?: string | null
  adapterData?: Record<string, unknown> | null
  revision?: number | null
}

export interface CreateWorkflowRunRequest {
  input: Record<string, unknown>
  clientRequestId: string
}

export interface CreateWorkflowRunResponse {
  taskId: string | number
  runId?: string | number
  taskNo?: string | null
  status?: WorkflowRunStatus
}

export interface WorkflowFeedbackRequest {
  stepId: string | number
  action: WorkflowFeedbackAction
  fields: Record<string, unknown>
  confirmationToken: string
  idempotencyKey: string
}

export interface WorkflowRequestOptions {
  token?: string | null
  signal?: AbortSignal
}

export interface ListWorkflowToolsOptions extends WorkflowRequestOptions {
  query?: Record<string, string | number | boolean | undefined> & {
    page?: number
    pageSize?: number
    keyword?: string
    category?: string
  }
}

type Request = <T>(
  method: string,
  path: string,
  options?: WorkflowRequestOptions & {
    query?: Record<string, string | number | boolean | undefined>
    body?: unknown
  },
) => Promise<T>

function encoded(value: string | number): string {
  return encodeURIComponent(String(value))
}

export function createWorkflowApi(request: Request) {
  return {
    listTools(options?: ListWorkflowToolsOptions): Promise<WorkflowToolPage> {
      const requestOptions: ListWorkflowToolsOptions = {}
      if (options?.query) requestOptions.query = options.query
      if (options?.token !== undefined) requestOptions.token = options.token
      if (options?.signal !== undefined) requestOptions.signal = options.signal
      return request<WorkflowToolPage>("GET", "/api/v1/agents/tools", requestOptions)
    },
    getTool(toolCode: string, options?: WorkflowRequestOptions): Promise<WorkflowToolDetail> {
      return request<WorkflowToolDetail>("GET", `/api/v1/agents/tools/${encoded(toolCode)}`, options)
    },
    createRun(
      toolCode: string,
      body: CreateWorkflowRunRequest,
      options?: WorkflowRequestOptions,
    ): Promise<CreateWorkflowRunResponse> {
      return request<CreateWorkflowRunResponse>("POST", `/api/v1/agents/tools/${encoded(toolCode)}/runs`, {
        ...options,
        body,
      })
    },
    getRun(taskId: string | number, options?: WorkflowRequestOptions): Promise<WorkflowRun> {
      return request<WorkflowRun>("GET", `/api/v1/agents/runs/${encoded(taskId)}`, options)
    },
    cancelRun(taskId: string | number, options?: WorkflowRequestOptions): Promise<WorkflowRun> {
      return request<WorkflowRun>("POST", `/api/v1/agents/runs/${encoded(taskId)}/cancel`, options)
    },
    resumeRun(taskId: string | number, options?: WorkflowRequestOptions): Promise<WorkflowRun> {
      return request<WorkflowRun>("POST", `/api/v1/agents/runs/${encoded(taskId)}/resume`, options)
    },
    submitFeedback(
      taskId: string | number,
      body: WorkflowFeedbackRequest,
      options?: WorkflowRequestOptions,
    ): Promise<WorkflowRun> {
      return request<WorkflowRun>("POST", `/api/v1/agents/runs/${encoded(taskId)}/feedback`, {
        ...options,
        body,
      })
    },
  }
}

const workflowApi = createWorkflowApi(apiRequest)

export const listTools = workflowApi.listTools
export const getTool = workflowApi.getTool
export const createRun = workflowApi.createRun
export const getRun = workflowApi.getRun
export const cancelRun = workflowApi.cancelRun
export const resumeRun = workflowApi.resumeRun
export const submitFeedback = workflowApi.submitFeedback
