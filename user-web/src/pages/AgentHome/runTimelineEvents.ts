import type { AgentRunEvent } from "@/api/types"

/** 仅面向用户的进度（意图识别、记忆快照、run 起止等内部步骤不展示） */
export const USER_FACING_EVENT_TYPES = [
  "tool.confirmation_required",
  "subagent.started",
  "subagent.completed",
  "subagent.failed",
  "workspace_file.created",
  "workspace_file.updated",
  "tool.started",
  "tool.task_dispatched",
  "tool.task_progress",
  "tool.finished",
  "message.completed",
  "run.completed",
  "run.failed",
] as const

/** 聊天页内联时间线：工具确认有独立卡片，时间线里不再重复 */
export const INLINE_VISIBLE_EVENT_TYPES = USER_FACING_EVENT_TYPES.filter(
  (type) => type !== "tool.confirmation_required",
)

export function filterUserFacingRunEvents(events: AgentRunEvent[], inlineMode = false) {
  const allowed = new Set<string>(inlineMode ? INLINE_VISIBLE_EVENT_TYPES : USER_FACING_EVENT_TYPES)
  return events.filter((event) => allowed.has(event.eventType))
}

export function filterToolProcessEvents(events: AgentRunEvent[]) {
  return events.filter((event) =>
    event.eventType === "tool.started" ||
    event.eventType === "tool.task_dispatched" ||
    event.eventType === "tool.task_progress" ||
    event.eventType === "tool.finished",
  )
}
