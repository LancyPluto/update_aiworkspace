import type { CommunityPost } from "@/api/types"

export function isAudioMediaUrl(url?: string | null) {
  const value = (url || "").trim().toLowerCase()
  if (!value) return false
  return /\.(mp3|wav|m4a|flac|ogg|aac|webm)(\?|$)/i.test(value) || value.startsWith("data:audio/")
}

export function isImageMediaUrl(url?: string | null) {
  const value = (url || "").trim().toLowerCase()
  if (!value) return false
  return /\.(png|jpe?g|webp|gif)(\?|$)/i.test(value) || value.startsWith("data:image/")
}

export function resolveCommunityAudioMedia(post: Pick<CommunityPost, "coverUrl" | "mediaUrl" | "modality">) {
  const coverRaw = post.coverUrl?.trim() || ""
  const mediaRaw = post.mediaUrl?.trim() || ""

  let coverUrl = ""
  let audioUrl = ""

  if (isImageMediaUrl(coverRaw)) coverUrl = coverRaw
  if (isAudioMediaUrl(coverRaw)) audioUrl = coverRaw
  if (isImageMediaUrl(mediaRaw) && !coverUrl) coverUrl = mediaRaw
  if (isAudioMediaUrl(mediaRaw)) audioUrl = mediaRaw

  return { coverUrl, audioUrl }
}

export function hasCommunityAudioMedia(post: Pick<CommunityPost, "coverUrl" | "mediaUrl" | "modality">) {
  if (!(post.modality || "").toUpperCase().includes("AUDIO")) return false
  const { coverUrl, audioUrl } = resolveCommunityAudioMedia(post)
  return Boolean(coverUrl || audioUrl)
}
