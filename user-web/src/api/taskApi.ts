import { apiRequest } from "./client"
import { getSessionBearerJwt } from "./sessionBearer"
import type {
  CreateTaskRequest,
  CreateTaskResponse,
  EstimateTaskRequest,
  ListTasksQuery,
  PageResult,
  RegenerateTaskRequest,
  TaskDetail,
  TaskEstimateResponse,
  TaskStatusPayload,
} from "./types"

/** POST /api/v1/tasks */
export async function createTask(
  body: CreateTaskRequest,
  options?: { token?: string | null },
): Promise<CreateTaskResponse> {
  return apiRequest<CreateTaskResponse>("POST", "/api/v1/tasks", {
    body,
    token: options?.token,
  })
}

/** POST /api/v1/tasks/estimate —— 权威实时算力预估（与提交时冻结口径一致） */
export async function estimateTask(
  body: EstimateTaskRequest,
  options?: { token?: string | null; signal?: AbortSignal },
): Promise<TaskEstimateResponse> {
  return apiRequest<TaskEstimateResponse>("POST", "/api/v1/tasks/estimate", {
    body,
    token: options?.token,
    signal: options?.signal,
  })
}

export async function streamTaskStatus(
  taskId: number | string,
  onStatus: (status: TaskStatusPayload) => void,
  options?: { token?: string | null; signal?: AbortSignal },
): Promise<void> {
  const id = encodeURIComponent(String(taskId))
  const headers: Record<string, string> = {
    Accept: "text/event-stream",
  }
  const token = options?.token ?? getSessionBearerJwt()
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(`/api/v1/tasks/${id}/events`, {
    method: "GET",
    headers,
    credentials: "include",
    signal: options?.signal,
  })
  if (!res.ok || !res.body) {
    throw new Error(`任务进度流连接失败 (${res.status})`)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split(/\r?\n\r?\n/)
    buffer = chunks.pop() ?? ""
    for (const chunk of chunks) {
      const data = chunk
        .split(/\r?\n/)
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart())
        .join("\n")
      if (!data) continue
      onStatus(JSON.parse(data) as TaskStatusPayload)
    }
  }
}

/** GET /api/v1/tasks/{taskId}/status —— 轮询任务状态 */
export async function fetchTaskStatus(
  taskId: number | string,
  options?: { token?: string | null; signal?: AbortSignal },
): Promise<TaskStatusPayload> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<TaskStatusPayload>("GET", `/api/v1/tasks/${id}/status`, {
    token: options?.token,
    signal: options?.signal,
  })
}

/** GET /api/v1/tasks/{taskId} —— 任务详情 */
export async function fetchTaskById(
  taskId: number | string,
  options?: { token?: string | null },
): Promise<TaskDetail> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<TaskDetail>("GET", `/api/v1/tasks/${id}`, { token: options?.token })
}

/** GET /api/v1/tasks —— 当前用户任务列表（分页） */
export async function fetchTasks(
  options?: {
    token?: string | null
    query?: ListTasksQuery
  },
): Promise<PageResult<TaskDetail>> {
  return apiRequest<PageResult<TaskDetail>>("GET", "/api/v1/tasks", {
    token: options?.token,
    query: options?.query as Record<string, string | number | boolean | undefined> | undefined,
  })
}

/** POST /api/v1/tasks/{taskId}/regenerate —— 使用历史参数再次生成 */
export async function regenerateTask(
  taskId: number | string,
  body: RegenerateTaskRequest,
  options?: { token?: string | null },
): Promise<TaskStatusPayload> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<TaskStatusPayload>("POST", `/api/v1/tasks/${id}/regenerate`, {
    body,
    token: options?.token,
  })
}

/** POST /api/v1/tasks/{taskId}/cancel —— 取消任务 */
export async function cancelTask(
  taskId: number | string,
  options?: { token?: string | null },
): Promise<TaskStatusPayload> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<TaskStatusPayload>("POST", `/api/v1/tasks/${id}/cancel`, {
    token: options?.token,
  })
}

/** POST /api/v1/tasks/{taskId}/workflow-feedback —— 工作流阶段意见/继续 */
export async function submitWorkflowFeedback(
  taskId: number | string,
  fields: Record<string, string>,
  options?: { token?: string | null },
): Promise<TaskStatusPayload> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<TaskStatusPayload>("POST", `/api/v1/tasks/${id}/workflow-feedback`, {
    body: { fields },
    token: options?.token,
  })
}

/** DELETE /api/v1/tasks/{taskId} - 从当前用户素材库/任务列表隐藏任务 */
export async function deleteTask(
  taskId: number | string,
  options?: { token?: string | null },
): Promise<void> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<void>("DELETE", `/api/v1/tasks/${id}`, {
    token: options?.token,
  })
}
