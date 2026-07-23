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
import type { ToolCompact, ToolDetail, ToolSummary } from "./types"
import { fetchTools } from "./toolApi"
import { compressImage } from "@/utils/imageCompressor"
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

function capabilitiesFromTool(tool: ToolCompact): Capability[] {
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

function mapToolSummaryToAITool(tool: ToolSummary | ToolDetail, order = 0): AITool {
  const style = "frontendStyle" in tool ? tool.frontendStyle || {} : {}
  const cardMedia = "cardMedia" in tool ? tool.cardMedia : null
  const outputModality = (tool.outputModality || "").trim().toUpperCase()
  const rawMediaDisplayMode =
    style.mediaDisplayMode ?? cardMedia?.mediaDisplayMode ?? (outputModality === "VIDEO" ? "effect" : "icon")
  const mediaDisplayMode =
    rawMediaDisplayMode === "comparison" ? "comparison" : rawMediaDisplayMode === "effect" ? "effect" : "icon"
  return {
    id: tool.toolCode,
    name: tool.toolName,
    iconUrl: tool.coverUrl || "",
    description: tool.description || "",
    enabled: true,
    order,
    primaryColor: style.primaryColor ?? undefined,
    welcomeMessage: style.welcomeMessage ?? undefined,
    mediaDisplayMode,
    modelIconUrl: style.modelIconUrl ?? cardMedia?.modelIconUrl ?? undefined,
    comparisonOriginalUrl: style.comparisonOriginalUrl ?? cardMedia?.comparisonOriginalUrl ?? undefined,
    comparisonEffectUrl: style.comparisonEffectUrl ?? cardMedia?.comparisonEffectUrl ?? undefined,
    audioPreviewUrl: style.audioPreviewUrl ?? undefined,
    cardMedia,
    frontendStyle: "frontendStyle" in tool ? tool.frontendStyle : undefined,
    heroTitle: style.heroTitle,
    heroSubtitle: style.heroSubtitle ?? cardMedia?.heroSubtitle,
    demoThumbnails: style.demoThumbnails ?? cardMedia?.demoThumbnails,
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
    estimatedCreditCost: tool.estimatedCreditCost ?? undefined,
    variableCreditPricing: "variableCreditPricing" in tool ? tool.variableCreditPricing : undefined,
    modelConfigName: tool.modelDisplayName,
  }
}

/** GET /api/v1/ai-tools — 已上架列表，按 order 排序 */
export async function fetchEnabledAITools(options?: { token?: string | null }): Promise<AITool[]> {
  return withMockFallback(
    async () => {
      const page = await fetchTools({
        token: options?.token,
        query: { view: "summary", pageNo: 1, pageSize: 100 },
      })
      return [...page.list].reverse().map((tool, index) => mapToolSummaryToAITool(tool, index))
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
  const uploadFile = await compressImage(file, 512, 0.8)
  return withToolMockFallback(
    options?.toolId || undefined,
    () => {
      const formData = new FormData()
      formData.append("file", uploadFile)
      return apiRequest<FileUploadResult>("POST", "/api/v1/upload", {
        token: options?.token,
        body: formData,
      })
    },
    () => mockUploadChatFile(uploadFile),
  )
}
