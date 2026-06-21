import type { AgentReferenceMention } from "@/utils/agentReferenceMentions"

export type MessageMentionSegment =
  | { type: "text"; value: string }
  | { type: "mention"; displayLabel: string; mention: AgentReferenceMention }

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

function isReferenceMention(value: unknown): value is AgentReferenceMention {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false
  const item = value as Record<string, unknown>
  return typeof item.url === "string" && Boolean(item.token || item.refLabel)
}

export function parseStoredReferenceMentions(contentJson?: string | null): AgentReferenceMention[] {
  const payload = parseMessageJson(contentJson)
  const raw = payload.referenceMentions
  if (!Array.isArray(raw)) return []
  const mentions: AgentReferenceMention[] = []
  const seen = new Set<string>()
  for (const item of raw) {
    if (!isReferenceMention(item)) continue
    const key = item.assetKey || item.url
    if (seen.has(key)) continue
    seen.add(key)
    mentions.push({
      token: String(item.token || item.refLabel || ""),
      refLabel: String(item.refLabel || item.token || ""),
      assetKey: item.assetKey,
      fileId: item.fileId,
      url: item.url,
      kind: item.kind,
      name: item.name,
      contentType: item.contentType,
      previewUrl: item.previewUrl,
      source: item.source,
    })
  }
  return mentions
}

export function segmentMessageWithMentions(
  text: string,
  mentions: AgentReferenceMention[],
): MessageMentionSegment[] {
  if (!text || mentions.length === 0) {
    return text ? [{ type: "text", value: text }] : []
  }

  const tokens = mentions
    .map((mention) => ({
      mention,
      displayLabel: (mention.token || mention.refLabel || "").trim(),
    }))
    .filter((item) => item.displayLabel.startsWith("@"))
    .sort((a, b) => b.displayLabel.length - a.displayLabel.length)

  const segments: MessageMentionSegment[] = []
  let cursor = 0
  while (cursor < text.length) {
    let matched: { index: number; displayLabel: string; mention: AgentReferenceMention } | null = null
    for (const item of tokens) {
      const index = text.indexOf(item.displayLabel, cursor)
      if (index < 0) continue
      if (!matched || index < matched.index) {
        matched = { index, displayLabel: item.displayLabel, mention: item.mention }
      }
    }
    if (!matched) {
      segments.push({ type: "text", value: text.slice(cursor) })
      break
    }
    if (matched.index > cursor) {
      segments.push({ type: "text", value: text.slice(cursor, matched.index) })
    }
    segments.push({
      type: "mention",
      displayLabel: matched.displayLabel,
      mention: matched.mention,
    })
    cursor = matched.index + matched.displayLabel.length
  }
  return segments
}
