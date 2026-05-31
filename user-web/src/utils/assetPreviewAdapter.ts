import type { CommunityPost, TaskDetail } from "@/api/types"
import type { AssetPreviewItem } from "@/types/assetPreview"
import type { ResultBlock } from "@/types/result"
import { communityDisplaySubtitle, communityDisplayTitle } from "@/utils/communityDisplay"
import { resolveCommunityAuthorName, resolveCommunityAuthorAvatar, resolveCommunityPrompt } from "@/utils/communityPostNormalize"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"

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
  options: { blocks?: ResultBlock[]; idPrefix?: string; source?: "private" | "community"; modality?: string } = {},
): AssetPreviewItem | null {
  const blocks = options.blocks || buildTaskResultBlocks(task.result?.contentText || "", task)
  const block = primaryResultBlock(blocks)
  if (!block) return null
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
    modality: options.modality || task.outputModality || task.result?.resourceType || "TEXT",
    createdAt: task.createdAt,
  } satisfies Partial<AssetPreviewItem>

  if (block.type === "image") {
    return {
      ...base,
      kind: "image",
      url: block.images[0]?.url,
      urls: block.images.map((image) => image.url),
      title: block.title || base.title,
    } as AssetPreviewItem
  }
  if (block.type === "video") return { ...base, kind: "video", url: block.url, title: block.title || base.title } as AssetPreviewItem
  if (block.type === "audio") return { ...base, kind: "audio", url: block.url, title: block.title || base.title } as AssetPreviewItem
  if (block.type === "text" || block.type === "json" || block.type === "report") {
    return { ...base, kind: "text", rawText: block.content, title: block.title || base.title } as AssetPreviewItem
  }
  if (block.type === "list") {
    return { ...base, kind: "text", rawText: block.items.join("\n"), title: block.title || base.title } as AssetPreviewItem
  }
  return { ...base, kind: "other", rawText: task.result?.contentText || "", title: base.title } as AssetPreviewItem
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
  return {
    id: `community-${post.id}`,
    source: "community",
    kind,
    title: communityDisplayTitle(displayInput),
    subtitle: communityDisplaySubtitle({ ...displayInput, authorName: undefined }) || undefined,
    url: url || post.coverUrl || undefined,
    prompt: resolvedPrompt || undefined,
    rawText: kind === "text" ? resolvedPrompt || post.description || post.title : undefined,
    taskId: post.taskId,
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
