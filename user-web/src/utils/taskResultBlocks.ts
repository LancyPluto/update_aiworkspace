import { getRequestBaseUrl } from "@/api/client"
import type { TaskDetail } from "@/api/types"
import type { ResultBlock } from "@/types/result"

export function buildTaskResultBlocks(content: string, detail?: TaskDetail): ResultBlock[] {
  const outputModality = (detail?.outputModality || detail?.result?.resourceType || "TEXT").toUpperCase()
  const parsed = parseJson(content)
  const finalVideoUrl = extractFinalVideoUrl(content)

  if (detail?.toolCode === "enterprise_diagnosis_agent") {
    return [
      {
        type: "report",
        title: detail.toolName || "企业诊断报告",
        content,
        filename: `${detail.taskNo ?? "enterprise-diagnosis"}-report`,
      },
    ]
  }

  if (outputModality === "VIDEO" || finalVideoUrl) {
    const videoUrl = finalVideoUrl || collectUrls(parsed ?? content)[0]
    if (videoUrl) {
      return [
        {
          type: "video",
          title: "生成视频",
          url: normalizeMediaUrl(videoUrl),
          downloadName: `${detail?.taskNo ?? "video"}-final.mp4`,
        },
      ]
    }
  }

  if (outputModality === "IMAGE") {
    const imageUrls = collectImageUrls(parsed ?? content)
    const images = imageUrls.map((url, index) => ({
      url: normalizeMediaUrl(url),
      label: `图片 ${index + 1}`,
    }))
    if (images.length > 0) {
      return [{ type: "image", title: "生成图片", images }]
    }
  }

  if (outputModality === "AUDIO") {
    const audioUrl = collectUrls(parsed ?? content)[0]
    if (audioUrl) {
      return [
        {
          type: "audio",
          title: "生成音频",
          url: normalizeMediaUrl(audioUrl),
          downloadName: `${detail?.taskNo ?? "audio"}-result`,
        },
      ]
    }
  }

  if (parsed !== null && outputModality !== "TEXT") {
    return [{ type: "json", title: "结构化结果", content: JSON.stringify(parsed, null, 2) }]
  }
  return [{ type: "text", title: "生成结果", content }]
}

function parseJson(content: string): unknown | null {
  const trimmed = content.trim()
  if (!trimmed || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
    return null
  }
  try {
    return JSON.parse(trimmed)
  } catch {
    return null
  }
}

function collectUrls(value: unknown): string[] {
  const urls = new Set<string>()
  const visit = (item: unknown) => {
    if (typeof item === "string") {
      extractUrlsFromText(item).forEach((url) => urls.add(sanitizeUrl(url)))
      return
    }
    if (Array.isArray(item)) {
      item.forEach(visit)
      return
    }
    if (item && typeof item === "object") {
      Object.values(item).forEach(visit)
    }
  }
  visit(value)
  return Array.from(urls)
}

function collectImageUrls(value: unknown): string[] {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    const root = value as Record<string, unknown>
    const directImages = Array.isArray(root.images) ? root.images : undefined
    if (directImages) {
      const urls = directImages
        .map((item) => {
          if (typeof item === "string") return item
          if (item && typeof item === "object") {
            const record = item as Record<string, unknown>
            return firstString(record.url, record.imageUrl, record.image_url, record.sourceUrl)
          }
          return ""
        })
        .filter((url): url is string => Boolean(url))
      if (urls.length > 0) return uniqueUrls(urls)
    }
  }
  return uniqueUrls(collectUrls(value))
}

function firstString(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) return value.trim()
  }
  return ""
}

function uniqueUrls(urls: string[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const url of urls) {
    const normalized = sanitizeUrl(url)
    if (!normalized || seen.has(normalized)) continue
    seen.add(normalized)
    result.push(normalized)
  }
  return result
}

function extractUrlsFromText(value: string): string[] {
  const trimmed = value.trim()
  const direct = /^(https?:\/\/\S+|\/\S+|data:(?:image|audio|video)\/\S+;base64,\S+)$/i
  if (direct.test(trimmed)) {
    return [trimmed]
  }
  const matches = trimmed.match(/(?:https?:\/\/|\/)[^\s"'<>]+/g)
  return matches ?? []
}

function extractFinalVideoUrl(content: string): string {
  const patterns = [
    /最终成片[：:]\s*(\S+)/,
    /final\.mp4[)\]]?\s*[：:]?\s*(\S+)/i,
    /(\/generated\/\S+?\.mp4)/,
    /(https?:\/\/\S+?\.mp4(?:\?\S*)?)/,
  ]
  for (const pattern of patterns) {
    const match = content.match(pattern)
    if (match?.[1]) {
      return sanitizeUrl(match[1])
    }
  }
  return ""
}

function sanitizeUrl(value: string): string {
  return value.trim().replace(/[)\]，。,.]+$/g, "")
}

function normalizeMediaUrl(value: string): string {
  if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
    return value
  }
  const path = value.startsWith("/") ? value : `/${value}`
  const base = getRequestBaseUrl()
  return new URL(path, base.endsWith("/") ? base : `${base}/`).toString()
}
