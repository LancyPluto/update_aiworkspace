import type { AgentMessage, AgentRunEvent } from "@/api/types"

export type ConversationPhaseType =
  | "session_start"
  | "user_prompt"
  | "media_insert"
  | "tool_run"
  | "generation"
  | "summary"

export interface ConversationPhase {
  id: string
  type: ConversationPhaseType
  label: string
  messageId: number | null
  order: number
}

export interface ScrollNavNode {
  id: string
  messageId: number
  label: string
  thumbnailUrl?: string
  ratio: number
  kind: "user" | "media" | "tool" | "assistant"
}

const MEDIA_RE = /!\[[^\]]*\]\([^)]+\)|\.(png|jpe?g|webp|gif|mp4|webm)(\?|$)/i
const SUMMARY_RE = /总结|综上|总而言之|小结|归纳/i

export function truncateSemanticLabel(text: string, max = 24): string {
  const cleaned = text.replace(/\s+/g, " ").trim()
  if (cleaned.length <= max) return cleaned
  return `${cleaned.slice(0, max)}…`
}

function extractFirstImageUrl(content: string): string | undefined {
  const md = content.match(/!\[[^\]]*\]\(([^)]+)\)/)
  if (md?.[1]) return md[1]
  const url = content.match(/https?:\/\/[^\s)]+\.(png|jpe?g|webp|gif)(\?[^\s)]*)?/i)
  return url?.[0]
}

function parseEventJson(raw?: string | null): Record<string, unknown> {
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw) as unknown
    return parsed && typeof parsed === "object" ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

export function buildConversationPhases(
  messages: AgentMessage[],
  events: AgentRunEvent[] = [],
): ConversationPhase[] {
  const phases: ConversationPhase[] = []
  let order = 0

  if (messages.length > 0) {
    phases.push({
      id: "phase-start",
      type: "session_start",
      label: "对话开始",
      messageId: messages[0].id,
      order: order++,
    })
  }

  for (const message of messages) {
    if (message.role === "USER") {
      phases.push({
        id: `phase-user-${message.id}`,
        type: "user_prompt",
        label: `用户：${truncateSemanticLabel(message.contentText)}`,
        messageId: message.id,
        order: order++,
      })
    }

    if (MEDIA_RE.test(message.contentText)) {
      phases.push({
        id: `phase-media-${message.id}`,
        type: "media_insert",
        label: message.contentText.match(/\.(mp4|webm)/i) ? "插入视频" : "插入图片/媒体",
        messageId: message.id,
        order: order++,
      })
    }

    if (
      message.role === "ASSISTANT" &&
      SUMMARY_RE.test(message.contentText) &&
      message.id === messages[messages.length - 1]?.id
    ) {
      phases.push({
        id: `phase-summary-${message.id}`,
        type: "summary",
        label: "总结",
        messageId: message.id,
        order: order++,
      })
    }
  }

  for (const event of events) {
    const payload = parseEventJson(event.eventJson)
    if (event.eventType.startsWith("tool.")) {
      const toolName =
        (typeof payload.toolName === "string" && payload.toolName) ||
        (typeof payload.toolCode === "string" && payload.toolCode) ||
        event.eventText ||
        "工具"
      const linkedMessage = [...messages].reverse().find((m) => m.runId === event.runId)
      phases.push({
        id: `phase-tool-${event.id}`,
        type: "tool_run",
        label: `调用：${truncateSemanticLabel(String(toolName), 20)}`,
        messageId: linkedMessage?.id ?? null,
        order: order++,
      })
    }
    if (event.eventType === "workspace_file.created") {
      const filename =
        (typeof payload.filename === "string" && payload.filename) ||
        (typeof payload.originalFilename === "string" && payload.originalFilename) ||
        "文件"
      const linkedMessage = [...messages].reverse().find((m) => m.runId === event.runId)
      phases.push({
        id: `phase-gen-${event.id}`,
        type: "generation",
        label: `生成：${truncateSemanticLabel(String(filename), 20)}`,
        messageId: linkedMessage?.id ?? null,
        order: order++,
      })
    }
  }

  return phases
}

export function buildScrollNavNodes(
  messages: AgentMessage[],
  events: AgentRunEvent[] = [],
  scrollHeight = 1,
  messageOffsets: Map<number, number> = new Map(),
): ScrollNavNode[] {
  const raw: Omit<ScrollNavNode, "ratio">[] = []

  for (const message of messages) {
    if (message.role === "USER") {
      raw.push({
        id: `node-user-${message.id}`,
        messageId: message.id,
        label: truncateSemanticLabel(message.contentText),
        kind: "user",
      })
    }

    const thumb = extractFirstImageUrl(message.contentText)
    if (thumb || (message.role === "ASSISTANT" && MEDIA_RE.test(message.contentText))) {
      raw.push({
        id: `node-media-${message.id}`,
        messageId: message.id,
        label: thumb ? "图片/媒体" : "生成内容",
        thumbnailUrl: thumb,
        kind: "media",
      })
    }
  }

  for (const event of events) {
    if (
      event.eventType === "tool.started" ||
      event.eventType === "workspace_file.created"
    ) {
      const payload = parseEventJson(event.eventJson)
      const linked = [...messages].reverse().find((m) => m.runId === event.runId)
      if (!linked) continue
      const label =
        event.eventType === "workspace_file.created"
          ? `生成：${truncateSemanticLabel(String(payload.filename || payload.originalFilename || "文件"), 18)}`
          : `调用：${truncateSemanticLabel(String(payload.toolName || payload.toolCode || "工具"), 18)}`
      raw.push({
        id: `node-event-${event.id}`,
        messageId: linked.id,
        label,
        kind: "tool",
      })
    }
  }

  const merged: Omit<ScrollNavNode, "ratio">[] = []
  for (const node of raw) {
    const offset = messageOffsets.get(node.messageId) ?? 0
    const prev = merged[merged.length - 1]
    if (prev) {
      const prevOffset = messageOffsets.get(prev.messageId) ?? 0
      if (Math.abs(offset - prevOffset) < 24 && prev.messageId === node.messageId) continue
    }
    merged.push(node)
  }

  const height = Math.max(scrollHeight, 1)
  return merged.map((node) => ({
    ...node,
    ratio: Math.min(1, Math.max(0, (messageOffsets.get(node.messageId) ?? 0) / height)),
  }))
}
