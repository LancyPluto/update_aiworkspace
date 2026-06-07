import type { AgentRunEvent, AgentToolCall } from "@/lib/api/types"

export function formatDateTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString("zh-CN", { hour12: false })
}

export function parseJsonValue(value?: string | null): unknown {
  if (!value) return null
  try {
    const parsed = JSON.parse(value)
    if (typeof parsed === "string") return JSON.parse(parsed)
    return parsed
  } catch {
    return value
  }
}

export function prettyJson(value?: string | null) {
  const parsed = parseJsonValue(value)
  if (parsed == null || parsed === "") return ""
  return typeof parsed === "string" ? parsed : JSON.stringify(parsed, null, 2)
}

export function objectPayload(value?: string | null): Record<string, unknown> {
  const parsed = parseJsonValue(value)
  return parsed && typeof parsed === "object" && !Array.isArray(parsed)
    ? (parsed as Record<string, unknown>)
    : {}
}

export function textValue(value: unknown) {
  return typeof value === "string" || typeof value === "number" ? String(value) : ""
}

export function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : null
}

export function objectList(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) return []
  return value.filter(
    (item): item is Record<string, unknown> =>
      Boolean(item) && typeof item === "object" && !Array.isArray(item),
  )
}

export function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.map((item) => textValue(item)).filter(Boolean)
}

export function percentValue(value: unknown) {
  const number = numberValue(value)
  return number == null ? "" : `${(number * 100).toFixed(0)}%`
}

export function compactJson(value: unknown, limit = 320) {
  if (value == null || value === "") return ""
  const text = typeof value === "string" ? value : JSON.stringify(value, null, 2)
  return text.length > limit ? `${text.slice(0, limit)}...` : text
}

export function statusVariant(status: string): "default" | "secondary" | "destructive" | "outline" {
  const upper = status.toUpperCase()
  if (upper === "SUCCEEDED" || upper === "SUCCESS" || upper === "COMPLETED") return "default"
  if (upper === "FAILED" || upper === "CANCELLED" || upper === "TIMEOUT") return "destructive"
  if (upper === "RUNNING" || upper === "PROCESSING" || upper === "PENDING" || upper === "QUEUED") return "secondary"
  return "outline"
}

