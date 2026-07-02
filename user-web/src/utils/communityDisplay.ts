import type { AssetPreviewItem } from "@/types/assetPreview"

const GENERIC_TITLE_RE = /^(生成完成|已完成|未命名|untitled|生成图片|生成视频|生成音频|生成文本|生成作品)$/i
const TASK_NO_RE = /^(TASK|T\d{8,}|[A-Z0-9]{2,}[-_][A-Z0-9_-]{4,})$/i
const MOJIBAKE_TITLE_RE = /^(?:\?{2,}|\uFFFD{1,}|[\s?]+(?:\d{8,})?)$/i

function normalize(value?: string | null) {
  return value?.trim() || ""
}

function isBrokenText(value: string) {
  const compact = value.replace(/\s+/g, "")
  if (!compact) return true
  if (MOJIBAKE_TITLE_RE.test(value)) return true
  const questionCount = (compact.match(/\?/g) || []).length
  return questionCount >= 3 && questionCount >= compact.length / 2
}

function isToolLikeLabel(value: string, toolName?: string | null, toolCode?: string | null) {
  if (!value) return true
  if (isBrokenText(value)) return true
  if (toolName && value === toolName.trim()) return true
  if (toolCode && value === toolCode.trim()) return true
  if (GENERIC_TITLE_RE.test(value)) return true
  if (/^生成[\u4e00-\u9fa5]{0,4}$/.test(value)) return true
  if (TASK_NO_RE.test(value)) return true
  if (/^gpt[-_\s]?image/i.test(value)) return true
  if (/^dall[-_\s]?e/i.test(value)) return true
  if (/^seedance|^kling|^flux/i.test(value)) return true
  return false
}

export function promptExcerpt(value?: string | null, maxLength = 56) {
  const prompt = normalize(value).replace(/\s+/g, " ")
  if (!prompt || isBrokenText(prompt)) return ""
  if (prompt.length <= maxLength) return prompt
  return `${prompt.slice(0, maxLength).trim()}…`
}

export function modalityDisplayName(kind?: AssetPreviewItem["kind"] | string | null): string {
  const raw = normalize(kind).toLowerCase()
  if (raw.includes("image") || raw === "image") return "图像"
  if (raw.includes("video") || raw === "video") return "视频"
  if (raw.includes("audio") || raw === "audio") return "音频"
  if (raw.includes("text") || raw === "text") return "文本"
  return "作品"
}

export function buildSafeCommunityTitle(input: {
  toolName?: string | null
  toolCode?: string | null
  kind?: AssetPreviewItem["kind"]
  modality?: string | null
}): string {
  const tool = normalize(input.toolName) || normalize(input.toolCode) || "AI 工具"
  const modality = modalityDisplayName(input.modality || input.kind)
  const verb = modality === "音频" ? "生成" : "创作"
  return `由 ${tool} ${verb}的${modality}`
}

export function communityDisplayTitle(input: {
  title?: string | null
  prompt?: string | null
  promptPreview?: string | null
  promptVisible?: boolean | null
  topic?: string | null
  tags?: string[]
  toolName?: string | null
  toolCode?: string | null
  kind?: AssetPreviewItem["kind"]
  modality?: string | null
}) {
  const title = normalize(input.title)
  if (title && !isToolLikeLabel(title, input.toolName, input.toolCode)) return title

  const topic = normalize(input.topic)
  if (topic && !isBrokenText(topic)) return topic

  const tag = normalize(input.tags?.[0])
  if (tag && !isBrokenText(tag)) return tag.startsWith("#") ? tag.slice(1) : tag

  return buildSafeCommunityTitle(input)
}

export function communityDisplaySubtitle(input: {
  subtitle?: string | null
  description?: string | null
  prompt?: string | null
  promptPreview?: string | null
  promptVisible?: boolean | null
  topic?: string | null
  tags?: string[]
  toolName?: string | null
  toolCode?: string | null
  authorName?: string | null
}) {
  const author = normalize(input.authorName)
  if (author && !isBrokenText(author)) return author

  const description = normalize(input.subtitle) || normalize(input.description)
  if (description && !isToolLikeLabel(description, input.toolName, input.toolCode)) {
    return promptExcerpt(description, 42)
  }

  const topic = normalize(input.topic)
  if (topic && !isBrokenText(topic)) return topic

  const tag = normalize(input.tags?.[0])
  if (tag && !isBrokenText(tag)) return tag.startsWith("#") ? tag : `#${tag}`

  return ""
}

export function communityCardDescription(input: {
  description?: string | null
  prompt?: string | null
  promptPreview?: string | null
  promptVisible?: boolean | null
}): string {
  const description = normalize(input.description)
  if (description && !isBrokenText(description)) return promptExcerpt(description, 72)
  return ""
}

export function displayCollectionName(name?: string | null, defaultCollection?: boolean | null): string {
  const raw = normalize(name)
  if (!raw) return defaultCollection ? "默认收藏夹" : "未命名收藏夹"
  if (raw === "Default inspiration" || raw === "default inspiration") return "默认收藏夹"
  return raw
}

export function enrichCommunityAsset(asset: AssetPreviewItem): AssetPreviewItem {
  if (asset.source !== "community") return asset
  return {
    ...asset,
    title: communityDisplayTitle(asset),
    subtitle: communityDisplaySubtitle(asset) || undefined,
  }
}
