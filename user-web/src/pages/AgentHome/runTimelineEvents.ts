import type { AgentRunEvent } from "@/api/types"

/** 面向用户的进度；记忆事件保留可见，方便确认工具调用实际使用了哪些长期记忆。 */
export const USER_FACING_EVENT_TYPES = [
  "memory.retrieved",
  "memory.context_frozen",
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