export function runDuration(startedAt?: string | null, finishedAt?: string | null) {
  if (!startedAt) return "-"
  const start = new Date(startedAt).getTime()
  const end = finishedAt ? new Date(finishedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return "-"
  const seconds = Math.round((end - start) / 1000)
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

export const EVENT_LABELS: Record<string, string> = {
  "run.started": "开始处理",
  "run.completed": "运行完成",
  "run.failed": "运行失败",
  "runtime_settings.applied": "运行参数已应用",
  "router.started": "路由开始",
  "router.candidates": "候选工具",
  "router.selected": "路由选中",
  "router.fallback": "路由回退",
  "intent.detected": "意图决策",
  "followup.detected": "识别为续写",
  "followup.inherited": "继承上轮参数",
  "followup.rejected": "拒绝继承参数",
  "memory.retrieved": "检索长期记忆",
  "memory.context_frozen": "冻结记忆快照",
  "memory.curator_started": "记忆整理开始",
  "memory.consolidated": "记忆已整理",
  "memory.candidate_created": "生成记忆候选",
  "memory.saved": "保存长期记忆",
  "memory.rejected": "拒绝写入记忆",
  "tool_call.loop_started": "工具调用循环开始",
  "tool_call.requested": "模型请求工具",
  "tool_call.executed": "工具已执行",
  "tool_call.rejected": "工具调用被拒绝",
  "tool_call.loop_completed": "工具调用循环完成",
  "tool.selected": "选择工具",
  "tool.arguments_preview": "工具参数预览",
  "arguments.merged": "参数合并",
  "tool.started": "工具开始",
  "tool.task_dispatched": "任务下发",
  "tool.task_progress": "任务进度",
  "tool.finished": "工具完成",
  "message.delta": "流式回复片段",
  "message.completed": "回复完成",
  "reasoning.delta": "思考过程片段",
  "reasoning.completed": "思考过程完成",
}

export function eventLabel(type: string) {
  return EVENT_LABELS[type] || type
}

export function eventTone(event: AgentRunEvent) {
  const payload = objectPayload(event.eventJson)
  if (event.eventType === "run.failed" || event.eventType.endsWith(".failed") || payload.errorCode) {
    return "destructive" as const
  }
  if (event.eventType === "run.completed" || event.eventType.endsWith(".completed") || payload.status === "SUCCESS") {
    return "default" as const
  }
  if (event.eventType.includes("tool")) return "secondary" as const
  return "outline" as const
}

export const VALID_INTENTS = [
  "general_chat",
  "tool_use",
  "needs_clarification",
  "unsupported",
  "rag",
  "file_analysis",
  "workflow",
  "security_rejected",
] as const

export const VALIDATION_FAILURE_LABELS: Record<string, string> = {
  parsed_not_object: "路由 AI 返回的不是合法 JSON 对象",
  invalid_intent: "路由 AI 返回了系统不认识的意图类型",
  "confidence_below_min": "置信度低于最低阈值",
  tool_use_missing_selected_tool: "选了「用工具」但没指定具体工具",
  tool_not_available: "指定的工具当前不可用",
  output_modality_mismatch: "工具输出类型与用户要求不匹配",
}

export function explainValidationFailure(code: string) {
  if (code.startsWith("confidence_below_min:")) {
    return "路由 AI 很有把握，但分数仍低于系统设置的最低线，结果被丢弃"
  }
  if (code.startsWith("tool_not_available:")) {
    const tool = code.split(":").slice(1).join(":")
    return `路由 AI 想用的工具「${tool}」当前对用户不可用`
  }
  if (code.startsWith("output_modality_mismatch:")) {
    return "用户要的是图/视频/音频，但路由选的工具类型对不上"
  }
  return VALIDATION_FAILURE_LABELS[code] || code
}

export function explainRejectionReason(reason: string, toolName?: string) {
  const map: Record<string, string> = {
    tool_not_allowed: toolName
      ? `当前是「普通聊天」模式，不能调用「${toolName}」（只有记忆工具可用）`
      : "当前模式下不允许调用这个工具",
    tool_not_available: "模型叫了一个系统里没有登记的工具名",
    output_modality_mismatch: "工具输出类型与用户要求不匹配",
    execution_error: "工具执行时抛出了异常",
    attachment_not_found: "参数里的参考图在数据库里找不到（不是记忆丢失）",
  }
  return map[reason] || reason
}

const AGENT_FILE_PATH =
  /\/api\/v1\/agent\/sessions\/(\d+)\/files\/(\d+)\/content/i

export function parseAgentFileRefs(value: unknown): Array<{ sessionId: number; fileId: number; raw: string }> {
  const refs: Array<{ sessionId: number; fileId: number; raw: string }> = []
  const seen = new Set<string>()

  function walk(node: unknown) {
    if (typeof node === "string") {
      const match = node.match(AGENT_FILE_PATH)
      if (match) {
        const key = `${match[1]}:${match[2]}`
        if (!seen.has(key)) {
          seen.add(key)
          refs.push({ sessionId: Number(match[1]), fileId: Number(match[2]), raw: node })
        }
      }
      return
    }
    if (Array.isArray(node)) {
      node.forEach(walk)
      return
    }
    if (node && typeof node === "object") {
      Object.values(node).forEach(walk)
    }
  }

  walk(value)
  return refs
}

export function hasPseudoToolCallInText(text: string) {
  const lowered = text.toLowerCase()
  return (
    lowered.includes("dsml") ||
    lowered.includes("tool_calls") ||
    lowered.includes("<｜｜dsml｜｜") ||
    lowered.includes("invoke name=")
  )
}

export function mergeMessageDeltas(events: AgentRunEvent[]) {
  const deltas = events
    .filter((e) => e.eventType === "message.delta")
    .map((e) => textValue(objectPayload(e.eventJson).delta) || e.eventText || "")
    .join("")
  const completed = [...events]
    .reverse()
    .find((e) => e.eventType === "message.completed")
  const completedText =
    textValue(objectPayload(completed?.eventJson).content) || completed?.eventText || ""
  return completedText || deltas
}

export function mergeReasoningDeltas(events: AgentRunEvent[]) {
  const deltas = events
    .filter((e) => e.eventType === "reasoning.delta")
    .map((e) => textValue(objectPayload(e.eventJson).delta) || e.eventText || "")
    .join("")
  const completed = [...events]
    .reverse()
    .find((e) => e.eventType === "reasoning.completed")
  const completedText =
    textValue(objectPayload(completed?.eventJson).content) || completed?.eventText || ""
  return completedText || deltas
}

export function taskIdFromCall(call: AgentToolCall) {
  if (call.taskId != null) return String(call.taskId)
  const payload = objectPayload(call.resultJson)
  return textValue(payload.taskId || payload.task_id)
}
