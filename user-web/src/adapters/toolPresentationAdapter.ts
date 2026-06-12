import type { Component } from "vue"
import type { ToolSummary } from "@/api/types"
import { cleanToolDisplayText } from "@/utils/toolDisplayText"
import { formatToolCreditLabel } from "@/utils/toolCreditLabel"

export type ToolModeFilter = "all" | "video" | "image" | "workflow" | "digitalHuman" | "audio"

export const DEFAULT_TOOL_COVER_URL = ""
export const INTERNAL_DEFAULT_CREATION_TOOL_CODES = new Set([
  "gpt_image_text_to_image",
  "agnes_text_to_video",
])
const WORKFLOW_TOOL_CODES = new Set([
  "digital_human_agent",
  "ai_comic_drama_agent",
  "enterprise_diagnosis_agent",
  "social_media_comment_insights_agent",
  "tts_mm",
])
const PUBLIC_TOOL_DESCRIPTION_FALLBACK = "点击进入工具并开始创作"

export interface ToolCardModel {
  id: string
  title: string
  description: string
  image: string
  mediaType: "image" | "video"
  tag: string
  to: string
  useTo: string
  costLabel: string
  outputModality?: string | null
  inputModality?: string | null
  icon?: Component
}

function normalize(value?: string | null): string {
  return (value || "").trim().toLowerCase()
}

function includesAny(value: string, keywords: string[]): boolean {
  return keywords.some((keyword) => value.includes(keyword))
}

function isAgentTool(tool: Partial<ToolSummary>): boolean {
  const text = searchableText(tool)
  return includesAny(text, ["agent", "智能体", "工作流", "workflow"])
}

export function isWorkflowTool(tool: Partial<ToolSummary>): boolean {
  const code = normalize(tool.toolCode)
  if (WORKFLOW_TOOL_CODES.has(code)) return true

  const type = normalize(tool.toolType).toUpperCase()
  const handler = normalize(tool.executionHandler).toUpperCase()
  const input = normalize(tool.inputModality).toUpperCase()
  const output = normalize(tool.outputModality).toUpperCase()

  if (type === "AGENT" || handler === "DIGITAL_HUMAN") return true
  if (type === "TEXT_TO_SPEECH" || type === "SPEECH_TO_TEXT" || type === "MUSIC_GENERATION") return true
  if (handler === "TEXT_TO_SPEECH" || handler === "SPEECH_TO_TEXT" || handler === "MUSIC_GENERATION") return true
  if (input === "AUDIO" || output === "AUDIO") return true

  return isAgentTool(tool) || includesAny(searchableText(tool), [
    "ppt",
    "slide",
    "audio",
    "speech",
    "tts",
    "music",
    "音频",
    "语音",
    "音乐",
  ])
}

function isDigitalHumanTool(tool: Partial<ToolSummary>): boolean {
  const text = searchableText(tool)
  return includesAny(text, ["digital human", "digital_human", "avatar", "presenter", "数字人", "口播", "主播"])
}

function isAudioTool(tool: Partial<ToolSummary>): boolean {
  const text = searchableText(tool)
  return includesAny(text, ["audio", "voice", "speech", "tts", "music", "音频", "语音", "声音", "配音", "音乐"])
}

function toolEntryPath(toolCode: string): string {
  const encoded = encodeURIComponent(toolCode)
  return `/create?tool=${encoded}`
}

function searchableText(tool: Partial<ToolSummary>): string {
  return [
    tool.inputModality,
    tool.outputModality,
    tool.categoryName,
    tool.toolType,
    tool.toolName,
    tool.description,
    tool.modelName,
    tool.modelConfigName,
  ]
    .map((value) => normalize(value))
    .filter(Boolean)
    .join(" ")
}

export function isOnlineTool(tool: Pick<ToolSummary, "status">): boolean {
  return normalize(tool.status) === "online"
}

export function isInternalDefaultCreationTool(tool: Pick<ToolSummary, "toolCode"> | string): boolean {
  const toolCode = typeof tool === "string" ? tool : tool.toolCode
  return INTERNAL_DEFAULT_CREATION_TOOL_CODES.has((toolCode || "").trim())
}

export function isVideoTool(tool: Partial<ToolSummary>): boolean {
  const text = searchableText(tool)
  return includesAny(text, ["video", "movie", "clip", "视频", "影片", "短片", "动效", "动画"])
}

export function isImageTool(tool: Partial<ToolSummary>): boolean {
  const text = searchableText(tool)
  return includesAny(text, ["image", "img", "picture", "photo", "图片", "图像", "照片", "海报", "封面"])
}

export function normalizePublicMediaUrl(value?: string | null, fallback = DEFAULT_TOOL_COVER_URL): string {
  const raw = value?.trim()
  if (!raw) return fallback
  if (/^(https?:)?\/\//i.test(raw) || raw.startsWith("data:")) return raw
  return raw.startsWith("/") ? raw : `/${raw}`
}

const VIDEO_PREVIEW_EXTENSION_PATTERN = /\.(mp4|webm|mov|m4v)(?:[?#].*)?$/i

export function isVideoPreviewUrl(value?: string | null): boolean {
  return VIDEO_PREVIEW_EXTENSION_PATTERN.test(value?.trim() || "")
}

function fallbackCoverUrl(tool: Partial<ToolSummary>): string {
  if (isAgentTool(tool)) return DEFAULT_TOOL_COVER_URL
  if (isImageTool(tool)) return DEFAULT_TOOL_COVER_URL
  return DEFAULT_TOOL_COVER_URL
}

function preferredToolCover(tool: ToolSummary): string | null | undefined {
  return (
    tool.frontendStyle?.comparisonEffectUrl ||
    tool.frontendStyle?.demoThumbnails?.[0] ||
    tool.coverUrl
  )
}

export function toWorkspaceToolCard(tool: ToolSummary): ToolCardModel {
  const title = cleanToolDisplayText(tool.toolName) || tool.toolCode
  const description =
    cleanToolDisplayText(tool.description) ||
    PUBLIC_TOOL_DESCRIPTION_FALLBACK
  const tag = cleanToolDisplayText(tool.categoryName) || (isWorkflowTool(tool) ? "工作流" : tool.outputModality) || "AI"
  const image = normalizePublicMediaUrl(preferredToolCover(tool), fallbackCoverUrl(tool))

  return {
    id: tool.toolCode,
    title,
    description,
    image,
    mediaType: isVideoPreviewUrl(image) ? "video" : "image",
    tag,
    to: `/tools/${encodeURIComponent(tool.toolCode)}`,
    useTo: toolEntryPath(tool.toolCode),
    costLabel: formatToolCreditLabel(tool),
    outputModality: tool.outputModality,
    inputModality: tool.inputModality,
  }
}

export function toWorkspaceToolCards(tools: ToolSummary[], mode: ToolModeFilter = "all"): ToolCardModel[] {
  return tools
    .filter(isOnlineTool)
    .filter((tool) => !isInternalDefaultCreationTool(tool))
    .filter((tool) => {
      if (mode === "video") return isVideoTool(tool)
      if (mode === "image") return isImageTool(tool)
      if (mode === "workflow") return isWorkflowTool(tool)
      if (mode === "digitalHuman") return isDigitalHumanTool(tool)
      if (mode === "audio") return isAudioTool(tool)
      return true
    })
    .map(toWorkspaceToolCard)
}
