import { apiRequest } from "./client"
import type {
  CreateTaskRequest,
  CreateTaskResponse,
  ListTasksQuery,
  PageResult,
  TaskDetail,
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

/** GET /api/v1/tasks/{taskId}/status —— 轮询任务状态 */
export async function fetchTaskStatus(
  taskId: number | string,
  options?: { token?: string | null },
): Promise<TaskStatusPayload> {
  const id = encodeURIComponent(String(taskId))
  return apiRequest<TaskStatusPayload>("GET", `/api/v1/tasks/${id}/status`, {
    token: options?.token,
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
