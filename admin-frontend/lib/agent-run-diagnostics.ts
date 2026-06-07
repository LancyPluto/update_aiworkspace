import type {
  AdminAgentRunContextSnapshot,
  AdminAgentRunDetail,
  AgentRun,
  AgentRunEvent,
  AgentToolCall,
} from "@/lib/api/types"
import {
  explainRejectionReason,
  explainValidationFailure,
  hasPseudoToolCallInText,
  mergeMessageDeltas,
  objectPayload,
  parseAgentFileRefs,
  textValue,
} from "@/lib/agent-run-utils"

export type DiagnosisSeverity = "success" | "warning" | "error"

export type DiagnosisStep = {
  id: string
  title: string
  plainText: string
  severity: DiagnosisSeverity
  eventId?: number
  technical?: string
}

export type TimelineStep = {
  id: string
  eventId: number
  eventType: string
  title: string
  plainText: string
  severity: DiagnosisSeverity
  createdAt?: string | null
  technical?: string
  payload?: Record<string, unknown>
}

export type RunDiagnosis = {
  summary: string
  severity: DiagnosisSeverity
  steps: DiagnosisStep[]
  suggestions: string[]
  timeline: TimelineStep[]
  listHint: string
}

function findRouterFallback(events: AgentRunEvent[]) {
  return events.find((e) => e.eventType === "router.fallback")
}

function findIntentDetected(events: AgentRunEvent[]) {
  return [...events].reverse().find((e) => e.eventType === "intent.detected")
}

function attachmentErrorFromRun(run: AgentRun, toolCalls: AgentToolCall[]) {
  const msg = (run.errorMessage || "").toLowerCase()
  if (msg.includes("attachment not found")) {
    const match = (run.errorMessage || "").match(/sessionId=(\d+).*fileId=(\d+)/i)
    if (match) {
      return { sessionId: Number(match[1]), fileId: Number(match[2]) }
    }
    for (const call of toolCalls) {
      const refs = parseAgentFileRefs(parseJsonSafe(call.argumentsJson))
      if (refs.length) return refs[refs.length - 1]
    }
  }
  for (const call of toolCalls) {
    const err = (call.errorMessage || "").toLowerCase()
    if (err.includes("attachment not found") || err.includes("参考图")) {
      const refs = parseAgentFileRefs(parseJsonSafe(call.argumentsJson))
      if (refs.length) return refs[refs.length - 1]
    }
  }
  return null
}

