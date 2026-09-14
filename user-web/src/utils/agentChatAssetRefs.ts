import type { AgentMessage } from "@/api/types"
import { buildTaskResultBlocks, resolveAudioTracks } from "@/utils/taskResultBlocks"
import { resolveAgentFileUrl } from "@/utils/agentAttachment"

export const ASSET_DRAG_MIME = "application/x-aidesu-chat-asset"

export type ChatAssetKind = "image" | "audio" | "video"

export interface ChatAssetRef {
  assetKey: string
  refLabel: string
  kind: ChatAssetKind
  url: string
  name: string
  contentType?: string
}

export interface ChatAssetDragPayload {
  assetKey: string
  refLabel: string
  kind: ChatAssetKind
  url: string
  name: string
  contentType?: string
}

const KIND_LABELS: Record<ChatAssetKind, string> = {
  image: "图片",
  audio: "音频",
  video: "视频",
}

function parseMessageJson(value?: string | null): Record<string, unknown> {
  if (!value) return {}
  try {
    const parsed = JSON.parse(value) as unknown
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {}
  } catch {
    return {}
  }
}

function truncateTitle(value: string, max = 18): string {
  const trimmed = value.trim()
  if (trimmed.length <= max) return trimmed
  return `${trimmed.slice(0, max)}…`
}

function normalizeUrl(url: string): string {
  return resolveAgentFileUrl(url) || url
}

function attachmentBaseName(name: string): string {
  let result = name.trim()
  while (true) {
    const next = result
      .replace(/^@(?:图片|音频|视频)\d+-/i, "")
      .replace(/^@+/, "")
      .trim()
    if (next === result) break
    result = next
  }
  return result
}

function pushAsset(
  assets: ChatAssetRef[],
  seen: Map<string, ChatAssetRef>,
  counters: Record<ChatAssetKind, number>,
  kind: ChatAssetKind,
  url: string,
  name: string,
  contentType?: string,
) {
  const normalizedUrl = normalizeUrl(url)
  if (!normalizedUrl) return
  const assetKey = `${kind}:${normalizedUrl}`
  const existing = seen.get(assetKey)
  if (existing) return

  counters[kind] += 1
  const refLabel = `@${KIND_LABELS[kind]}${counters[kind]}-${truncateTitle(attachmentBaseName(name))}`
  const asset: ChatAssetRef = {
    assetKey,
    refLabel,
    kind,
    url: normalizedUrl,
    name: name.trim() || KIND_LABELS[kind],
    contentType,
  }
  seen.set(assetKey, asset)
  assets.push(asset)
}

export function collectSessionAssets(messages: AgentMessage[]): ChatAssetRef[] {
  const assets: ChatAssetRef[] = []
  const seen = new Map<string, ChatAssetRef>()
  const counters: Record<ChatAssetKind, number> = { image: 0, audio: 0, video: 0 }

  for (const message of messages) {
    const payload = parseMessageJson(message.contentJson)
    const raw = payload.attachments
    if (Array.isArray(raw)) {
      for (const item of raw) {
        if (!item || typeof item !== "object") continue
        const record = item as Record<string, unknown>
        const url = typeof record.url === "string" ? record.url : typeof record.downloadUrl === "string" ? record.downloadUrl : ""
        const name = typeof record.name === "string" ? record.name : "附件"
        const contentType = typeof record.contentType === "string" ? record.contentType : undefined
        if (!url) continue
        const lowerType = (contentType || name).toLowerCase()
        if (lowerType.includes("video") || url.endsWith(".mp4")) {
          pushAsset(assets, seen, counters, "video", url, name, contentType)
        } else if (lowerType.includes("audio") || /\.(mp3|wav|m4a)(\?|$)/i.test(url)) {
          pushAsset(assets, seen, counters, "audio", url, name, contentType)
        } else {
          pushAsset(assets, seen, counters, "image", url, name, contentType)
        }
      }
    }
    if (message.role === "USER") {
      continue
    }

    const content = message.contentText || ""
    if (!content.trim()) continue
    const blocks = buildTaskResultBlocks(content)
    for (const block of blocks) {
      if (block.type === "image") {
        block.images.forEach((image, index) => {
          pushAsset(
            assets,
            seen,
            counters,
            "image",
            image.url,
            image.label || block.title || `图片 ${index + 1}`,
            "image/*",
          )
        })
      } else if (block.type === "video") {
        pushAsset(assets, seen, counters, "video", block.url, block.title || "视频", "video/*")
      } else if (block.type === "audio") {
        resolveAudioTracks(block).forEach((track, index) => {
          pushAsset(
            assets,
            seen,
            counters,
            "audio",
            track.url,
            track.title || block.title || `音频 ${index + 1}`,
            "audio/*",
          )
        })
      }
    }
  }

  return assets
}

export function chatAssetRefByUrl(messages: AgentMessage[]): Map<string, ChatAssetRef> {
  const map = new Map<string, ChatAssetRef>()
  for (const asset of collectSessionAssets(messages)) {
    map.set(asset.url, asset)
    map.set(normalizeUrl(asset.url), asset)
  }
  return map
}

export function assetToDragPayload(asset: ChatAssetRef): ChatAssetDragPayload {
  return {
    assetKey: asset.assetKey,
    refLabel: asset.refLabel,
    kind: asset.kind,
    url: asset.url,
    name: asset.name,
    contentType: asset.contentType,
  }
}

export function writeAssetDragData(event: DragEvent, asset: ChatAssetRef) {
  const payload = assetToDragPayload(asset)
  event.dataTransfer?.setData(ASSET_DRAG_MIME, JSON.stringify(payload))
  event.dataTransfer?.setData("text/plain", payload.refLabel)
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "copy"
  }
}

export function readAssetDragPayload(event: DragEvent): ChatAssetDragPayload | null {
  const raw = event.dataTransfer?.getData(ASSET_DRAG_MIME)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as ChatAssetDragPayload
    if (!parsed?.url || !parsed.refLabel) return null
    return parsed
  } catch {
    return null
  }
}

export function dragPayloadToUrlAttachment(payload: ChatAssetDragPayload) {
  return {
    id: payload.assetKey,
    name: payload.refLabel,
    refLabel: payload.refLabel,
    contentType: payload.contentType || `${payload.kind}/*`,
    url: payload.url,
    source: "chat_reference" as const,
  }
}

export function bindLongPressReference(
  element: HTMLElement,
  asset: ChatAssetRef,
  onReference: (payload: ChatAssetDragPayload) => void,
) {
  let timer: number | null = null

  const clear = () => {
    if (timer != null) {
      window.clearTimeout(timer)
      timer = null
    }
  }

  const onTouchStart = () => {
    clear()
    timer = window.setTimeout(() => {
      onReference(assetToDragPayload(asset))
      timer = null
    }, 500)
  }

  element.addEventListener("touchstart", onTouchStart, { passive: true })
  element.addEventListener("touchend", clear, { passive: true })
  element.addEventListener("touchmove", clear, { passive: true })
  element.addEventListener("touchcancel", clear, { passive: true })

  return () => {
    clear()
    element.removeEventListener("touchstart", onTouchStart)
    element.removeEventListener("touchend", clear)
    element.removeEventListener("touchmove", clear)
    element.removeEventListener("touchcancel", clear)
  }
}
