import { apiRequest } from "./client"
import type { AITool, Capability } from "./aiToolTypes"
import type { PageResult, ToolCategory, ToolDetail, ToolSummary, UserUploadAsset } from "./types"
import { compressImage } from "@/utils/imageCompressor"
import {
  isMarketplaceMockToolId,
  isMockMode,
  mockFetchAIToolById,
  mockFetchEnabledAITools,
} from "./aiToolMock"

const FRONTEND_STYLE_PATTERN = /<!-- ai-tool-ui:(.*?) -->/s

function parseFrontendStyle(
  configNote?: string | null,
): Pick<AITool, "primaryColor" | "welcomeMessage" | "mediaDisplayMode" | "modelIconUrl" | "comparisonOriginalUrl" | "comparisonEffectUrl" | "audioPreviewUrl"> {
  const match = (configNote || "").match(FRONTEND_STYLE_PATTERN)
  if (!match) return {}

  try {
    const parsed = JSON.parse(match[1]) as {
      primaryColor?: unknown
      welcomeMessage?: unknown
      mediaDisplayMode?: unknown
      modelIconUrl?: unknown
      comparisonOriginalUrl?: unknown
      comparisonEffectUrl?: unknown
      audioPreviewUrl?: unknown
    }
    return {
      primaryColor: typeof parsed.primaryColor === "string" ? parsed.primaryColor : undefined,
      welcomeMessage: typeof parsed.welcomeMessage === "string" ? parsed.welcomeMessage : undefined,
      mediaDisplayMode: parsed.mediaDisplayMode === "comparison" ? "comparison" : parsed.mediaDisplayMode === "effect" ? "effect" : "icon",
      modelIconUrl: typeof parsed.modelIconUrl === "string" ? parsed.modelIconUrl : undefined,
      comparisonOriginalUrl: typeof parsed.comparisonOriginalUrl === "string" ? parsed.comparisonOriginalUrl : undefined,
      comparisonEffectUrl: typeof parsed.comparisonEffectUrl === "string" ? parsed.comparisonEffectUrl : undefined,
      audioPreviewUrl: typeof parsed.audioPreviewUrl === "string" ? parsed.audioPreviewUrl : undefined,
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

  if (output === "IMAGE" || output === "VIDEO" || type.includes("IMAGE") || type.includes("VIDEO")) {
    capabilities.push({
      type: "imageGeneration",
      config: { aspectRatios: ["16:9", "9:16"], defaultRatio: "16:9", maxImagesPerRequest: 1 },
    })
  }
  if (input === "FILE" || input === "MULTIMODAL") {
    capabilities.push({
      type: "fileReading",
      config: { supportedFileTypes: ["pdf", "txt", "png", "jpg", "jpeg", "webp"], maxSizeMB: 20 },
    })
  }
  if (type === "MUSIC_GENERATION") {
    capabilities.push({
      type: "fileReading",
      config: { supportedFileTypes: ["mp3", "wav", "m4a", "flac", "ogg", "aac"], maxSizeMB: 100 },
    })
  }

  return capabilities
}

export function mapToolToAITool(tool: ToolSummary | ToolDetail): AITool {
  const style = parseFrontendStyle(tool.configNote)
  const outputModality = (tool.outputModality || "").trim().toUpperCase()
  const mediaDisplayMode =
    style.mediaDisplayMode ?? (outputModality === "VIDEO" ? "effect" : "icon")
  return {
    id: tool.toolCode,
    name: tool.toolName,
    iconUrl: tool.coverUrl || "",
    description: tool.description || "",
    enabled: (tool.status || "").toUpperCase() === "ONLINE",
    order: tool.id,
    primaryColor: style.primaryColor,
    welcomeMessage: style.welcomeMessage,
    mediaDisplayMode,
    modelIconUrl: style.modelIconUrl,
    comparisonOriginalUrl: style.comparisonOriginalUrl,
    comparisonEffectUrl: style.comparisonEffectUrl,
    audioPreviewUrl: style.audioPreviewUrl,
    capabilities: capabilitiesFromTool(tool),
    inputModality: tool.inputModality,
    outputModality: tool.outputModality,
    toolType: tool.toolType,
    categoryCode: tool.categoryCode,
    categoryName: tool.categoryName,
    fields: "fields" in tool ? tool.fields : undefined,
    estimatedCreditCost: tool.estimatedCreditCost,
    modelConfigName: tool.modelConfigName,
    modelName: tool.modelName,
  }
}

/** GET /api/v1/tool-categories */
export async function fetchToolCategories(options?: { token?: string | null }): Promise<ToolCategory[]> {
  return apiRequest<ToolCategory[]>("GET", "/api/v1/tool-categories", { token: options?.token })
}

/** GET /api/v1/tools —— 返回分页结果 */
export async function fetchTools(options?: {
  token?: string | null
  query?: Record<string, string | number | boolean | undefined>
}): Promise<PageResult<ToolSummary>> {
  return apiRequest<PageResult<ToolSummary>>("GET", "/api/v1/tools", {
    token: options?.token,
    query: options?.query,
  })
}

/** GET /api/v1/tools/search —— 搜索工具，返回分页结果 */
export async function searchTools(options?: {
  token?: string | null
  query?: Record<string, string | number | boolean | undefined>
}): Promise<PageResult<ToolSummary>> {
  return apiRequest<PageResult<ToolSummary>>("GET", "/api/v1/tools/search", {
    token: options?.token,
    query: options?.query,
  })
}

/** GET /api/v1/tools/{toolCode} */
export async function fetchToolByCode(
  toolCode: string,
  options?: { token?: string | null },
): Promise<ToolDetail> {
  const encoded = encodeURIComponent(toolCode)
  return apiRequest<ToolDetail>("GET", `/api/v1/tools/${encoded}`, { token: options?.token })
}

export async function fetchEnabledAITools(options?: { token?: string | null }): Promise<AITool[]> {
  if (isMockMode()) return mockFetchEnabledAITools()
  const page = await fetchTools({ token: options?.token, query: { pageNo: 1, pageSize: 100 } })
  return page.list
    .filter((tool) => (tool.status || "").toUpperCase() === "ONLINE")
    .map(mapToolToAITool)
    .sort((a, b) => a.order - b.order)
}

export async function fetchAIToolById(
  toolId: string,
  options?: { token?: string | null },
): Promise<AITool> {
  if (isMockMode() || isMarketplaceMockToolId(toolId)) return mockFetchAIToolById(toolId)
  const tool = await fetchToolByCode(toolId, options)
  return mapToolToAITool(tool)
}

/** POST /api/v1/tool-upload — 工具表单文件上传（支持 mp3/wav 等音频） */
export async function uploadToolFile(
  file: File,
  options?: { token?: string | null },
): Promise<{ assetId?: number; fileId: string; url: string; name?: string; contentType?: string; size?: number }> {
  const uploadFile = await compressImage(file, 512, 0.8)
  const formData = new FormData()
  formData.append("file", uploadFile)
  return apiRequest<{ assetId?: number; fileId: string; url: string; name?: string; contentType?: string; size?: number }>("POST", "/api/v1/tool-upload", {
    token: options?.token,
    body: formData,
  })
}

export async function fetchUploadAssets(options?: {
  token?: string | null
  kind?: "image" | "video" | "audio" | "file" | string | null
  pageNo?: number
  pageSize?: number
}): Promise<PageResult<UserUploadAsset>> {
  return apiRequest<PageResult<UserUploadAsset>>("GET", "/api/v1/upload-assets", {
    token: options?.token,
    query: {
      kind: options?.kind ?? undefined,
      pageNo: options?.pageNo ?? undefined,
      pageSize: options?.pageSize ?? undefined,
    },
  })
}

export async function deleteUploadAsset(
  assetId: number,
  options?: { token?: string | null },
): Promise<void> {
  await apiRequest<void>("DELETE", `/api/v1/upload-assets/${assetId}`, { token: options?.token })
}
