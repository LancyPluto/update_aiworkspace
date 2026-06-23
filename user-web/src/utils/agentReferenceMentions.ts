import type { AgentFile, AgentUrlAttachment } from "@/api/types"
import type { ChatAssetRef } from "@/utils/agentChatAssetRefs"
import { resolveAgentFileUrl } from "@/utils/agentAttachment"

export interface AgentReferenceMention {
  token: string
  refLabel: string
  assetKey?: string
  fileId?: number | string
  url: string
  kind?: "image" | "video" | "audio" | "file" | string
  name?: string
  contentType?: string | null
  previewUrl?: string
  source?: "current_turn" | "session_asset" | "url" | "agent_file" | string
}

export interface ReferencePickerOption {
  dedupeKey: string
  displayLabel: string
  refLabel: string
  subtitle: string
  previewUrl?: string
  mention: AgentReferenceMention
}

const AT_TOKEN_PATTERN = /@[^\s@]+/g

const SOURCE_PRIORITY: Record<string, number> = {
  current_turn: 0,
  agent_file: 1,
  url: 2,
  session_asset: 3,
}

function normalizeAssetUrl(url: string): string {
  return resolveAgentFileUrl(url) || url.trim()
}

export function mentionDedupeKey(mention: Pick<AgentReferenceMention, "assetKey" | "fileId" | "url">): string {
  const key = mention.assetKey?.trim()
  if (key) return key
  if (mention.fileId != null && String(mention.fileId).trim()) return `file_${mention.fileId}`
  const url = normalizeAssetUrl(mention.url)
  if (url) return `url:${url}`
  return mention.url.trim()
}

function stripExtension(name: string): string {
  return name.replace(/\.[a-z0-9]{2,5}$/i, "").trim()
}

function sanitizeShortName(name: string, max = 14): string {
  const base = stripExtension(name)
    .replace(/^@+/, "")
    .replace(/^(?:图|图片|视频|音频|文件)\d+[-_]?/i, "")
    .replace(/[@\s]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_|_$/g, "")
  if (!base) return ""
  if (base.length <= max) return base
  return `${base.slice(0, max)}…`
}

export function displayLabelForMention(
  mention: Pick<AgentReferenceMention, "kind" | "source">,
  index: number,
): string {
  return `@${displayKindLabel(mention.kind)}${index}`
}

export function extractAtTokens(message: string): string[] {
  const seen = new Set<string>()
  const ordered: string[] = []
  for (const match of message.matchAll(AT_TOKEN_PATTERN)) {
    const token = match[0]?.trim()
    if (!token || seen.has(token)) continue
    seen.add(token)
    ordered.push(token)
  }
  return ordered
}

export function baseImageLabel(label: string): string | null {
  const match = label.match(/^(@(?:图|图片)\d+)/)
  return match?.[1] ?? null
}

function addCatalogEntry(
  catalog: Map<string, AgentReferenceMention>,
  mention: AgentReferenceMention,
) {
  const key = mentionDedupeKey(mention)
  const existingEntry = findCatalogEntry(catalog, mention)
  if (!existingEntry) {
    catalog.set(key, mention)
    return
  }
  const [existingKey, existing] = existingEntry
  const existingPriority = SOURCE_PRIORITY[existing.source ?? ""] ?? 99
  const nextPriority = SOURCE_PRIORITY[mention.source ?? ""] ?? 99
  if (nextPriority < existingPriority) {
    catalog.set(existingKey, {
      ...mention,
      token: existing.token,
      refLabel: existing.refLabel,
    })
  }
}

function findCatalogEntry(
  catalog: Map<string, AgentReferenceMention>,
  asset: Pick<AgentReferenceMention, "assetKey" | "fileId" | "url">,
): [string, AgentReferenceMention] | undefined {
  const key = mentionDedupeKey(asset)
  const direct = catalog.get(key)
  if (direct) return [key, direct]
  const normalizedUrl = normalizeAssetUrl(asset.url)
  for (const entry of catalog.entries()) {
    const [entryKey, mention] = entry
    if (asset.assetKey && mention.assetKey === asset.assetKey) return entry
    if (asset.fileId != null && mention.fileId != null && String(mention.fileId) === String(asset.fileId)) return entry
    if (normalizedUrl && normalizeAssetUrl(mention.url) === normalizedUrl) return entry
    if (entryKey === key) return entry
  }
  return undefined
}

