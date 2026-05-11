import { apiRequest } from "./client"
import type {
  AiTask,
  CreateTaskRequest,
  CreateTaskResponse,
  ListTasksQuery,
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

/** GET /api/v1/tasks/{taskId}/status —— V1 轮询任务状态 */
export async function fetchTaskStatus(
  taskId: number | string,
  options?: { token?: string | null },
): Promise<TaskStatusPayload> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<TaskStatusPayload>("GET", `/api/v1/tasks/${id}/status`, {
    token: options?.token,
  })
}

/** GET /api/v1/tasks/{taskId} */
export async function fetchTaskById(
  taskId: number | string,
  options?: { token?: string | null },
): Promise<AiTask> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<AiTask>("GET", `/api/v1/tasks/${id}`, { token: options?.token })
}

/** GET /api/v1/tasks */
export async function fetchTasks(
  options?: {
    token?: string | null
    query?: ListTasksQuery
  },
): Promise<AiTask[]> {
  return apiRequest<AiTask[]>("GET", "/api/v1/tasks", {
    token: options?.token,
    query: options?.query as Record<string, string | number | boolean | undefined> | undefined,
  })
}
