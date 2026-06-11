import { getApiOrigin } from "@/api/client"
import type { AITool } from "@/api/aiToolTypes"

export function normalizeOutputModality(value?: string | null): string {
  return (value || "TEXT").trim().toUpperCase()
}

export function normalizeMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

const VIDEO_PREVIEW_EXTENSION_PATTERN = /\.(mp4|webm|mov|m4v)(?:[?#].*)?$/i

export function isVideoPreviewUrl(value?: string | null): boolean {
  return VIDEO_PREVIEW_EXTENSION_PATTERN.test(value?.trim() || "")
}

export function isKlingTool(tool: Pick<AITool, "id" | "name">): boolean {
  const text = `${tool.id} ${tool.name}`.toLowerCase()
  return text.includes("kling") || text.includes("可灵")
}

/** Vite publicDir=asset，视频封面默认走站点根路径 */
export function defaultVideoCoverPath(tool: Pick<AITool, "id" | "name">): string {
  return isKlingTool(tool) ? "/3D动画生成.mp4" : "/猴子视频.mp4"
}

export function resolveToolCoverUrl(tool: AITool): string {
  const configured = tool.iconUrl?.trim()
  if (configured) return normalizeMediaUrl(configured)
  if (normalizeOutputModality(tool.outputModality) === "VIDEO") {
    return normalizeMediaUrl(defaultVideoCoverPath(tool))
  }
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
  return tool.mediaDisplayMode === "effect" && Boolean(tool.iconUrl?.trim())
}
