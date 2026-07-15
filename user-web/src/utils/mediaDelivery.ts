import { getApiOrigin } from "@/api/client"

export type ImageDeliveryPreset = "lqip" | "list" | "card" | "detail"

const IMAGE_PRESETS: Record<ImageDeliveryPreset, { width: number; quality: number }> = {
  lqip: { width: 32, quality: 30 },
  list: { width: 320, quality: 80 },
  card: { width: 640, quality: 85 },
  detail: { width: 1280, quality: 88 },
}

export function normalizeMediaDeliveryUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (/^(https?:|data:|blob:)/i.test(raw)) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const origin = getApiOrigin()
  return origin ? `${origin}${path}` : path
}

export function isManagedTransformUrl(value?: string | null): boolean {
  const source = normalizeMediaDeliveryUrl(value)
  if (!source || source.startsWith("data:") || source.startsWith("blob:")) return false
  if (/([?&])(signature|ossaccesskeyid|auth_key|expires)=/i.test(source)) return false
  return source.includes(".oss-")
    || source.includes("/cdn/")
    || source.includes("cdn.wlcloudai.com")
    || source.includes("/api/v1/assets/private/")
}

export function buildOssImageVariant(value: string, preset: ImageDeliveryPreset): string {
  const source = normalizeMediaDeliveryUrl(value)
  if (!source || !isManagedTransformUrl(source)) return ""
  const { width, quality } = IMAGE_PRESETS[preset]
  const hashIndex = source.indexOf("#")
  const withoutHash = hashIndex >= 0 ? source.slice(0, hashIndex) : source
  const hash = hashIndex >= 0 ? source.slice(hashIndex + 1) : ""
  const queryIndex = withoutHash.indexOf("?")
  const path = queryIndex >= 0 ? withoutHash.slice(0, queryIndex) : withoutHash
  const query = new URLSearchParams(queryIndex >= 0 ? withoutHash.slice(queryIndex + 1) : "")
  query.delete("x-oss-process")
  query.set("x-oss-process", `image/resize,w_${width}/format,webp/quality,q_${quality}`)
  const variant = `${path}?${query.toString()}`
  return hash ? `${variant}#${hash}` : variant
}

export function buildImageCandidateChain(
  value?: string | null,
  preset: Exclude<ImageDeliveryPreset, "lqip"> = "card",
  optimizationEnabled = true,
): string[] {
  const original = normalizeMediaDeliveryUrl(value)
  if (!original) return []
  const candidates = optimizationEnabled ? [buildOssImageVariant(original, preset), original] : [original]
  return [...new Set(candidates.filter(Boolean))]
}

export function buildImageLqip(value?: string | null, optimizationEnabled = true): string {
  return optimizationEnabled ? buildOssImageVariant(normalizeMediaDeliveryUrl(value), "lqip") : ""
}

export function buildVideoCandidateChain(value?: string | null, optimizationEnabled = true): string[] {
  const original = normalizeMediaDeliveryUrl(value)
  if (!original) return []
  if (!optimizationEnabled || !isManagedTransformUrl(original)) return [original]
  const queryIndex = original.search(/[?#]/)
  const path = queryIndex >= 0 ? original.slice(0, queryIndex) : original
  const tail = queryIndex >= 0 ? original.slice(queryIndex) : ""
  const slash = path.lastIndexOf("/")
  const dot = path.lastIndexOf(".")
  const preview = `${dot > slash ? path.slice(0, dot) : path}.preview-480p.mp4${tail}`
  return [...new Set([preview, original])]
}

export function buildVideoPosterUrl(value?: string | null, optimizationEnabled = true): string {
  const original = normalizeMediaDeliveryUrl(value)
  if (!original || !optimizationEnabled || !isManagedTransformUrl(original)) return ""
  const hashIndex = original.indexOf("#")
  const withoutHash = hashIndex >= 0 ? original.slice(0, hashIndex) : original
  const hash = hashIndex >= 0 ? original.slice(hashIndex + 1) : ""
  const queryIndex = withoutHash.indexOf("?")
  const path = queryIndex >= 0 ? withoutHash.slice(0, queryIndex) : withoutHash
  const query = new URLSearchParams(queryIndex >= 0 ? withoutHash.slice(queryIndex + 1) : "")
  query.delete("x-oss-process")
  query.set("x-oss-process", "video/snapshot,t_1000,f_jpg,w_640,h_0,m_fast")
  const poster = `${path}?${query.toString()}`
  return hash ? `${poster}#${hash}` : poster
}

export function mediaDeliveryOptimizationEnabled(): boolean {
  const globalFlag = String(import.meta.env.VITE_MEDIA_DELIVERY_OPTIMIZATION ?? "").toLowerCase()
  const legacyFlag = String(import.meta.env.VITE_COMMUNITY_MEDIA_OPTIMIZATION ?? "").toLowerCase()
  const value = globalFlag || legacyFlag
  return ["1", "true", "yes", "on"].includes(value)
    || (import.meta.env.PROD && !["0", "false", "no", "off"].includes(value))
}