function displayKindLabel(kind?: string): string {
  if (kind === "video") return "视频"
  if (kind === "audio") return "音频"
  if (kind === "file") return "文件"
  return "图"
}

function refKindLabel(kind?: string): string {
  if (kind === "video") return "视频"
  if (kind === "audio") return "音频"
  if (kind === "file") return "文件"
  return "图片"
}

function sessionRefLabel(kind: string | undefined, index: number, name?: string | null): string {
  const prefix = refKindLabel(kind)
  const shortName = sanitizeShortName(name || prefix, 18) || prefix
  return `@${prefix}${index}-${shortName}`
}

function sourceSubtitle(source?: string, name?: string | null): string {
  const prefix = source === "session_asset" ? "会话素材" : "本轮素材"
  const cleanName = sanitizeShortName(name || "", 20)
  return cleanName ? `${prefix} · ${cleanName}` : prefix
}

function nextIndex(counters: Record<string, number>, kind?: string): number {
  const key = kind === "video" || kind === "audio" || kind === "file" ? kind : "image"
  counters[key] = (counters[key] ?? 0) + 1
  return counters[key]
}

export function buildAttachmentLabelCatalog(
  urlItems: AgentUrlAttachment[],
  files: AgentFile[],
  sessionAssets: ChatAssetRef[] = [],
): Map<string, AgentReferenceMention> {
  const catalog = new Map<string, AgentReferenceMention>()
  const counters: Record<string, number> = { image: 0, video: 0, audio: 0, file: 0 }

  for (const asset of sessionAssets) {
    const kind = asset.kind
    const existing = findCatalogEntry(catalog, asset)?.[1]
    const index = existing ? 0 : nextIndex(counters, kind)
    const refLabel = existing?.refLabel || sessionRefLabel(kind, index, asset.name || asset.refLabel)
    addCatalogEntry(catalog, {
      token: existing?.token || displayLabelForMention({ kind, source: "session_asset" }, index),
      refLabel,
      assetKey: asset.assetKey,
      url: asset.url,
      kind,
      name: asset.name,
      contentType: asset.contentType,
      previewUrl: asset.kind === "image" ? resolveAgentFileUrl(asset.url) : undefined,
      source: "session_asset",
    })
  }

  urlItems.forEach((item) => {
    const kind = attachmentKind(item.contentType, item.name)
    const identity = { assetKey: String(item.id ?? item.url), fileId: item.id, url: item.url }
    const existing = findCatalogEntry(catalog, identity)?.[1]
    const index = existing ? 0 : nextIndex(counters, kind)
    const name = item.name || item.refLabel || refKindLabel(kind)
    const refLabel = existing?.refLabel || sessionRefLabel(kind, index, name)
    const source = item.source === "chat_reference" ? "session_asset" : "current_turn"
    addCatalogEntry(catalog, {
      token: existing?.token || displayLabelForMention({ kind, source }, index),
      refLabel,
      assetKey: identity.assetKey,
      fileId: identity.fileId,
      url: item.url,
      kind,
      name: item.name,
      contentType: item.contentType,
      previewUrl: kind === "image" ? resolveAgentFileUrl(item.url) : undefined,
      source,
    })
  })

  files.forEach((file) => {
    if (!file.downloadUrl) return
    const kind = attachmentKind(file.contentType, file.originalFilename)
    const identity = { assetKey: `agent_file:${file.id}`, fileId: file.id, url: file.downloadUrl }
    const existing = findCatalogEntry(catalog, identity)?.[1]
    const index = existing ? 0 : nextIndex(counters, kind)
    const name = file.originalFilename || `${refKindLabel(kind)}${index}`
    const refLabel = existing?.refLabel || sessionRefLabel(kind, index, name)
    addCatalogEntry(catalog, {
      token: existing?.token || displayLabelForMention({ kind, source: "agent_file" }, index),
      refLabel,
      assetKey: identity.assetKey,
      fileId: identity.fileId,
      url: identity.url,
      kind,
      name: file.originalFilename,
      contentType: file.contentType,
      previewUrl: resolveAgentFileUrl(file.downloadUrl),
      source: "agent_file",
    })
  })

  return catalog
}

