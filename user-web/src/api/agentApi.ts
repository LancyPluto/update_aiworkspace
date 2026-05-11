import { apiRequest, getApiOrigin } from "./client"
import { getSessionBearerJwt } from "./sessionBearer"
import type {
  AgentMessage,
  AgentRun,
  AgentRunEvent,
  AgentSession,
  AgentFile,
  AgentToolPreference,
  AgentWorkspace,
  AgentWorkspaceMemoryItem,
  CreateAgentMessageResponse,
  CreateAgentWorkspaceMemoryRequest,
  PageResult,
  UpdateAgentWorkspaceMemoryRequest,
} from "./types"

export function fetchAgentSessions(options?: { token?: string | null }) {
  return apiRequest<PageResult<AgentSession>>("GET", "/api/v1/agent/sessions", {
    token: options?.token,
    query: { pageNo: 1, pageSize: 30 },
  })
}

export function fetchAgentWorkspaces(options?: { token?: string | null }) {
  return apiRequest<PageResult<AgentWorkspace>>("GET", "/api/v1/agent/workspaces", {
    token: options?.token,
  })
}

export function createAgentSession(body: { title?: string }, options?: { token?: string | null }) {
  return apiRequest<AgentSession>("POST", "/api/v1/agent/sessions", {
    token: options?.token,
    body,
  })
}

export function fetchAgentMessages(sessionId: number, options?: { token?: string | null }) {
  return apiRequest<PageResult<AgentMessage>>("GET", `/api/v1/agent/sessions/${sessionId}/messages`, {
    token: options?.token,
    query: { pageNo: 1, pageSize: 100 },
  })
}

export function sendAgentMessage(
  sessionId: number,
  body: { content: string; clientRequestId?: string },
  options?: { token?: string | null },
) {
  return apiRequest<CreateAgentMessageResponse>("POST", `/api/v1/agent/sessions/${sessionId}/messages`, {
    token: options?.token,
    body,
  })
}

export function fetchAgentFiles(sessionId: number, options?: { token?: string | null }) {
  return apiRequest<PageResult<AgentFile>>("GET", `/api/v1/agent/sessions/${sessionId}/files`, {
    token: options?.token,
  })
}

export function uploadAgentFile(sessionId: number, file: File, options?: { token?: string | null }) {
  const body = new FormData()
  body.append("file", file)
  return apiRequest<AgentFile>("POST", `/api/v1/agent/sessions/${sessionId}/files`, {
    token: options?.token,
    body,
  })
}

export function fetchAgentRunEvents(runId: number, options?: { token?: string | null; afterEventId?: number }) {
  return apiRequest<PageResult<AgentRunEvent>>("GET", `/api/v1/agent/runs/${runId}/events`, {
    token: options?.token,
    query: { afterEventId: options?.afterEventId, pageSize: 100 },
  })
}

export async function streamAgentRunEvents(
  runId: number,
  options: {
    token?: string | null
    afterEventId?: number
    signal?: AbortSignal
    onEvent: (event: AgentRunEvent) => void
  },
) {
  const origin = getApiOrigin()
  const path = `/api/v1/agent/runs/${runId}/events/stream`
  const url = new URL(path, origin || window.location.origin)
  if (options.afterEventId) url.searchParams.set("afterEventId", String(options.afterEventId))
  const bearer = options.token ?? getSessionBearerJwt()
  const response = await fetch(url.toString(), {
    method: "GET",
    signal: options.signal,
    credentials: "include",
    headers: {
      Accept: "text/event-stream",
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
    },
  })
  if (!response.ok || !response.body) {
    throw new Error(`Agent event stream failed: ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    buffer = buffer.replace(/\r\n/g, "\n")
    const chunks = buffer.split("\n\n")
    buffer = chunks.pop() ?? ""
    for (const chunk of chunks) {
      const dataLines = chunk
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trim())
      if (dataLines.length === 0) continue
      const data = dataLines.join("\n")
      if (data === "ok") continue
      try {
        options.onEvent(JSON.parse(data) as AgentRunEvent)
      } catch {
        // Ignore keepalive or malformed event frames.
      }
    }
  }
}

export function confirmAgentTool(
  runId: number,
  body: { toolCode: string; approved: boolean; autoCallEnabled?: boolean },
  options?: { token?: string | null },
) {
  return apiRequest<AgentRun>("POST", `/api/v1/agent/runs/${runId}/tool-confirmations`, {
    token: options?.token,
    body,
  })
}

export function updateAgentToolPreference(
  toolCode: string,
  body: { autoCallEnabled: boolean },
  options?: { token?: string | null },
) {
  return apiRequest<AgentToolPreference>("PUT", `/api/v1/agent/tool-preferences/${toolCode}`, {
    token: options?.token,
    body,
  })
}

export function fetchAgentWorkspaceMemory(workspaceId: number, options?: { token?: string | null }) {
  return apiRequest<PageResult<AgentWorkspaceMemoryItem>>("GET", `/api/v1/agent/workspaces/${workspaceId}/memory`, {
    token: options?.token,
  })
}

export function createAgentWorkspaceMemory(
  workspaceId: number,
  body: CreateAgentWorkspaceMemoryRequest,
  options?: { token?: string | null },
) {
  return apiRequest<AgentWorkspaceMemoryItem>("POST", `/api/v1/agent/workspaces/${workspaceId}/memory`, {
    token: options?.token,
    body,
  })
}

export function updateAgentWorkspaceMemory(
  workspaceId: number,
  memoryId: number,
  body: UpdateAgentWorkspaceMemoryRequest,
  options?: { token?: string | null },
) {
  return apiRequest<AgentWorkspaceMemoryItem>("PUT", `/api/v1/agent/workspaces/${workspaceId}/memory/${memoryId}`, {
    token: options?.token,
    body,
  })
}

export function deleteAgentWorkspaceMemory(
  workspaceId: number,
  memoryId: number,
  options?: { token?: string | null },
) {
  return apiRequest<void>("DELETE", `/api/v1/agent/workspaces/${workspaceId}/memory/${memoryId}`, {
    token: options?.token,
  })
}
