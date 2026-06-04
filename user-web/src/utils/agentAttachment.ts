import { getApiOrigin } from "@/api/client"
import { getSessionBearerJwt } from "@/api/sessionBearer"

const IMAGE_EXTENSIONS = [".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif"]
const previewUrlCache = new Map<string, string>()

export function isImageAttachment(contentType?: string | null, filename?: string | null) {
  const type = (contentType || "").toLowerCase()
  if (type.startsWith("image/")) return true
  const name = (filename || "").toLowerCase()
  return IMAGE_EXTENSIONS.some((ext) => name.endsWith(ext))
}

export function resolveAgentFileUrl(url?: string | null) {
  const raw = url?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:") || raw.startsWith("blob:")) return raw
  const origin = getApiOrigin()
  if (!origin) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  return `${origin}${path}`
}

/** Fetch authenticated agent file content and return a blob URL suitable for <img src>. */
export async function fetchAgentFilePreviewUrl(url?: string | null, token?: string | null): Promise<string> {
  const resolved = resolveAgentFileUrl(url)
  if (!resolved || resolved.startsWith("blob:") || resolved.startsWith("data:")) {
    return resolved
  }
  const cached = previewUrlCache.get(resolved)
  if (cached) return cached

  const headers: Record<string, string> = {}
  const bearer = token ?? getSessionBearerJwt()
  if (bearer) headers.Authorization = `Bearer ${bearer}`

  const response = await fetch(resolved, {
    method: "GET",
    credentials: "include",
    headers,
  })
  if (!response.ok) {
    return resolved
  }
  const blob = await response.blob()
  const objectUrl = URL.createObjectURL(blob)
  previewUrlCache.set(resolved, objectUrl)
  return objectUrl
}

export function revokeAgentFilePreviewUrl(url?: string | null) {
  const resolved = resolveAgentFileUrl(url)
  if (!resolved) return
  const cached = previewUrlCache.get(resolved)
  if (cached?.startsWith("blob:")) {
    URL.revokeObjectURL(cached)
    previewUrlCache.delete(resolved)
  }
}
