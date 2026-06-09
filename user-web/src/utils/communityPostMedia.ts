import { getApiOrigin } from "@/api/client"
import type { CommunityPost, TaskDetail } from "@/api/types"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"

export type CommunityMediaKind = "image" | "video" | "audio" | "text"

export function normalizeCommunityMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

export function resolveCommunityPostKind(modality?: string | null): CommunityMediaKind {
  const value = (modality || "").toLowerCase()
  if (value.includes("video")) return "video"
  if (value.includes("audio")) return "audio"
  if (value.includes("image")) return "image"
  return "text"
}

export function resolveCommunityImageUrls(
  post: Pick<CommunityPost, "modality" | "mediaUrls" | "mediaUrl" | "coverUrl">,
  extraUrls: string[] = [],
): string[] {
  if (resolveCommunityPostKind(post.modality) !== "image") return []

  const rawUrls = post.mediaUrls?.length ? post.mediaUrls : [post.mediaUrl, post.coverUrl]
  const urls = [...rawUrls, ...extraUrls]
    .map((url) => normalizeCommunityMediaUrl(url))
    .filter((url): url is string => Boolean(url))

  return [...new Set(urls)]
}

export function extractImageUrlsFromTask(task?: TaskDetail | null): string[] {
  const content = task?.result?.contentText?.trim()
  if (!content) return []

  const blocks = buildTaskResultBlocks(content, task)
  const imageBlock = blocks.find((block) => block.type === "image")
  if (imageBlock?.type !== "image") return []

  return imageBlock.images
    .map((image) => normalizeCommunityMediaUrl(image.url))
    .filter((url): url is string => Boolean(url))
}
