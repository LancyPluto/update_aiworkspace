import type { TaskDetail } from "@/api/types"
import type { AudioTrackItem, ResultBlock } from "@/types/result"
import { normalizeMediaUrl } from "@/utils/toolCoverMedia"

/** 任务卡片封面：优先用生成结果中的视频/图片/音乐封面 URL */
export function extractTaskPreviewUrl(detail?: TaskDetail | null): string {
  const content = detail?.result?.contentText?.trim()
  if (!content) return ""
  const blocks = buildTaskResultBlocks(content, detail ?? undefined)
  for (const block of blocks) {
    if (block.type === "video" && block.url) return block.url
    if (block.type === "image" && block.images.length > 0) return block.images[0]!.url
    if (block.type === "audio") {
      const tracks = resolveAudioTracks(block)
      const cover = tracks.find((track) => track.coverUrl)?.coverUrl
      if (cover) return cover
      if (tracks[0]?.url) return tracks[0].url
    }
  }
  return ""
}

export function resolveAudioTracks(block: Extract<ResultBlock, { type: "audio" }>): AudioTrackItem[] {
  if (block.tracks?.length) return block.tracks
  return [{ url: block.url, title: block.title, downloadName: block.downloadName }]
}

export function formatAudioDuration(seconds?: number): string {
  if (seconds === undefined || seconds === null || Number.isNaN(seconds)) return ""
  const total = Math.max(0, Math.floor(seconds))
  const minutes = Math.floor(total / 60)
  const remain = total % 60
  return `${minutes}:${String(remain).padStart(2, "0")}`
}

export function buildTaskResultBlocks(content: string, detail?: TaskDetail): ResultBlock[] {
  const parsed = parseJson(content)
  const finalVideoUrl = extractFinalVideoUrl(content)
  const outputModality = (
    detail?.outputModality ||
    detail?.result?.resourceType ||
    inferOutputModality(parsed, content, finalVideoUrl) ||
    "TEXT"
  ).toUpperCase()

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
      const dlUrl = extractDownloadUrlFor(parsed, videoUrl)
      return [
        {
          type: "video",
          title: "生成视频",
          url: normalizeMediaUrl(videoUrl),
          downloadName: `${detail?.taskNo ?? "video"}-final.mp4`,
          ...(dlUrl ? { downloadUrl: dlUrl } : {}),
        },
      ]
    }
  }

  if (outputModality === "IMAGE") {
    const requestedImageCount = resolveRequestedImageCount(detail?.params)
    const imageItems = limitUrls(collectImageUrls(parsed ?? content), requestedImageCount)
    const downloadUrls = collectImageDownloadUrls(parsed)
    const images = imageItems.map((url, index) => ({
      url: normalizeMediaUrl(url),
      label: `图片 ${index + 1}`,
      ...(downloadUrls[index] ? { downloadUrl: downloadUrls[index] } : {}),
    }))
    if (images.length > 0) {
      return [{ type: "image", title: "生成图片", images }]
    }
  }

  if (outputModality === "AUDIO") {
    const tracks = collectAudioTracks(parsed, detail?.taskNo)
    if (tracks.length === 0) {
      const fallback = collectAudioUrls(parsed ?? content)[0]
      if (fallback) {
        tracks.push({
          url: normalizeMediaUrl(fallback),
          title: "生成音频",
          downloadName: `${detail?.taskNo ?? "audio"}-1`,
        })
      }
    }
    if (tracks.length > 0) {
      return [
        {
          type: "audio",
          title: tracks.length > 1 ? "生成音乐" : tracks[0]?.title || "生成音频",
          url: tracks[0]!.url,
          downloadName: tracks[0]?.downloadName,
          tracks,
        },
      ]
    }
  }

  if (parsed !== null && outputModality !== "TEXT") {
    return [{ type: "json", title: "结构化结果", content: JSON.stringify(parsed, null, 2) }]
  }
  return [{ type: "text", title: "生成结果", content }]
}

function collectAudioTracks(parsed: unknown | null, taskNo?: string | null): AudioTrackItem[] {
  if (!parsed || typeof parsed !== "object") return []
  const root = parsed as Record<string, unknown>
  const audios = root.audios
  if (!Array.isArray(audios)) return []

  const tracks: AudioTrackItem[] = []
  audios.forEach((item, index) => {
    if (!item || typeof item !== "object") return
    const row = item as Record<string, unknown>
    const url = firstString(row.url, row.audioUrl, row.audio_url)
    if (!url) return
    const coverRaw = firstString(row.imageUrl, row.image_url, row.coverUrl, row.cover_url)
    tracks.push({
      url: normalizeMediaUrl(url),
      title: firstString(row.title) || `版本 ${index + 1}`,
      coverUrl: coverRaw ? normalizeMediaUrl(coverRaw) : undefined,
      duration: typeof row.duration === "number" ? row.duration : undefined,
      downloadName: `${taskNo ?? "audio"}-${index + 1}`,
    })
  })
  return tracks
}

