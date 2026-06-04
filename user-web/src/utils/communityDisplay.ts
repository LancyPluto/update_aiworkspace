import type { AssetPreviewItem } from "@/types/assetPreview"

const GENERIC_TITLE_RE = /^(生成完成|已完成|未命名|untitled|生成图片|生成视频|生成音频|生成文本|生成作品)$/i
const TASK_NO_RE = /^(TASK|T\d{8,}|[A-Z0-9]{2,}[-_][A-Z0-9_-]{4,})$/i
const MOJIBAKE_TITLE_RE = /^(?:\?{2,}|�{1,}|[\s?]+(?:\d{8,})?)$/i

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
  return `${prompt.slice(0, maxLength).trim()}...`
}

function kindPlaceholder(kind?: AssetPreviewItem["kind"]) {
  if (kind === "video") return "视频灵感案例"
  if (kind === "image") return "视觉生成案例"
  if (kind === "audio") return "音频创作案例"
  if (kind === "text") return "文案生成案例"
  return "AI 创作案例"
}

export function communityDisplayTitle(input: {
  title?: string | null
  prompt?: string | null
  promptPreview?: string | null
  topic?: string | null
  tags?: string[]
  toolName?: string | null
  toolCode?: string | null
  kind?: AssetPreviewItem["kind"]
}) {
  const title = normalize(input.title)
  if (title && !isToolLikeLabel(title, input.toolName, input.toolCode)) return title

  const prompt = promptExcerpt(input.promptPreview || input.prompt, 48)
  if (prompt) return prompt

  const topic = normalize(input.topic)
  if (topic && !isBrokenText(topic)) return topic

  const tag = normalize(input.tags?.[0])
  if (tag && !isBrokenText(tag)) return tag.startsWith("#") ? tag.slice(1) : tag

  return kindPlaceholder(input.kind)
}

export function communityDisplaySubtitle(input: {
  subtitle?: string | null
  description?: string | null
  prompt?: string | null
  promptPreview?: string | null
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

  const prompt = promptExcerpt(input.promptPreview || input.prompt, 42)
  if (prompt) return prompt

  const topic = normalize(input.topic)
  if (topic && !isBrokenText(topic)) return topic

  const tag = normalize(input.tags?.[0])
  if (tag && !isBrokenText(tag)) return tag.startsWith("#") ? tag : `#${tag}`

  return ""
}

export function enrichCommunityAsset(asset: AssetPreviewItem): AssetPreviewItem {
  if (asset.source !== "community") return asset
  return {
    ...asset,
    title: communityDisplayTitle(asset),
    subtitle: communityDisplaySubtitle(asset) || undefined,
  }
}
