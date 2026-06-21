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

export function mentionDedupeKey(mention: Pick<AgentReferenceMention, "assetKey" | "url">): string {
  const key = mention.assetKey?.trim()
  if (key) return key
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
    .replace(/^图片\d+[-_]?/i, "")
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
  name?: string,
): string {
  const kindLabel =
    mention.kind === "video" ? "视频" : mention.kind === "audio" ? "音频" : mention.kind === "file" ? "文件" : "图片"
  const shortName = sanitizeShortName(name || "")
  if (shortName && shortName.length <= 12 && !/^图片\d+$/i.test(shortName)) {
    return `@${shortName}`
  }
  return `@${kindLabel}${index}`
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
  const match = label.match(/^(@图片\d+)/)
  return match?.[1] ?? null
}

function addCatalogEntry(
  catalog: Map<string, AgentReferenceMention>,
  mention: AgentReferenceMention,
) {
  const key = mentionDedupeKey(mention)
  const existing = catalog.get(key)
  if (!existing) {
    catalog.set(key, mention)
    return
  }
  const existingPriority = SOURCE_PRIORITY[existing.source ?? ""] ?? 99
  const nextPriority = SOURCE_PRIORITY[mention.source ?? ""] ?? 99
  if (nextPriority < existingPriority) {
    catalog.set(key, mention)
  }
}

export function buildAttachmentLabelCatalog(
  urlItems: AgentUrlAttachment[],
  files: AgentFile[],
  sessionAssets: ChatAssetRef[] = [],
): Map<string, AgentReferenceMention> {
  const catalog = new Map<string, AgentReferenceMention>()
  let imageIndex = 0

  urlItems.forEach((item) => {
    imageIndex += 1
    const name = item.refLabel || item.name
    const refLabel = item.refLabel || `@图片${imageIndex}-${name}`
    const kind = attachmentKind(item.contentType, item.name)
    const displayLabel = displayLabelForMention(
      { kind, source: item.source === "chat_reference" ? "session_asset" : "current_turn" },
      imageIndex,
      name,
    )
    addCatalogEntry(catalog, {
      token: displayLabel,
      refLabel,
      assetKey: String(item.id ?? item.url),
      fileId: item.id,
      url: item.url,
      kind,
      name: item.name,
      contentType: item.contentType,
      previewUrl: kind === "image" ? resolveAgentFileUrl(item.url) : undefined,
      source: item.source === "chat_reference" ? "session_asset" : "current_turn",
    })
  })

  files.forEach((file) => {
    if (!file.downloadUrl) return
    imageIndex += 1
    const name = file.originalFilename || `图片${imageIndex}`
    const refLabel = `@图片${imageIndex}-${name}`
    const displayLabel = displayLabelForMention({ kind: "image", source: "agent_file" }, imageIndex, name)
    addCatalogEntry(catalog, {
      token: displayLabel,
      refLabel,
      assetKey: `agent_file:${file.id}`,
      fileId: file.id,
      url: file.downloadUrl,
      kind: attachmentKind(file.contentType, file.originalFilename),
      name: file.originalFilename,
      contentType: file.contentType,
      previewUrl: resolveAgentFileUrl(file.downloadUrl),
      source: "agent_file",
    })
  })

  for (const asset of sessionAssets) {
    const match = asset.refLabel.match(/@图片(\d+)/)
    const index = match ? Number(match[1]) : ++imageIndex
    const displayLabel = displayLabelForMention(
      { kind: asset.kind, source: "session_asset" },
      index,
      asset.name,
    )
    addCatalogEntry(catalog, {
      token: displayLabel,
      refLabel: asset.refLabel,
      assetKey: asset.assetKey,
      url: asset.url,
      kind: asset.kind,
      name: asset.name,
      contentType: asset.contentType,
      previewUrl: asset.kind === "image" ? resolveAgentFileUrl(asset.url) : undefined,
      source: "session_asset",
    })
  }

  return catalog
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

    const match = mention.refLabel.match(/@图片(\d+)/)
    const index = match ? Number(match[1]) : options.length + 1
    const displayLabel =
      mention.token.startsWith("@") && mention.token.length <= 16
        ? mention.token
        : displayLabelForMention(mention, index, mention.refLabel)

    options.push({
      dedupeKey,
      displayLabel,
      refLabel: mention.refLabel,
      subtitle: mention.source === "session_asset" ? "会话素材" : "本轮素材",
      previewUrl: mention.kind === "image" ? resolveAgentFileUrl(mention.url) : undefined,
      mention: { ...mention, token: displayLabel },
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
  if (explicitMentions.length > 0) {
    return explicitMentions.filter((mention) => Boolean(mention.url))
  }
  const catalog = buildAttachmentLabelCatalog(urlItems, files, sessionAssets)
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
