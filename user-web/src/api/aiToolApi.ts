import { apiRequest, ApiBusinessError } from "./client"
import type {
  AITool,
  ChatMessage,
  ChatRequest,
  ChatResponse,
  ChatSession,
  FileUploadResult,
} from "./aiToolTypes"
import {
  isMockMode,
  mockCreateChatSession,
  mockDeleteChatSession,
  mockFetchAIToolById,
  mockFetchChatMessages,
  mockFetchChatSessions,
  mockFetchEnabledAITools,
  mockSendChatMessage,
  mockUploadChatFile,
} from "./aiToolMock"

async function withMockFallback<T>(request: () => Promise<T>, mock: () => Promise<T>): Promise<T> {
  if (isMockMode()) return mock()
  return request()
}

/** GET /api/v1/ai-tools — 已上架列表，按 order 排序 */
export async function fetchEnabledAITools(options?: { token?: string | null }): Promise<AITool[]> {
  return withMockFallback(
    () => apiRequest<AITool[]>("GET", "/api/v1/ai-tools", { token: options?.token }),
    mockFetchEnabledAITools,
  )
}

/** GET /api/v1/ai-tools/{toolId} */
export async function fetchAIToolById(
  toolId: string,
  options?: { token?: string | null },
): Promise<AITool> {
  return withMockFallback(
    () =>
      apiRequest<AITool>("GET", `/api/v1/ai-tools/${encodeURIComponent(toolId)}`, {
        token: options?.token,
      }),
    () => mockFetchAIToolById(toolId),
  )
}

/** GET /api/v1/sessions?toolId= */
export async function fetchChatSessions(
  toolId: string,
  options?: { token?: string | null },
): Promise<ChatSession[]> {
  return withMockFallback(
    () =>
      apiRequest<ChatSession[]>("GET", "/api/v1/sessions", {
        token: options?.token,
        query: { toolId },
      }),
    () => mockFetchChatSessions(toolId),
  )
}

/** POST /api/v1/sessions */
export async function createChatSession(
  toolId: string,
  options?: { token?: string | null },
): Promise<ChatSession> {
  return withMockFallback(
    () =>
      apiRequest<ChatSession>("POST", "/api/v1/sessions", {
        token: options?.token,
        body: { toolId },
      }),
    () => mockCreateChatSession(toolId),
  )
}

/** DELETE /api/v1/sessions/{sessionId} */
export async function deleteChatSession(
  sessionId: string,
  options?: { token?: string | null },
): Promise<void> {
  return withMockFallback(
    () =>
      apiRequest<void>("DELETE", `/api/v1/sessions/${encodeURIComponent(sessionId)}`, {
        token: options?.token,
      }),
    () => mockDeleteChatSession(sessionId),
  )
}

/** GET /api/v1/messages?sessionId= */
export async function fetchChatMessages(
  sessionId: string,
  options?: { token?: string | null },
): Promise<ChatMessage[]> {
  return withMockFallback(
    () =>
      apiRequest<ChatMessage[]>("GET", "/api/v1/messages", {
        token: options?.token,
        query: { sessionId },
      }),
    () => mockFetchChatMessages(sessionId),
  )
}

/** POST /api/v1/chat/messages */
export async function sendChatMessage(
  payload: ChatRequest,
  options?: { token?: string | null; signal?: AbortSignal },
): Promise<ChatResponse> {
  return withMockFallback(
    () =>
      apiRequest<ChatResponse>("POST", "/api/v1/chat/messages", {
        token: options?.token,
        body: payload,
        signal: options?.signal,
      }),
    () => mockSendChatMessage(payload),
  )
}

/** POST /api/v1/upload */
export async function uploadChatFile(
  file: File,
  options?: { token?: string | null },
): Promise<FileUploadResult> {
  return withMockFallback(
    () => {
      const formData = new FormData()
      formData.append("file", file)
      return apiRequest<FileUploadResult>("POST", "/api/v1/upload", {
        token: options?.token,
        body: formData,
      })
    },
    () => mockUploadChatFile(file),
  )
}
