import type { AgentRunEvent, CommunityPost, TaskDetail } from "@/api/types"
import type { AssetPreviewItem } from "@/types/assetPreview"
import type { ResultBlock } from "@/types/result"
import { communityDisplaySubtitle, communityDisplayTitle } from "@/utils/communityDisplay"
import { resolveCommunityAudioMedia } from "@/utils/communityAudioMedia"
import { resolveCommunityAuthorName, resolveCommunityAuthorAvatar, resolveCommunityPrompt } from "@/utils/communityPostNormalize"
import { buildTaskResultBlocks, resolveAudioTracks } from "@/utils/taskResultBlocks"

export function primaryResultBlock(blocks: ResultBlock[]): ResultBlock | null {
  return blocks.find((block) => block.type === "image" || block.type === "video" || block.type === "audio") || blocks[0] || null
}

export function taskPrompt(task: TaskDetail): string {
  const params = task.params || {}
  const value = params.prompt || params.text || params.description || params.videoTopic || params.productName
  return typeof value === "string" && value.trim() ? value.trim() : ""
}

export function taskPromptPreview(task: TaskDetail, maxLength = 48): string {
  const prompt = taskPrompt(task)
  if (!prompt) return task.taskNo
  if (prompt.length <= maxLength) return prompt
  return `${prompt.slice(0, maxLength)}...`
}

export function textFromResultBlock(block: ResultBlock | null, fallback = "") {
  if (!block) return fallback
  if (block.type === "text" || block.type === "json" || block.type === "report") return block.content
  if (block.type === "list") return block.items.join("\n")
  return fallback
}

export function assetFromTask(
  task: TaskDetail,
  options: {
    blocks?: ResultBlock[]
    idPrefix?: string
    source?: "private" | "community"
    modality?: string
    defaultPromptVisible?: boolean
  } = {},
): AssetPreviewItem | null {
  const blocks = options.blocks || buildTaskResultBlocks(task.result?.contentText || "", task)
  const block = primaryResultBlock(blocks)
  if (!block) {
    const fallbackUrl = extractPrimaryMediaUrl(task.result?.contentText || "")
    if (!fallbackUrl) return null
    const kind = inferKindFromUrl(fallbackUrl)
    return {
      id: `${options.idPrefix || "task"}-${task.taskId}`,
      source: options.source || "private",
      kind,
      title: task.toolName || task.taskNo,
      subtitle: task.taskNo,
      prompt: taskPrompt(task),
      taskId: task.taskId,
      taskNo: task.taskNo,
      toolName: task.toolName,
      toolCode: task.toolCode,
      communityPostId: task.communityPostId ?? undefined,
      modality: options.modality || task.outputModality || task.result?.resourceType || "TEXT",
      promptVisible: options.defaultPromptVisible,
      createdAt: task.finishedAt || task.createdAt,
      url: fallbackUrl,
    } as AssetPreviewItem
  }
  const base = {
    id: `${options.idPrefix || "task"}-${task.taskId}`,
    source: options.source || "private",
    title: task.toolName || block.title || task.taskNo,
    subtitle: task.taskNo,
    prompt: taskPrompt(task),
    taskId: task.taskId,
    taskNo: task.taskNo,
    toolName: task.toolName,
    toolCode: task.toolCode,
    communityPostId: task.communityPostId ?? undefined,
    promptVisible: task.communityPromptVisible ?? options.defaultPromptVisible,
    modality: options.modality || task.outputModality || task.result?.resourceType || "TEXT",
    createdAt: task.finishedAt || task.createdAt,
  } satisfies Partial<AssetPreviewItem>

  if (block.type === "image") {
    const urls = block.images.map((image) => image.url).filter(Boolean)
    const url = urls[0] || extractPrimaryMediaUrl(task.result?.contentText || "") || ""
    return {
      ...base,
      kind: "image",
      url,
      urls,
      title: block.title || base.title,
      subtitle: urls.length > 1 ? `${base.subtitle} · 共 ${urls.length} 张` : base.subtitle,
    } as AssetPreviewItem
  }
  if (block.type === "video") return { ...base, kind: "video", url: block.url, title: block.title || base.title } as AssetPreviewItem
  if (block.type === "audio") {
    const tracks = resolveAudioTracks(block)
    const first = tracks[0]
    return {
      ...base,
      kind: "audio",
      url: first?.url || block.url,
      urls: tracks.map((track) => track.url),
      coverUrl: tracks.find((track) => track.coverUrl)?.coverUrl,
      title: first?.title || block.title || base.title,
    } as AssetPreviewItem
  }
  if (block.type === "text" || block.type === "json" || block.type === "report") {
    return { ...base, kind: "text", rawText: block.content, title: block.title || base.title } as AssetPreviewItem
  }
  if (block.type === "list") {
    return { ...base, kind: "text", rawText: block.items.join("\n"), title: block.title || base.title } as AssetPreviewItem
  }
  return { ...base, kind: "other", rawText: task.result?.contentText || "", title: base.title } as AssetPreviewItem
}