function parseJsonSafe(value?: string | null): unknown {
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

function explainEventPlain(event: AgentRunEvent): { title: string; plainText: string; severity: DiagnosisSeverity; technical?: string } {
  const payload = objectPayload(event.eventJson)
  const type = event.eventType

  if (type === "router.started") {
    const min = textValue(payload.minConfidence)
    return {
      title: "开始判断用户想做什么",
      plainText: min ? `系统准备选路由，最低置信度要求 ${(Number(min) * 100).toFixed(0)}%` : "系统开始分析用户请求",
      severity: "success",
    }
  }
  if (type === "router.candidates") {
    const tools = Array.isArray(payload.candidateToolCodes) ? payload.candidateToolCodes.join("、") : ""
    return {
      title: "列出可用工具",
      plainText: tools ? `当前可考虑的工具：${tools}` : "已列出候选工具",
      severity: "success",
    }
  }
  if (type === "router.selected") {
    return {
      title: "路由成功选中工具",
      plainText: `决定用「${textValue(payload.selectedToolCode) || "工具"}」处理，意图：${textValue(payload.intent)}`,
      severity: "success",
    }
  }
  if (type === "router.fallback") {
    const vf = textValue(payload.validationFailure)
    const parsed = payload.parsed && typeof payload.parsed === "object" ? (payload.parsed as Record<string, unknown>) : null
    const parsedIntent = parsed ? textValue(parsed.intent) : ""
    let plain = "没能按 AI 路由结果执行，改走备用方案"
    if (vf === "invalid_intent" && parsedIntent) {
      plain = `AI 返回了「${parsedIntent}」，但系统只认固定几种意图，所以结果被丢弃`
    } else if (vf) {
      plain = explainValidationFailure(vf)
    } else if (textValue(payload.reason) === "invalid_or_low_confidence") {
      plain = "路由 AI 的输出没通过校验，系统改用普通聊天"
    }
    return {
      title: "路由回退",
      plainText: plain,
      severity: "warning",
      technical: vf || textValue(payload.reason),
    }
  }
  if (type === "intent.detected") {
    const intent = textValue(payload.intent)
    const tool = textValue(payload.selectedToolCode)
    const reason = textValue(payload.reason)
    let plain = `最终按「${intent || "未知"}」处理`
    if (reason === "router_fallback_general_chat") {
      plain = "没能自动选工具，改走普通聊天模式"
    } else if (tool) {
      plain = `决定用工具「${tool}」`
    }
    return {
      title: "意图决策",
      plainText: plain,
      severity: intent === "general_chat" && !tool ? "warning" : "success",
      technical: reason,
    }
  }
  if (type === "tool_call.requested") {
    return {
      title: "模型想调用工具",
      plainText: `模型请求调用「${textValue(payload.name)}」`,
      severity: "success",
    }
  }
  if (type === "tool_call.rejected") {
    const reason = textValue(payload.reason)
    return {
      title: "工具调用被拒绝",
      plainText: explainRejectionReason(reason, textValue(payload.name)),
      severity: "error",
      technical: reason,
    }
  }
  if (type === "tool_call.executed") {
    return {
      title: "工具已执行",
      plainText: `「${textValue(payload.name)}」执行完成`,
      severity: "success",
    }
  }
  if (type === "tool.task_dispatched") {
    return {
      title: "任务已下发",
      plainText: `工具任务 #${textValue(payload.taskId)} 已创建`,
      severity: "success",
    }
  }
  if (type === "memory.retrieved") {
    return {
      title: "读取长期记忆",
      plainText: `检索到 ${textValue(payload.count) || "若干"} 条记忆，用于辅助回答`,
      severity: "success",
    }
  }
  if (type === "message.completed") {
    const content = textValue(payload.content) || event.eventText || ""
    if (hasPseudoToolCallInText(content)) {
      return {
        title: "模型回复（含伪工具调用）",
        plainText: "模型只在文字里写了工具调用，系统没有真正执行",
        severity: "error",
      }
    }
    return {
      title: "模型回复完成",
      plainText: content.length > 120 ? `${content.slice(0, 120)}...` : content || "回复已生成",
      severity: "success",
    }
  }
  if (type === "run.failed") {
    return {
      title: "运行失败",
      plainText: textValue(payload.errorMessage) || event.eventText || "运行失败",
      severity: "error",
      technical: textValue(payload.errorCode),
    }
  }
  if (type === "reasoning.completed") {
    const content = textValue(payload.content) || event.eventText || ""
    return {
      title: "思考过程",
      plainText: content.length > 100 ? `${content.slice(0, 100)}...` : content || "已记录思考过程",
      severity: "success",
    }
  }

  return {
    title: type,
    plainText: event.eventText || textValue(payload.reason) || "",
    severity: type.includes("failed") || type.includes("rejected") ? "error" : "success",
  }
}

function buildSuggestions(
  run: AgentRun,
  events: AgentRunEvent[],
  toolCalls: AgentToolCall[],
  contextSnapshot?: AdminAgentRunContextSnapshot | null,
): string[] {
  const suggestions: string[] = []
  const fallback = findRouterFallback(events)
  const fallbackPayload = fallback ? objectPayload(fallback.eventJson) : {}
  const vf = textValue(fallbackPayload.validationFailure)

  if (vf === "invalid_intent") {
    suggestions.push("检查路由 Prompt 是否要求 AI 只返回合法 intent（如 tool_use），或升级 agent-service 的 intent 别名映射")
  }
  if (events.some((e) => e.eventType === "tool_call.rejected" && objectPayload(e.eventJson).reason === "tool_not_allowed")) {
    suggestions.push("用户明显要生图时，应确保路由到 tool_use 而非 general_chat；检查 router.fallback 原因")
  }

  const attachment = attachmentErrorFromRun(run, toolCalls)
  if (attachment) {
    suggestions.push(
      `参考图 session#${attachment.sessionId} file#${attachment.fileId} 未纳入本次运行上下文，请让用户在「素材」中勾选后确认再发送`,
    )
    if (contextSnapshot?.agentFiles?.length) {
      const available = contextSnapshot.agentFiles.map((f) => `#${f.id} ${f.originalFilename || ""}`).join("、")
      suggestions.push(`当前会话可用附件：${available}`)
    } else {
      suggestions.push("在排查页「上下文」查看当前 session 还有哪些可用附件")
    }
  }

  const answer = mergeMessageDeltas(events)
  if (hasPseudoToolCallInText(answer)) {
    suggestions.push("模型输出了伪工具调用文本，需修复路由或启用 product tool loop 让工具真正执行")
  }

  if (suggestions.length === 0 && run.errorMessage) {
    suggestions.push("展开下方时间线查看失败前的每一步，对照技术详情定位")
  }
  if (suggestions.length === 0 && run.status?.toUpperCase() === "COMPLETED") {
    suggestions.push("本次运行正常完成，可查看时间线了解决策过程")
  }

  return suggestions.slice(0, 4)
}

function buildSteps(
  run: AgentRun,
  events: AgentRunEvent[],
  toolCalls: AgentToolCall[],
): DiagnosisStep[] {
  const steps: DiagnosisStep[] = []
  const fallback = findRouterFallback(events)
  if (fallback) {
    const p = objectPayload(fallback.eventJson)
    const parsed = p.parsed && typeof p.parsed === "object" ? (p.parsed as Record<string, unknown>) : null
    steps.push({
      id: "router-fallback",
      title: "路由没通过",
      plainText: explainEventPlain(fallback).plainText,
      severity: "warning",
      eventId: fallback.id,
      technical: textValue(p.validationFailure) || textValue(p.reason),
    })
    if (parsed && textValue(parsed.intent)) {
      steps.push({
        id: "parsed-intent",
        title: "AI 原本想做什么",
        plainText: `AI 认为意图是「${textValue(parsed.intent)}」，工具「${textValue(parsed.selectedToolCode)}」，置信度 ${textValue(parsed.confidence)}`,
        severity: "warning",
        technical: JSON.stringify(parsed),
      })
    }
  }

  const intent = findIntentDetected(events)
  if (intent) {
    const plain = explainEventPlain(intent)
    steps.push({
      id: "intent",
      title: "系统最终决定",
      plainText: plain.plainText,
      severity: plain.severity,
      eventId: intent.id,
      technical: plain.technical,
    })
  }

  const rejections = events.filter((e) => e.eventType === "tool_call.rejected")
  for (const rej of rejections) {
    const plain = explainEventPlain(rej)
    steps.push({
      id: `reject-${rej.id}`,
      title: "工具被拒绝",
      plainText: plain.plainText,
      severity: "error",
      eventId: rej.id,
      technical: plain.technical,
    })
  }

  const failedCall = toolCalls.find((c) => c.errorMessage)
  if (failedCall) {
    steps.push({
      id: `tool-${failedCall.id}`,
      title: "工具任务失败",
      plainText: failedCall.errorMessage || "工具执行失败",
      severity: "error",
      technical: failedCall.errorCode || undefined,
    })
  }

  if (run.errorMessage && !failedCall) {
    steps.push({
      id: "run-error",
      title: "运行报错",
      plainText: run.errorMessage,
      severity: "error",
      technical: run.errorCode || undefined,
    })
  }

  const answer = mergeMessageDeltas(events)
  if (hasPseudoToolCallInText(answer)) {
    steps.push({
      id: "pseudo-tool",
      title: "伪工具调用",
      plainText: "模型在回复正文里写了工具调用代码，但没有真正执行",
      severity: "error",
    })
  }

  return steps
}

function buildSummary(run: AgentRun, steps: DiagnosisStep[]): { summary: string; severity: DiagnosisSeverity; listHint: string } {
  const status = (run.status || "").toUpperCase()
  if (status === "FAILED" || steps.some((s) => s.severity === "error")) {
    const errStep = steps.find((s) => s.severity === "error")
    const msg = (run.errorMessage || "").toLowerCase()
    if (msg.includes("attachment not found")) {
      const match = (run.errorMessage || "").match(/fileId=(\d+)/i)
      const hint = match ? `附件找不到 #${match[1]}` : "参考图附件找不到"
      return {
        summary: `参考图在数据库里不存在，不是对话记忆丢失。${errStep?.plainText || run.errorMessage || ""}`,
        severity: "error",
        listHint: hint,
      }
    }
    if (steps.some((s) => s.id.startsWith("reject-"))) {
      return {
        summary: errStep?.plainText || run.errorMessage || "工具调用被拒绝",
        severity: "error",
        listHint: "工具被拒绝",
      }
    }
    return {
      summary: run.errorMessage || errStep?.plainText || "运行失败",
      severity: "error",
      listHint: run.errorCode || "运行失败",
    }
  }
  if (steps.some((s) => s.severity === "warning")) {
    const warn = steps.find((s) => s.severity === "warning")
    return {
      summary: warn?.plainText || "路由曾回退，但最终完成了",
      severity: "warning",
      listHint: "路由曾回退",
    }
  }
  return {
    summary: status === "COMPLETED" ? "运行正常完成" : `状态：${run.status}`,
    severity: "success",
    listHint: "正常",
  }
}

const TIMELINE_SKIP = new Set(["message.delta", "reasoning.delta", "runtime_settings.applied"])

export function diagnoseAgentRun(
  detail: Pick<AdminAgentRunDetail, "run" | "events" | "toolCalls"> & {
    contextSnapshot?: AdminAgentRunContextSnapshot | null
  },
): RunDiagnosis {
  const { run, events, toolCalls, contextSnapshot } = detail
  const steps = buildSteps(run, events, toolCalls)
  const { summary, severity, listHint } = buildSummary(run, steps)
  const suggestions = buildSuggestions(run, events, toolCalls, contextSnapshot)

  const timeline: TimelineStep[] = events
    .filter((e) => !TIMELINE_SKIP.has(e.eventType))
    .map((event) => {
      const plain = explainEventPlain(event)
      return {
        id: `evt-${event.id}`,
        eventId: event.id,
        eventType: event.eventType,
        title: plain.title,
        plainText: plain.plainText,
        severity: plain.severity,
        createdAt: event.createdAt,
        technical: plain.technical,
        payload: objectPayload(event.eventJson),
      }
    })

  return { summary, severity, steps, suggestions, timeline, listHint }
}

export function diagnoseRunListItem(
  run: Pick<AgentRun, "status" | "errorCode" | "errorMessage">,
  events?: AgentRunEvent[],
): string {
  if (run.errorMessage?.toLowerCase().includes("attachment not found")) {
    const match = run.errorMessage.match(/fileId=(\d+)/i)
    return match ? `参考图找不到 #${match[1]}` : "参考图附件找不到"
  }
  if (events?.some((e) => e.eventType === "tool_call.rejected")) return "工具调用被拒绝"
  if (run.errorMessage) return run.errorMessage.length > 40 ? `${run.errorMessage.slice(0, 40)}...` : run.errorMessage
  if (run.status?.toUpperCase() === "COMPLETED") return "正常完成"
  return run.status || "-"
}