function resolveRequestedImageCount(params?: Record<string, unknown>): number | null {
  if (!params) return null
  for (const key of ["count", "outputCount", "imageCount", "numImages", "batchSize", "batch_size", "n"]) {
    const value = params[key]
    const numeric = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN
    if (Number.isInteger(numeric) && numeric > 0) return Math.min(numeric, 4)
  }
  return null
}

function limitUrls(urls: string[], limit: number | null): string[] {
  return limit ? urls.slice(0, limit) : urls
}

function inferOutputModality(parsed: unknown | null, content: string, finalVideoUrl: string): string {
  if (finalVideoUrl) return "VIDEO"
  if (parsed && typeof parsed === "object") {
    const root = parsed as Record<string, unknown>
    if (Array.isArray(root.images) && root.images.length > 0) return "IMAGE"
    if (Array.isArray(root.videos) && root.videos.length > 0) return "VIDEO"
    if (Array.isArray(root.audios) && root.audios.length > 0) return "AUDIO"
    if (typeof root.imageUrl === "string" || typeof root.image_url === "string" || typeof root.output_url === "string") return "IMAGE"
    if (typeof root.videoUrl === "string" || typeof root.video_url === "string") return "VIDEO"
    if (typeof root.audioUrl === "string" || typeof root.audio_url === "string") return "AUDIO"
  }
  if (/\/generated\/(?!uploads\/)\S+\.(?:png|jpe?g|webp|gif)|data:image\//i.test(content)) return "IMAGE"
  if (/\/generated\/.*\.mp4|data:video\//i.test(content)) return "VIDEO"
  if (/\/generated\/.*\.(mp3|wav|m4a)|data:audio\//i.test(content)) return "AUDIO"
  return ""
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
    const outputUrls = collectOutputImageUrls(root)
    if (outputUrls.length > 0) return outputUrls
  }
  return uniqueUrls(collectUrls(value).filter(isGeneratedImageOutputUrl))
}

function collectAudioUrls(value: unknown): string[] {
  return collectUrls(value).filter((url) => /\/generated\/.*\.(mp3|wav|m4a|flac|ogg|aac|webm)(?:\?|$)|data:audio\//i.test(url))
}

function collectOutputImageUrls(value: unknown): string[] {
  const urls: string[] = []
  const visit = (item: unknown, key = "") => {
    if (typeof item === "string") {
      if (isOutputMediaKey(key) || isGeneratedImageOutputUrl(item) || item.startsWith("data:image/")) {
        extractUrlsFromText(item).forEach((url) => {
          if (isImageUrl(url)) urls.push(url)
        })
      }
      return
    }
    if (Array.isArray(item)) {
      item.forEach((child) => visit(child, key))
      return
    }
    if (item && typeof item === "object") {
      for (const [childKey, childValue] of Object.entries(item as Record<string, unknown>)) {
        if (isInputMediaKey(childKey)) continue
        visit(childValue, childKey)
      }
    }
  }
  visit(value)
  return uniqueUrls(urls)
}

function isOutputMediaKey(key: string): boolean {
  return /^(url|uri|src|href|imageUrl|image_url|output|outputUrl|output_url|result|resultUrl|result_url|downloadUrl|download_url|fileUrl|file_url)$/i.test(key)
}

function isInputMediaKey(key: string): boolean {
  return /(input|reference|ref_|source|cover|thumbnail|upload|original|mask|init|prompt)/i.test(key)
}

function isGeneratedImageOutputUrl(value: string): boolean {
  const url = sanitizeUrl(value)
  return /\/generated\/(?!uploads\/)\S+\.(?:png|jpe?g|webp|gif)(?:\?\S*)?$/i.test(url)
}

function isImageUrl(value: string): boolean {
  const url = sanitizeUrl(value)
  return url.startsWith("data:image/") || /\.(?:png|jpe?g|webp|gif)(?:\?\S*)?$/i.test(url)
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

function collectImageDownloadUrls(parsed: unknown): string[] {
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return []
  const root = parsed as Record<string, unknown>
  const images = Array.isArray(root.images) ? root.images : []
  return images.map((item) => {
    if (item && typeof item === "object") {
      const record = item as Record<string, unknown>
      return typeof record.downloadUrl === "string" ? record.downloadUrl : ""
    }
    return ""
  })
}

function extractDownloadUrlFor(parsed: unknown, _displayUrl: string): string | undefined {
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return undefined
  const root = parsed as Record<string, unknown>
  if (typeof root.downloadUrl === "string") return root.downloadUrl
  const videos = Array.isArray(root.videos) ? root.videos : undefined
  if (videos) {
    for (const item of videos) {
      if (item && typeof item === "object") {
        const record = item as Record<string, unknown>
        if (typeof record.downloadUrl === "string") return record.downloadUrl
      }
    }
  }
  return undefined
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