export function findReferenceMentionByAsset(
  catalog: Map<string, AgentReferenceMention>,
  asset: Pick<AgentReferenceMention, "assetKey" | "fileId" | "url">,
): AgentReferenceMention | undefined {
  return findCatalogEntry(catalog, asset)?.[1]
}

export function listReferencePickerOptions(
  urlItems: AgentUrlAttachment[],
  files: AgentFile[],
  sessionAssets: ChatAssetRef[] = [],
): ReferencePickerOption[] {
  const catalog = buildAttachmentLabelCatalog(urlItems, files, sessionAssets)
  const options: ReferencePickerOption[] = []
  const seen = new Set<string>()

  catalog.forEach((mention) => {
    const dedupeKey = mentionDedupeKey(mention)
    if (seen.has(dedupeKey)) return
    seen.add(dedupeKey)

    options.push({
      dedupeKey,
      displayLabel: mention.token,
      refLabel: mention.refLabel,
      subtitle: sourceSubtitle(mention.source, mention.name || mention.refLabel),
      previewUrl: mention.kind === "image" ? resolveAgentFileUrl(mention.url) : undefined,
      mention,
    })
  })

  return options
}

function findMentionByToken(
  token: string,
  catalog: Map<string, AgentReferenceMention>,
): AgentReferenceMention | undefined {
  for (const mention of catalog.values()) {
    if (mention.token === token || mention.refLabel === token) return mention
  }
  const base = baseImageLabel(token)
  if (base) {
    for (const mention of catalog.values()) {
      if (mention.token === base || mention.refLabel.startsWith(base)) return mention
    }
  }
  for (const mention of catalog.values()) {
    const mentionBase = baseImageLabel(mention.refLabel) || mention.token
    if (mention.refLabel.startsWith(token) || token.startsWith(mentionBase)) return mention
  }
  return undefined
}

export function resolveReferenceMentions(
  message: string,
  catalog: Map<string, AgentReferenceMention>,
): AgentReferenceMention[] {
  const mentions: AgentReferenceMention[] = []
  const seen = new Set<string>()
  for (const token of extractAtTokens(message)) {
    const mention = findMentionByToken(token, catalog)
    if (!mention) continue
    const key = mentionDedupeKey(mention)
    if (seen.has(key)) continue
    seen.add(key)
    mentions.push({ ...mention, token })
  }
  return mentions
}

export function mergeExplicitReferenceMentions(
  message: string,
  urlItems: AgentUrlAttachment[],
  files: AgentFile[],
  sessionAssets: ChatAssetRef[] = [],
  explicitMentions: AgentReferenceMention[] = [],
): AgentReferenceMention[] {
  const catalog = buildAttachmentLabelCatalog(urlItems, files, sessionAssets)
  if (explicitMentions.length > 0) {
    return explicitMentions
      .filter((mention) => Boolean(mention.url))
      .map((mention) => {
        const canonical = findReferenceMentionByAsset(catalog, mention)
        if (!canonical) return mention
        return {
          ...canonical,
          token: mention.token || canonical.token,
          refLabel: canonical.refLabel,
        }
      })
  }
  return resolveReferenceMentions(message, catalog)
}

export function buildReferenceMentionsPayload(
  message: string,
  urlItems: AgentUrlAttachment[],
  files: AgentFile[],
  sessionAssets: ChatAssetRef[] = [],
  explicitMentions: AgentReferenceMention[] = [],
): AgentReferenceMention[] {
  return mergeExplicitReferenceMentions(message, urlItems, files, sessionAssets, explicitMentions)
}

function attachmentKind(contentType?: string | null, name?: string | null): "image" | "video" | "audio" | "file" {
  const haystack = `${contentType || ""} ${name || ""}`.toLowerCase()
  if (haystack.includes("video") || /\.(mp4|mov|webm|mkv)(\?|$)/i.test(haystack)) return "video"
  if (haystack.includes("audio") || /\.(mp3|wav|m4a|aac|ogg|flac)(\?|$)/i.test(haystack)) return "audio"
  if (haystack.includes("image") || /\.(png|jpe?g|webp|gif|bmp|heic|heif|avif)(\?|$)/i.test(haystack)) return "image"
  return "file"
}
