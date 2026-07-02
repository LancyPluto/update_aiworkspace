import { getApiOrigin } from "@/api/client"
import type { AITool } from "@/api/aiToolTypes"

export function normalizeOutputModality(value?: string | null): string {
  return (value || "TEXT").trim().toUpperCase()
}

function isRewritableMediaOrigin(url: URL): boolean {
  const host = url.hostname.toLowerCase()
  if (host === "backend") return true
  if ((host === "localhost" || host === "127.0.0.1") && (url.port === "8080" || url.port === "")) return true
  const path = url.pathname
  if (!path.startsWith("/generated") && !path.startsWith("/uploads")) return false
  if (typeof window === "undefined") return host === "backend"
  return host !== window.location.hostname
}

function toBrowserMediaPath(pathname: string, search = "", hash = ""): string {
  const path = `${pathname}${search}${hash}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin.replace(/\/$/, "")}${path}` : path
}

export function normalizeMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("data:")) return raw
  if (/^https?:\/\//i.test(raw)) {
    try {
      const parsed = new URL(raw)
      if (isRewritableMediaOrigin(parsed)) {
        return toBrowserMediaPath(parsed.pathname, parsed.search, parsed.hash)
      }
      return raw
    } catch {
      return raw
    }
  }
  const path = raw.startsWith("/") ? raw : `/${raw}`
  return toBrowserMediaPath(path)
}

export function isValidImagePreviewUrl(value?: string | null): boolean {
  const raw = value?.trim()
  if (!raw) return false
  if (raw.startsWith("data:image/")) return true
  if (/^https?:\/\//i.test(raw)) return true
  if (raw.startsWith("/")) {
    return (
      /\.(png|jpe?g|webp|gif|avif|bmp|svg)(\?|#|$)/i.test(raw)
      || raw.includes("/uploads/")
      || raw.includes("/generated/")
    )
  }
  return false
}

export function normalizeMediaFieldValue(value: unknown): unknown {
  if (typeof value === "string") return normalizeMediaUrl(value)
  if (Array.isArray(value)) {
    return value.map((item) => (typeof item === "string" ? normalizeMediaUrl(item) : item))
  }
  return value
}

const VIDEO_PREVIEW_EXTENSION_PATTERN = /\.(mp4|webm|mov|m4v)(?:[?#].*)?$/i

export function isVideoPreviewUrl(value?: string | null): boolean {
  return VIDEO_PREVIEW_EXTENSION_PATTERN.test(value?.trim() || "")
}

export function isKlingTool(tool: Pick<AITool, "id" | "name">): boolean {
  const text = `${tool.id} ${tool.name}`.toLowerCase()
  return text.includes("kling") || text.includes("可灵")
}

export function isHappyHorseTool(tool: Pick<AITool, "id" | "name">): boolean {
  const text = `${tool.id} ${tool.name}`.toLowerCase()
  return text.includes("happyhorse") || text.includes("happy horse")
}

export function defaultVideoCoverPath(tool: Pick<AITool, "id" | "name">): string {
  if (isKlingTool(tool)) return "https://cdn.wlcloudai.com/static/3D动画生成.mp4"
  if (isHappyHorseTool(tool)) return "https://cdn.wlcloudai.com/static/猴子视频.mp4"
  return "https://cdn.wlcloudai.com/static/猴子视频.mp4"
}

export interface ToolCoverSource {
  toolCode: string
  toolName: string
  coverUrl?: string | null
  outputModality?: string | null
  frontendStyle?: {
    comparisonEffectUrl?: string | null
    demoThumbnails?: string[] | null
  } | null
}

function collectCoverCandidates(tool: ToolCoverSource): string[] {
  return [
    tool.frontendStyle?.comparisonEffectUrl,
    tool.frontendStyle?.demoThumbnails?.[0],
    tool.coverUrl,
  ]
    .map((value) => value?.trim())
    .filter((value): value is string => Boolean(value))
}

/** 视频类工具优先使用 mp4 封面，避免 effect 配置里的静态图裂图 */
export function resolveSummaryToolCoverUrl(tool: ToolCoverSource): string {
  const candidates = collectCoverCandidates(tool)
  if (normalizeOutputModality(tool.outputModality) === "VIDEO") {
    const videoUrl = candidates.find((url) => isVideoPreviewUrl(url))
    if (videoUrl) return normalizeMediaUrl(videoUrl)
    return normalizeMediaUrl(defaultVideoCoverPath({ id: tool.toolCode, name: tool.toolName }))
  }
  const first = candidates[0]
  return first ? normalizeMediaUrl(first) : ""
}

export function resolveToolCoverUrl(tool: AITool): string {
  const candidates = [tool.comparisonEffectUrl, tool.iconUrl]
    .map((value) => value?.trim())
    .filter((value): value is string => Boolean(value))

  if (normalizeOutputModality(tool.outputModality) === "VIDEO") {
    const videoUrl = candidates.find((url) => isVideoPreviewUrl(url))
    if (videoUrl) return normalizeMediaUrl(videoUrl)
    return normalizeMediaUrl(defaultVideoCoverPath(tool))
  }

  const configured = candidates[0]
  if (configured) return normalizeMediaUrl(configured)
  return ""
}

export function resolveToolCoverFallback(tool: AITool): string {
  if (normalizeOutputModality(tool.outputModality) === "VIDEO") {
    return normalizeMediaUrl(defaultVideoCoverPath(tool))
  }
  return normalizeMediaUrl(tool.iconUrl)
}

/** 视频类工具用大封面；有 effect 封面资源的图片工具同理 */
export function shouldUseEffectCard(tool: AITool): boolean {
  const output = normalizeOutputModality(tool.outputModality)
  if (output === "VIDEO") return true
  if (output === "AUDIO" && Boolean(tool.audioPreviewUrl?.trim())) return true
  return tool.mediaDisplayMode === "effect" && Boolean(tool.iconUrl?.trim())
}
