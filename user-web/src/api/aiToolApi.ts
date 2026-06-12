import { apiRequest } from "./client"
import type {
  AITool,
  Capability,
  ChatMessage,
  ChatRequest,
  ChatResponse,
  ChatSession,
  FileUploadResult,
} from "./aiToolTypes"
import type { PageResult, ToolDetail, ToolFrontendStyle, ToolSummary } from "./types"
import {
  isMarketplaceMockToolId,
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

export { isMarketplaceMockToolId }

async function withMockFallback<T>(request: () => Promise<T>, mock: () => Promise<T>): Promise<T> {
  if (isMockMode()) return mock()
  return request()
}

async function withToolMockFallback<T>(
  toolId: string | undefined,
  request: () => Promise<T>,
  mock: () => Promise<T>,
): Promise<T> {
  if (isMockMode() || (toolId && isMarketplaceMockToolId(toolId))) return mock()
  return request()
}

function marketplaceToolIdFromSessionId(sessionId: string): string | undefined {
  const match = sessionId.match(/^sess-(.+?)-/)
  return match?.[1]
}

async function withSessionMockFallback<T>(
  sessionId: string,
  request: () => Promise<T>,
  mock: () => Promise<T>,
): Promise<T> {
  const toolId = marketplaceToolIdFromSessionId(sessionId)
  if (isMockMode() || (toolId && isMarketplaceMockToolId(toolId))) return mock()
  return request()
}

const FRONTEND_STYLE_PATTERN = /<!-- ai-tool-ui:(.*?) -->/s

function parseFrontendStyle(
  configNote?: string | null,
): ToolFrontendStyle {
  const match = (configNote || "").match(FRONTEND_STYLE_PATTERN)
  if (!match) return {}

  try {
    const parsed = JSON.parse(match[1]) as Record<string, unknown>
    const stringList = (value: unknown) => Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : undefined
    return {
      primaryColor: typeof parsed.primaryColor === "string" ? parsed.primaryColor : undefined,
      welcomeMessage: typeof parsed.welcomeMessage === "string" ? parsed.welcomeMessage : undefined,
      mediaDisplayMode: parsed.mediaDisplayMode === "comparison" ? "comparison" : parsed.mediaDisplayMode === "effect" ? "effect" : "icon",
      modelIconUrl: typeof parsed.modelIconUrl === "string" ? parsed.modelIconUrl : undefined,
      comparisonOriginalUrl: typeof parsed.comparisonOriginalUrl === "string" ? parsed.comparisonOriginalUrl : undefined,
      comparisonEffectUrl: typeof parsed.comparisonEffectUrl === "string" ? parsed.comparisonEffectUrl : undefined,
      heroTitle: typeof parsed.heroTitle === "string" ? parsed.heroTitle : undefined,
      heroSubtitle: typeof parsed.heroSubtitle === "string" ? parsed.heroSubtitle : undefined,
      demoThumbnails: stringList(parsed.demoThumbnails),
      useCases: stringList(parsed.useCases),
      steps: stringList(parsed.steps),
      recommendedToolCodes: stringList(parsed.recommendedToolCodes),
      beforeVideoUrl: typeof parsed.beforeVideoUrl === "string" ? parsed.beforeVideoUrl : undefined,
      afterVideoUrl: typeof parsed.afterVideoUrl === "string" ? parsed.afterVideoUrl : undefined,
    }
  } catch {
    return {}
  }
}

function capabilitiesFromTool(tool: ToolSummary): Capability[] {
  const capabilities: Capability[] = []
  const input = (tool.inputModality || "").toUpperCase()
  const output = (tool.outputModality || "").toUpperCase()
  const type = (tool.toolType || "").toUpperCase()

  if (output === "IMAGE" || type.includes("IMAGE")) {
    capabilities.push({
      type: "imageGeneration",
      config: { aspectRatios: ["1:1", "16:9", "9:16"], defaultRatio: "1:1", maxImagesPerRequest: 1 },
    })
  }
  if (input === "FILE" || input === "MULTIMODAL") {
    capabilities.push({
      type: "fileReading",
      config: { supportedFileTypes: ["pdf", "txt", "png", "jpg", "jpeg", "webp"], maxSizeMB: 20 },
    })
  }

  return capabilities
}

function mapToolSummaryToAITool(tool: ToolSummary | ToolDetail): AITool {
  const style = tool.frontendStyle || parseFrontendStyle(tool.configNote)
  const outputModality = (tool.outputModality || "").trim().toUpperCase()
  const rawMediaDisplayMode = style.mediaDisplayMode ?? (outputModality === "VIDEO" ? "effect" : "icon")
  const mediaDisplayMode =
    rawMediaDisplayMode === "comparison" ? "comparison" : rawMediaDisplayMode === "effect" ? "effect" : "icon"
  return {
    id: tool.toolCode,
    name: tool.toolName,
    iconUrl: tool.coverUrl || "",
    description: tool.description || "",
    enabled: (tool.status || "").toUpperCase() === "ONLINE",
    order: tool.id,
    primaryColor: style.primaryColor ?? undefined,
    welcomeMessage: style.welcomeMessage ?? undefined,
    mediaDisplayMode,
    modelIconUrl: style.modelIconUrl ?? undefined,
    comparisonOriginalUrl: style.comparisonOriginalUrl ?? undefined,
    comparisonEffectUrl: style.comparisonEffectUrl ?? undefined,
    frontendStyle: style,
    heroTitle: style.heroTitle,
    heroSubtitle: style.heroSubtitle,
    demoThumbnails: style.demoThumbnails,
    useCases: style.useCases,
    steps: style.steps,
    recommendedToolCodes: style.recommendedToolCodes,
    beforeVideoUrl: style.beforeVideoUrl,
    afterVideoUrl: style.afterVideoUrl,
    capabilities: capabilitiesFromTool(tool),
    inputModality: tool.inputModality,
    outputModality: tool.outputModality,
    toolKind: tool.toolKind,
    fields: "fields" in tool ? tool.fields : undefined,
    estimatedCreditCost: tool.estimatedCreditCost,
    variableCreditPricing: "variableCreditPricing" in tool ? tool.variableCreditPricing : undefined,
    modelConfigName: tool.modelConfigName,
    modelName: tool.modelName,
  }
}

/** GET /api/v1/ai-tools — 已上架列表，按 order 排序 */
export async function fetchEnabledAITools(options?: { token?: string | null }): Promise<AITool[]> {
  return withMockFallback(
    async () => {
      const page = await apiRequest<PageResult<ToolSummary>>("GET", "/api/v1/tools", {
        token: options?.token,
        query: { pageNo: 1, pageSize: 100 },
      })
      return page.list
        .filter((tool) => (tool.status || "").toUpperCase() === "ONLINE")
        .map(mapToolSummaryToAITool)
        .sort((a, b) => a.order - b.order)
    },
    mockFetchEnabledAITools,
  )
}

/** GET /api/v1/ai-tools/{toolId} */
export async function fetchAIToolById(
  toolId: string,
  options?: { token?: string | null },
): Promise<AITool> {
  return withToolMockFallback(
    toolId,
    async () => {
      const tool = await apiRequest<ToolDetail>("GET", `/api/v1/tools/${encodeURIComponent(toolId)}`, {
        token: options?.token,
      })
      return mapToolSummaryToAITool(tool)
    },
    () => mockFetchAIToolById(toolId),
  )
}

/** GET /api/v1/sessions?toolId= */
export async function fetchChatSessions(
  toolId: string,
  options?: { token?: string | null },
): Promise<ChatSession[]> {
  return withToolMockFallback(
    toolId,
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
  return withToolMockFallback(
    toolId,
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
  return withSessionMockFallback(
    sessionId,
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
  return withSessionMockFallback(
    sessionId,
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
  return withToolMockFallback(
    payload.toolId,
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
  options?: { token?: string | null; toolId?: string | null },
): Promise<FileUploadResult> {
  return withToolMockFallback(
    options?.toolId || undefined,
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