function extractPrimaryMediaUrl(text: string): string {
  const raw = (text || "").trim()
  if (!raw) return ""
  const matches = raw.match(
    /(https?:\/\/\S+?\.(?:png|jpe?g|webp|gif|mp4|mp3|wav)(?:\?\S*)?)|(\/generated\/\S+?\.(?:png|jpe?g|webp|gif|mp4|mp3|wav)(?:\?\S*)?)/i,
  )
  return (matches?.[1] || matches?.[2] || "").replace(/[)\]，。,.、；;]+$/g, "")
}

function parseRunEventPayload(eventJson?: string | null): Record<string, unknown> {
  if (!eventJson) return {}
  try {
    const parsed = JSON.parse(eventJson) as unknown
    return parsed && typeof parsed === "object" ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function normalizeComparableUrl(value?: string | null): string {
  return (value || "").trim().replace(/\/+$/, "")
}

function urlsMatch(left?: string | null, right?: string | null): boolean {
  const a = normalizeComparableUrl(left)
  const b = normalizeComparableUrl(right)
  if (!a || !b) return false
  return a === b || a.endsWith(b) || b.endsWith(a)
}

function extractToolFinishedContent(payload: Record<string, unknown>): string {
  const data = payload.data
  if (data && typeof data === "object") {
    const contentText = (data as Record<string, unknown>).contentText
    if (typeof contentText === "string") return contentText
  }
  if (typeof payload.contentText === "string") return payload.contentText
  if (typeof payload.resultSummary === "string") return payload.resultSummary
  return ""
}

function contentContainsUrl(contentText: string, assetUrl: string): boolean {
  if (!contentText || !assetUrl) return false
  const normalized = normalizeComparableUrl(assetUrl)
  return contentText.includes(normalized) || contentText.includes(assetUrl)
}

export function promptFromToolEventPayload(payload: Record<string, unknown>): string {
  const argumentsValue = payload.arguments
  if (argumentsValue && typeof argumentsValue === "object") {
    const args = argumentsValue as Record<string, unknown>
    const value = args.prompt || args.text || args.description || args.videoTopic || args.productName
    if (typeof value === "string" && value.trim()) return value.trim()
  }
  if (typeof argumentsValue === "string") {
    try {
      const parsed = JSON.parse(argumentsValue) as Record<string, unknown>
      return promptFromToolEventPayload({ arguments: parsed })
    } catch {
      return ""
    }
  }
  return ""
}

export function resolveTaskIdFromRunEvents(events: AgentRunEvent[], assetUrl?: string | null): number | null {
  const finished = events
    .filter((event) => event.eventType === "tool.finished")
    .map((event) => parseRunEventPayload(event.eventJson))
    .filter((payload) => !payload.errorCode)

  for (let index = finished.length - 1; index >= 0; index -= 1) {
    const payload = finished[index]
    const taskId = Number(payload.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) continue
    if (!assetUrl) return taskId
    const contentText = extractToolFinishedContent(payload)
    if (urlsMatch(assetUrl, contentText) || contentContainsUrl(contentText, assetUrl)) {
      return taskId
    }
  }

  const fallbackTaskId = Number(finished[finished.length - 1]?.taskId)
  return Number.isFinite(fallbackTaskId) && fallbackTaskId > 0 ? fallbackTaskId : null
}

export function mergeAssetWithTask(
  asset: AssetPreviewItem,
  task: TaskDetail,
  options: { defaultPromptVisible?: boolean } = {},
): AssetPreviewItem {
  const fromTask = assetFromTask(task, {
    defaultPromptVisible: options.defaultPromptVisible,
  })
  if (!fromTask) return asset
  return {
    ...fromTask,
    ...asset,
    id: asset.id || fromTask.id,
    kind: asset.kind || fromTask.kind,
    url: asset.url || fromTask.url,
    urls: asset.urls?.length ? asset.urls : fromTask.urls,
    coverUrl: asset.coverUrl || fromTask.coverUrl,
    title: asset.title || fromTask.title,
    prompt: asset.prompt || fromTask.prompt,
    rawText: asset.rawText || fromTask.rawText,
    toolName: fromTask.toolName || asset.toolName,
    toolCode: fromTask.toolCode || asset.toolCode,
    taskNo: fromTask.taskNo || asset.taskNo,
    taskId: fromTask.taskId || asset.taskId,
    createdAt: fromTask.createdAt || asset.createdAt,
    communityPostId: fromTask.communityPostId ?? asset.communityPostId,
    promptVisible: asset.promptVisible ?? fromTask.promptVisible ?? options.defaultPromptVisible,
    source: asset.source || fromTask.source || "private",
  }
}

function inferKindFromUrl(url: string): AssetPreviewItem["kind"] {
  const lower = (url || "").toLowerCase()
  if (lower.endsWith(".mp4") || lower.includes(".mp4?")) return "video"
  if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.includes(".mp3?") || lower.includes(".wav?")) return "audio"
  if (/\.(png|jpg|jpeg|webp|gif)(\?|$)/i.test(lower)) return "image"
  return "other"
}

export function assetFromCommunityPost(post: CommunityPost, url?: string): AssetPreviewItem {
  const kind = communityKind(post.modality)
  const resolvedPrompt = resolveCommunityPrompt(post)
  const displayInput = {
    title: post.title,
    subtitle: post.description || undefined,
    description: post.description || undefined,
    prompt: resolvedPrompt || undefined,
    promptPreview: post.promptPreview || resolvedPrompt || undefined,
    topic: post.topic,
    tags: post.tags || [],
    toolName: post.toolName,
    toolCode: post.toolCode,
    kind,
  }
  const authorName = resolveCommunityAuthorName(post)
  const audioMedia = kind === "audio" ? resolveCommunityAudioMedia(post) : null
  return {
    id: `community-${post.id}`,
    source: "community",
    kind,
    title: communityDisplayTitle(displayInput),
    subtitle: communityDisplaySubtitle({ ...displayInput, authorName: undefined }) || undefined,
    url: url || audioMedia?.audioUrl || post.mediaUrl || post.coverUrl || undefined,
    coverUrl: audioMedia?.coverUrl || undefined,
    prompt: resolvedPrompt || undefined,
    rawText: kind === "text" ? resolvedPrompt || post.description || post.title : undefined,
    taskId: post.taskId,
    urls: post.mediaUrls?.length ? post.mediaUrls : undefined,
    toolName: post.toolName || undefined,
    toolCode: post.toolCode || undefined,
    communityPostId: post.id,
    authorName: authorName || undefined,
    authorAvatarUrl: resolveCommunityAuthorAvatar(post) || undefined,
    authorUserId: post.userId,
    promptVisible: post.promptVisible,
    modality: post.modality,
    topic: post.topic,
    tags: post.tags || [],
    featured: post.featured,
    pinned: post.pinned,
    stats: {
      views: post.viewCount,
      likes: post.likeCount,
      favorites: post.favoriteCount,
      sameStyle: post.sameStyleCount || 0,
    },
    createdAt: post.createdAt,
  }
}

function communityKind(modality?: string | null): AssetPreviewItem["kind"] {
  const value = (modality || "").toLowerCase()
  if (value.includes("image")) return "image"
  if (value.includes("video")) return "video"
  if (value.includes("audio")) return "audio"
  if (value.includes("text")) return "text"
  return "other"
}
