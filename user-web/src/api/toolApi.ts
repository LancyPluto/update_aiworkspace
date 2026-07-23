import { apiRequest } from "./client"
import type { AITool, Capability } from "./aiToolTypes"
import type {
  PageResult,
  ToolCategory,
  ToolCompact,
  ToolDetail,
  ToolSummary,
  UserUploadAsset,
} from "./types"
import {
  createToolCatalogFetcher,
  normalizeToolCatalogQuery,
  type ToolCatalogFetchOptions,
  type ToolCatalogQuery,
} from "./toolCatalogCache"
import { compressImage } from "@/utils/imageCompressor"
import {
  isMarketplaceMockToolId,
  isMockMode,
  mockFetchAIToolById,
  mockFetchEnabledAITools,
} from "./aiToolMock"

function capabilitiesFromTool(tool: ToolCompact): Capability[] {
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

export function mapToolToAITool(tool: ToolSummary | ToolDetail, order = 0): AITool {
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
    heroSubtitle: style.heroSubtitle ?? cardMedia?.heroSubtitle,
    demoThumbnails: style.demoThumbnails ?? cardMedia?.demoThumbnails,
    capabilities: capabilitiesFromTool(tool),
    inputModality: tool.inputModality,
    outputModality: tool.outputModality,
    toolType: tool.toolType,
    categoryCode: tool.categoryCode,
    categoryName: tool.categoryName,
    fields: "fields" in tool ? tool.fields : undefined,
    estimatedCreditCost: tool.estimatedCreditCost ?? undefined,
    modelConfigName: tool.modelDisplayName,
  }
}

/** GET /api/v1/tool-categories */
export async function fetchToolCategories(options?: { token?: string | null }): Promise<ToolCategory[]> {
  return apiRequest<ToolCategory[]>("GET", "/api/v1/tool-categories", { token: options?.token })
}

type CompactToolCatalogFetchOptions = Omit<ToolCatalogFetchOptions, "query"> & {
  query: ToolCatalogQuery & { view: "compact" }
}

type SummaryToolCatalogFetchOptions = Omit<ToolCatalogFetchOptions, "query"> & {
  query?: ToolCatalogQuery & { view?: "summary" }
}

const fetchToolCatalogPage = createToolCatalogFetcher<PageResult<ToolCompact | ToolSummary>>(
  ({ token, query }) => apiRequest("GET", "/api/v1/tools", { token, query }),
)

/** GET /api/v1/tools —— 按列表契约返回分页结果，共享公开目录短时缓存。 */
export function fetchTools(options: CompactToolCatalogFetchOptions): Promise<PageResult<ToolCompact>>
export function fetchTools(options?: SummaryToolCatalogFetchOptions): Promise<PageResult<ToolSummary>>
export function fetchTools(
  options?: ToolCatalogFetchOptions,
): Promise<PageResult<ToolCompact | ToolSummary>> {
  return fetchToolCatalogPage(options)
}

/** GET /api/v1/tools/search —— 搜索结果不进入目录缓存。 */
export function searchTools(options: CompactToolCatalogFetchOptions): Promise<PageResult<ToolCompact>>
export function searchTools(options?: SummaryToolCatalogFetchOptions): Promise<PageResult<ToolSummary>>
export function searchTools(
  options?: ToolCatalogFetchOptions,
): Promise<PageResult<ToolCompact | ToolSummary>> {
  return apiRequest<PageResult<ToolCompact | ToolSummary>>("GET", "/api/v1/tools/search", {
    token: options?.token,
    query: normalizeToolCatalogQuery(options?.query),
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
  const page = await fetchTools({
    token: options?.token,
    query: { view: "summary", pageNo: 1, pageSize: 100 },
  })
  return [...page.list].reverse().map((tool, index) => mapToolToAITool(tool, index))
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
  options?: { token?: string | null; retainHistory?: boolean },
): Promise<{ assetId?: number; fileId: string; url: string; name?: string; contentType?: string; size?: number }> {
  const uploadFile = await compressImage(file, 512, 0.8)
  const formData = new FormData()
  formData.append("file", uploadFile)
  return apiRequest<{ assetId?: number; fileId: string; url: string; name?: string; contentType?: string; size?: number }>("POST", "/api/v1/tool-upload", {
    token: options?.token,
    body: formData,
    query: options?.retainHistory === undefined ? undefined : { retainHistory: options.retainHistory },
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
