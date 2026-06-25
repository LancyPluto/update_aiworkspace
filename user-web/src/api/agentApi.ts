import { apiRequest, getRequestBaseUrl } from "./client"
import type {
  AgentMessage,
  AgentRun,
  AgentRunEvent,
  AgentSession,
  AgentFile,
  AgentUrlAttachment,
  AgentModelConfig,
  AgentToolPickerItem,
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

export function fetchAgentModelConfigs(options?: { token?: string | null }) {
  return apiRequest<AgentModelConfig[]>("GET", "/api/v1/agent/model-configs", {
    token: options?.token,
  })
}

export function fetchAgentTools(options?: { token?: string | null }) {
  return apiRequest<AgentToolPickerItem[]>("GET", "/api/v1/agent/tools", {
    token: options?.token,
  })
}

export function createAgentSession(body: { title?: string }, options?: { token?: string | null }) {
  return apiRequest<AgentSession>("POST", "/api/v1/agent/sessions", {
    token: options?.token,
    body,
  })
}

export function deleteAgentSession(sessionId: number, options?: { token?: string | null }) {
  return apiRequest<void>("DELETE", `/api/v1/agent/sessions/${sessionId}`, {
    token: options?.token,
  })
}

export function fetchAgentMessages(sessionId: number, options?: { token?: string | null; signal?: AbortSignal }) {
  return apiRequest<PageResult<AgentMessage>>("GET", `/api/v1/agent/sessions/${sessionId}/messages`, {
    token: options?.token,
    signal: options?.signal,
    query: { pageNo: 1, pageSize: 100 },
  })
}

export function sendAgentMessage(
  sessionId: number,
  body: {
    content: string
    clientRequestId?: string
    modelConfigId?: number | null
    preferredToolCode?: string | null
    intelligenceLevel?: "standard" | "high"
    parentMessageId?: number | null
    fileIds?: number[]
    urlAttachments?: AgentUrlAttachment[]
    globalFileIds?: Array<string | number>
    contentParts?: Array<
      | { type: "text"; text: string }
      | {
          type: "image" | "file"
          file_id?: string | number
          url?: string
          asset_key: string
          name?: string
          content_type?: string
        }
    >
    positionalPrompt?: string
    referenceMentions?: Array<{
      token?: string
      refLabel?: string
      assetKey?: string
      fileId?: string | number
      url: string
      kind?: string
      name?: string
      contentType?: string | null
      previewUrl?: string
      source?: string
    }>
  },
  options?: { token?: string | null },
) {
  return apiRequest<CreateAgentMessageResponse>("POST", `/api/v1/agent/sessions/${sessionId}/messages`, {
    token: options?.token,
    body,
  })
}

export function activateAgentBranch(
  sessionId: number,
  body: { anchorMessageId: number; variantMessageId: number },
  options?: { token?: string | null },
) {
  return apiRequest<PageResult<AgentMessage>>("POST", `/api/v1/agent/sessions/${sessionId}/branches/active`, {
    token: options?.token,
    body,
  })
}

export function regenerateAgentRun(
  runId: number,
  body?: { clientRequestId?: string; modelConfigId?: number | null },
  options?: { token?: string | null },
) {
  return apiRequest<CreateAgentMessageResponse>("POST", `/api/v1/agent/runs/${runId}/regenerate`, {
    token: options?.token,
    body: body ?? {},
  })
}

export function editRegenerateAgentMessage(
  sessionId: number,
  messageId: number,
  body: { content: string; clientRequestId?: string; modelConfigId?: number | null },
  options?: { token?: string | null },
) {
  return apiRequest<CreateAgentMessageResponse>(
    "POST",
    `/api/v1/agent/sessions/${sessionId}/messages/${messageId}/edit-regenerate`,
    {
      token: options?.token,
      body,
    },
  )
}

export function fetchAgentFiles(sessionId: number, options?: { token?: string | null }) {
  return apiRequest<PageResult<AgentFile>>("GET", `/api/v1/agent/sessions/${sessionId}/files`, {
    token: options?.token,
  })
}

export function fetchRecentAgentFiles(sessionId: number, options?: { token?: string | null }) {
  return apiRequest<PageResult<AgentFile>>("GET", `/api/v1/agent/sessions/${sessionId}/files/recent`, {
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

export function deleteAgentFile(sessionId: number, fileId: number, options?: { token?: string | null }) {
  return apiRequest<void>("DELETE", `/api/v1/agent/sessions/${sessionId}/files/${fileId}`, {
    token: options?.token,
  })
}

export function fetchAgentRun(runId: number, options?: { token?: string | null; signal?: AbortSignal }) {
  return apiRequest<AgentRun>("GET", `/api/v1/agent/runs/${runId}`, {
    token: options?.token,
    signal: options?.signal,
  })
}

export function fetchAgentRunEvents(runId: number, options?: { token?: string | null; afterEventId?: number; signal?: AbortSignal }) {
  return apiRequest<PageResult<AgentRunEvent>>("GET", `/api/v1/agent/runs/${runId}/events`, {
    token: options?.token,
    signal: options?.signal,
    query: { afterEventId: options?.afterEventId, pageSize: 100 },
  })
}

export function cancelAgentRun(runId: number, options?: { token?: string | null }) {
  return apiRequest<AgentRun>("POST", `/api/v1/agent/runs/${runId}/cancel`, {
    token: options?.token,
  })
}

export async function streamAgentRunEvents(
  runId: number,
  options: {
    token?: string | null
    afterEventId?: number
    signal?: AbortSignal
    onEvent: (event: AgentRunEvent) => void | Promise<void>
  },
) {
  const path = `/api/v1/agent/runs/${runId}/events/stream`
  const base = getRequestBaseUrl()
  const url = new URL(path, base.endsWith("/") ? base : `${base}/`)
  if (options.afterEventId) url.searchParams.set("afterEventId", String(options.afterEventId))
  const response = await fetch(url.toString(), {
    method: "GET",
    signal: options.signal,
    headers: {
      Accept: "text/event-stream",
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
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
        const event = JSON.parse(data) as AgentRunEvent
        await options.onEvent(event)
        await yieldForRenderableAgentEvent(event)
      } catch {
        // Ignore keepalive or malformed event frames.
      }
    }
  }
}

function yieldForRenderableAgentEvent(event: AgentRunEvent): Promise<void> | undefined {
  if (event.eventType !== "message.delta" && event.eventType !== "reasoning.delta") return undefined
  if (typeof window === "undefined") return undefined
  return new Promise((resolve) => {
    window.requestAnimationFrame(() => resolve())
  })
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
  body: { autoCallEnabled?: boolean; disabled?: boolean },
  options?: { token?: string | null },
) {
  return apiRequest<AgentToolPreference>("PUT", `/api/v1/agent/tool-preferences/${toolCode}`, {
    token: options?.token,
    body,
  })
}

export function fetchAgentWorkspaceMemory(
  workspaceId: number,
  options?: { token?: string | null; status?: "ACTIVE" | "CANDIDATE" },
) {
  const query = options?.status ? `?status=${encodeURIComponent(options.status)}` : ""
  return apiRequest<PageResult<AgentWorkspaceMemoryItem>>(
    "GET",
    `/api/v1/agent/workspaces/${workspaceId}/memory${query}`,
    { token: options?.token },
  )
}

export function pinAgentWorkspaceMemory(
  workspaceId: number,
  memoryId: number,
  pinned: boolean,
  options?: { token?: string | null },
) {
  return apiRequest<AgentWorkspaceMemoryItem>("PUT", `/api/v1/agent/workspaces/${workspaceId}/memory/${memoryId}/pin`, {
    token: options?.token,
    body: { pinned },
  })
}

export function approveAgentWorkspaceMemoryCandidate(
  workspaceId: number,
  memoryId: number,
  options?: { token?: string | null },
) {
  return apiRequest<AgentWorkspaceMemoryItem>(
    "POST",
    `/api/v1/agent/workspaces/${workspaceId}/memory/${memoryId}/approve`,
    { token: options?.token },
  )
}

export function rejectAgentWorkspaceMemoryCandidate(
  workspaceId: number,
  memoryId: number,
  options?: { token?: string | null },
) {
  return apiRequest<AgentWorkspaceMemoryItem>(
    "POST",
    `/api/v1/agent/workspaces/${workspaceId}/memory/${memoryId}/reject`,
    { token: options?.token },
  )
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
