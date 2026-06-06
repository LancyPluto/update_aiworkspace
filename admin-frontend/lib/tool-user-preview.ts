import { getBaseUrl } from "@/lib/api/http"

export interface ToolPreviewInput {
  id: string
  name: string
  description?: string | null
  coverUrl?: string | null
  outputModality?: string | null
  mediaDisplayMode?: "icon" | "effect" | "comparison"
  modelIconUrl?: string | null
  comparisonOriginalUrl?: string | null
  comparisonEffectUrl?: string | null
  modelConfigName?: string | null
  modelName?: string | null
  primaryColor?: string | null
  toolCode?: string | null
}

export interface ModelBrand {
  name: string
  iconUrl: string
  color: string
}

export type ToolPreviewVariant = "comparison" | "effect" | "icon"

const BRAND_RULES: Array<{ patterns: string[]; brand: ModelBrand }> = [
  { patterns: ["deepseek"], brand: { name: "DeepSeek", iconUrl: "https://www.deepseek.com/favicon.ico", color: "#4d6bfe" } },
  { patterns: ["doubao", "seedance", "seedream", "volcengine"], brand: { name: "Doubao", iconUrl: "https://www.doubao.com/favicon.ico", color: "#4f46e5" } },
  { patterns: ["qwen", "tongyi", "aliyun"], brand: { name: "Qwen", iconUrl: "https://tongyi.aliyun.com/favicon.ico", color: "#615ced" } },
  { patterns: ["glm", "zhipu", "chatglm"], brand: { name: "GLM", iconUrl: "https://chatglm.cn/favicon.ico", color: "#2563eb" } },
  { patterns: ["kimi", "moonshot"], brand: { name: "Kimi", iconUrl: "https://kimi.moonshot.cn/favicon.ico", color: "#111827" } },
  { patterns: ["kling"], brand: { name: "Kling", iconUrl: "https://app.klingai.com/favicon.ico", color: "#111827" } },
  { patterns: ["siliconflow"], brand: { name: "SiliconFlow", iconUrl: "https://siliconflow.cn/favicon.ico", color: "#111827" } },
  { patterns: ["minimax"], brand: { name: "MiniMax", iconUrl: "https://www.minimaxi.com/favicon.ico", color: "#0f172a" } },
  { patterns: ["openai", "gpt"], brand: { name: "OpenAI", iconUrl: "https://cdn.simpleicons.org/openai/111827", color: "#111827" } },
  { patterns: ["claude", "anthropic"], brand: { name: "Anthropic", iconUrl: "https://cdn.simpleicons.org/anthropic/111827", color: "#111827" } },
]

export function normalizeOutputModality(value?: string | null): string {
  return (value || "TEXT").trim().toUpperCase()
}

export function isVideoPreviewUrl(value?: string | null): boolean {
  const raw = value?.split(/[?#]/)[0]?.toLowerCase() || ""
  return [".mp4", ".webm", ".mov", ".m4v"].some((ext) => raw.endsWith(ext))
}

/** 与用户端 mapToolToAITool 一致：相对路径走同源 /generated 代理或 API 根地址 */
export function normalizeMediaUrl(value: string | undefined | null, baseUrl?: string): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const origin = (baseUrl ?? getBaseUrl()).replace(/\/$/, "")
  return origin ? `${origin}${path}` : path
}

function searchText(tool: ToolPreviewInput): string {
  return [tool.modelConfigName, tool.modelName, tool.name, tool.toolCode, tool.id].filter(Boolean).join(" ").toLowerCase()
}

export function resolveModelBrand(tool: ToolPreviewInput, baseUrl?: string): ModelBrand {
  if (tool.modelIconUrl?.trim()) {
    return {
      name: tool.modelConfigName || tool.modelName || tool.name,
      iconUrl: normalizeMediaUrl(tool.modelIconUrl, baseUrl),
      color: tool.primaryColor || "#2563eb",
    }
  }

  const text = searchText(tool)
  const matched = BRAND_RULES.find((rule) => rule.patterns.some((pattern) => text.includes(pattern.toLowerCase())))
  if (matched) return matched.brand

  if (tool.coverUrl?.trim()) {
    return {
      name: tool.modelConfigName || tool.modelName || tool.name,
      iconUrl: normalizeMediaUrl(tool.coverUrl, baseUrl),
      color: tool.primaryColor || "#2563eb",
    }
  }

  return {
    name: tool.modelConfigName || tool.modelName || tool.name,
    iconUrl: "",
    color: tool.primaryColor || "#2563eb",
  }
}

/** 与用户端 toolApi.mapToolToAITool 的 mediaDisplayMode 解析保持一致 */
export function resolveMediaDisplayMode(tool: ToolPreviewInput): "icon" | "effect" | "comparison" {
  if (tool.mediaDisplayMode === "comparison") return "comparison"
  if (tool.mediaDisplayMode === "effect") return "effect"
  const output = normalizeOutputModality(tool.outputModality)
  if (output === "VIDEO") return "effect"
  return "icon"
}

export function resolvePreviewCoverUrl(tool: ToolPreviewInput, baseUrl?: string): string {
  return normalizeMediaUrl(tool.coverUrl, baseUrl)
}

export function usesComparisonMedia(tool: ToolPreviewInput): boolean {
  return (
    resolveMediaDisplayMode(tool) === "comparison" &&
    Boolean(tool.comparisonOriginalUrl?.trim()) &&
    Boolean(tool.comparisonEffectUrl?.trim())
  )
}

/** 与用户端 shouldUseEffectCard 一致 */
export function shouldUseEffectCard(tool: ToolPreviewInput): boolean {
  const output = normalizeOutputModality(tool.outputModality)
  if (output === "VIDEO") return true
  return resolveMediaDisplayMode(tool) === "effect" && Boolean(tool.coverUrl?.trim())
}

export function resolvePreviewVariant(tool: ToolPreviewInput): ToolPreviewVariant {
  if (usesComparisonMedia(tool)) return "comparison"
  if (shouldUseEffectCard(tool)) return "effect"
  return "icon"
}

export function mediaDisplayModeLabel(mode: ReturnType<typeof resolveMediaDisplayMode>): string {
  if (mode === "comparison") return "效果对比"
  if (mode === "effect") return "模型效果"
  return "模型图标"
}

export function modalityLabel(value?: string | null): string {
  const labels: Record<string, string> = {
    TEXT: "文本",
    IMAGE: "图片",
    AUDIO: "音频",
    VIDEO: "视频",
    JSON: "结构化",
    FILE: "文件",
    MULTIMODAL: "多模态",
  }
  const key = normalizeOutputModality(value)
  return labels[key] || key
}
