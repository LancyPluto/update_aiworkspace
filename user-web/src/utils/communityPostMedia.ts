import { getApiOrigin } from "@/api/client"
import type { CommunityPost, TaskDetail } from "@/api/types"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"

export type CommunityMediaKind = "image" | "video" | "audio" | "text"
export type CommunityDerivativeKind = "image-thumb" | "image-lqip" | "video-poster" | "video-preview"

export function normalizeCommunityMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function communityMediaDerivativeUrl(value: string, suffix: string, extension: string): string {
  const source = normalizeCommunityMediaUrl(value)
  if (!source || source.startsWith("data:")) return ""

  const queryIndex = source.indexOf("?")
  const hashIndex = source.indexOf("#")
  const splitIndex = [queryIndex, hashIndex].filter((index) => index >= 0).sort((a, b) => a - b)[0] ?? -1
  const path = splitIndex >= 0 ? source.slice(0, splitIndex) : source
  const tail = splitIndex >= 0 ? source.slice(splitIndex) : ""
  const slashIndex = path.lastIndexOf("/")
  const dotIndex = path.lastIndexOf(".")
  const hasExtension = dotIndex > slashIndex
  const base = hasExtension ? path.slice(0, dotIndex) : path

  return `${base}.${suffix}.${extension}${tail}`
}

export function resolveCommunityDerivativeUrl(value?: string | null, kind?: CommunityDerivativeKind): string {
  if (!value || !kind) return ""
  if (kind === "image-thumb") return communityMediaDerivativeUrl(value, "thumb-640", "webp")
  if (kind === "image-lqip") return communityMediaDerivativeUrl(value, "lqip-32", "webp")
  if (kind === "video-poster") return resolveOssVideoPosterUrl(value)
  return communityMediaDerivativeUrl(value, "preview-480p", "mp4")
}

const OSS_SNAPSHOT_SUFFIX = "?x-oss-process=video/snapshot,t_1000,f_jpg,w_640,h_0,m_fast"

export function resolveOssVideoPosterUrl(value?: string | null): string {
  const source = normalizeCommunityMediaUrl(value)
  if (!source) return ""
  if (source.includes(".oss-") || source.includes("/cdn/")) {
    const clean = source.split("?")[0].split("#")[0]
    return clean + OSS_SNAPSHOT_SUFFIX
  }
  return ""
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
