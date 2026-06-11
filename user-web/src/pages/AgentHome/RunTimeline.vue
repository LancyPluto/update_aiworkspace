<script setup lang="ts">
import { computed, ref } from "vue"
import { AlertTriangle, Bot, CheckCircle2, ChevronDown, Database, FileText, Hammer, Loader2, Sparkles, Store } from "lucide-vue-next"
import type { AgentRunEvent } from "@/api/types"
import { filterUserFacingRunEvents } from "./runTimelineEvents"

const props = defineProps<{
  events: AgentRunEvent[]
  inlineMode?: boolean
}>()

type TimelineTone = "info" | "success" | "warning" | "error"

const expandedEventIds = ref<Set<number>>(new Set())

const visibleEvents = computed(() => filterUserFacingRunEvents(props.events, props.inlineMode).slice(-14))

function parseEventJson(value?: string | null | Record<string, unknown>) {
  if (value == null || value === "") return {} as Record<string, unknown>
  if (typeof value === "object" && !Array.isArray(value)) return value as Record<string, unknown>
  try {
    const parsed = JSON.parse(String(value)) as unknown
    if (typeof parsed === "string") {
      const nested = JSON.parse(parsed) as unknown
      return typeof nested === "object" && nested !== null && !Array.isArray(nested) ? nested as Record<string, unknown> : {}
    }
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
  } catch {
    return {} as Record<string, unknown>
  }
}

function titleFor(event: AgentRunEvent) {
  const payload = parseEventJson(event.eventJson)
  if (event.eventType === "run.started") return "开始处理请求"
  if (event.eventType === "intent.detected") {
    if (event.eventText === "tool_use") return "已识别为工具调用"
    if (event.eventText === "needs_clarification") return "需要补充更多信息"
    if (event.eventText === "general_chat") return "按通用问答处理"
    if (event.eventText === "unsupported") return "当前请求暂不支持"
    return "已识别请求意图"
  }
  if (event.eventType === "tool.selected") return `已选择工具：${String(payload.toolCode || event.eventText || "工具")}`
  if (event.eventType === "tool.confirmation_required") return `等待确认：${String(payload.toolName || payload.toolCode || event.eventText || "工具")}`
  if (event.eventType === "subagent.started") return `子 Agent 已启动：${String(payload.subagentName || event.eventText || "agent")}`
  if (event.eventType === "subagent.completed") return `子 Agent 已完成：${String(payload.subagentName || event.eventText || "agent")}`
  if (event.eventType === "subagent.failed") return `子 Agent 执行失败：${String(payload.subagentName || event.eventText || "agent")}`
  if (event.eventType === "memory.context_injected") return "已注入工作区记忆"
  if (event.eventType === "memory.context_frozen") return "已冻结工作区记忆快照"
  if (event.eventType === "memory.candidate_created") return "已生成记忆候选"
  if (event.eventType === "memory.saved") return "已保存工作区记忆"
  if (event.eventType === "workspace_file.created") return `已创建产物：${String(payload.filename || event.eventText || "文件")}`
  if (event.eventType === "workspace_file.updated") return `已更新产物：${String(payload.filename || event.eventText || "文件")}`
  if (event.eventType === "workspace_file.read") return "已读取工作区文件"
  if (event.eventType === "tool.started") return `工具执行中：${String(payload.toolCode || event.eventText || "工具")}`
  if (event.eventType === "tool.task_dispatched") return `已下发工具任务：${String(payload.toolCode || event.eventText || "工具")}`
  if (event.eventType === "tool.task_progress") {
    const status = typeof payload.status === "string" ? payload.status : ""
    const toolCode = String(payload.toolCode || event.eventText || "工具")
    if (status === "PROCESSING") return `工具任务执行中：${toolCode}`
    if (status === "QUEUED") return `工具任务排队中：${toolCode}`
    return status ? `工具任务状态：${toolCode}（${status}）` : `工具任务进度：${toolCode}`
  }
  if (event.eventType === "tool.finished") {
    return payload.errorCode ? `工具执行失败：${String(payload.toolCode || event.eventText || "工具")}` : `工具执行完成：${String(payload.toolCode || event.eventText || "工具")}`
  }
  if (event.eventType === "message.completed") return "回复已生成"
  if (event.eventType === "run.completed") return "本次运行已完成"
  if (event.eventType === "run.failed") {
    if (payload.status === "CANCELLED") return "本次运行已取消"
    if (payload.status === "TIMEOUT") return "本次运行已超时"
    return "本次运行失败"
  }
  return event.eventType
}

function detailFor(event: AgentRunEvent) {
  const payload = parseEventJson(event.eventJson)
  if (event.eventType === "memory.retrieved" || event.eventType === "memory.context_frozen") {
    const count = typeof payload.count === "number" ? payload.count : Array.isArray(payload.items) ? payload.items.length : null
    const source = typeof payload.source === "string" ? payload.source : typeof payload.view === "string" ? payload.view : ""
    const itemTitles = Array.isArray(payload.items)
      ? payload.items
          .map((item) => typeof item === "object" && item !== null && "title" in item ? String((item as Record<string, unknown>).title || "") : "")
          .filter(Boolean)
          .slice(0, 3)
      : []
    const prefix = count == null ? "已读取长期记忆" : `已读取 ${count} 条长期记忆`
    const sourceText = source ? `（${source}）` : ""
    const titles = itemTitles.length > 0 ? `：${itemTitles.join("、")}` : ""
    return `${prefix}${sourceText}${titles}`
  }
  if (event.eventType === "intent.detected") {
    if (typeof payload.selectedToolCode === "string") return `候选工具：${payload.selectedToolCode}`
    if (Array.isArray(payload.candidateToolCodes) && payload.candidateToolCodes.length > 0) return `候选工具：${payload.candidateToolCodes.join("、")}`
    if (typeof payload.reason === "string") return payload.reason
  }
  if (event.eventType === "tool.confirmation_required") {
    return typeof payload.description === "string" ? payload.description : "确认后 Agent 会继续执行该工具。"
  }
  if (typeof payload.progressMessage === "string") return payload.progressMessage
  if (typeof payload.errorMessage === "string") return payload.errorMessage
  if (typeof payload.taskDescription === "string") return payload.taskDescription
  if (typeof payload.error === "string") return payload.error
  if (typeof payload.reason === "string") return payload.reason
  if (typeof payload.title === "string") return payload.title
  if (typeof payload.filename === "string") return payload.filename
  if (Array.isArray(payload.filenames) && payload.filenames.length > 0) return payload.filenames.join(", ")
  if (typeof event.eventText === "string") return event.eventText
  return ""
}

function toneFor(event: AgentRunEvent): TimelineTone {
  const payload = parseEventJson(event.eventJson)
  if (event.eventType === "run.failed" && payload.status === "CANCELLED") return "warning"
  if (event.eventType.endsWith(".failed") || event.eventType === "run.failed") return "error"
  if (event.eventType.endsWith(".completed") || event.eventType === "run.completed") return "success"
  if (event.eventType === "memory.saved") return "success"
  if (event.eventType === "tool.finished") return payload.errorCode ? "error" : "success"
  if (
    [
      "run.started",
      "intent.detected",
      "tool.selected",
      "tool.started",
      "tool.task_dispatched",
      "tool.task_progress",
      "workspace_file.read",
      "memory.context_injected",
      "memory.context_frozen",
      "message.completed",
    ].includes(event.eventType)
  ) {
    return "info"
  }
  return "warning"
}

function iconFor(event: AgentRunEvent) {
  if (event.eventType === "run.started") return Loader2
  if (event.eventType === "intent.detected") return Sparkles
  if (event.eventType === "tool.confirmation_required") return Store
  if (event.eventType.startsWith("subagent.")) return Bot
  if (event.eventType.startsWith("tool.")) return Hammer
  if (event.eventType.startsWith("workspace_file.")) return FileText
  if (event.eventType.startsWith("memory.")) return Database
  if (event.eventType === "message.completed") return Bot
  if (event.eventType === "run.failed") return AlertTriangle
  if (event.eventType === "run.completed") return CheckCircle2
  return Loader2
}

function detailJson(event: AgentRunEvent) {
  const payload = parseEventJson(event.eventJson)
  if (Object.keys(payload).length === 0) return ""
  return JSON.stringify(payload, null, 2)
}

function toggleExpanded(eventId: number) {
  const next = new Set(expandedEventIds.value)
  if (next.has(eventId)) next.delete(eventId)
  else next.add(eventId)
  expandedEventIds.value = next
}
</script>

<template>
  <section
    v-if="visibleEvents.length > 0"
    class="run-timeline"
    :class="{ inline: inlineMode }"
    aria-label="Agent run timeline"
  >
    <article v-for="event in visibleEvents" :key="event.id" class="timeline-row" :class="toneFor(event)">
      <div class="timeline-icon">
        <component :is="iconFor(event)" class="h-4 w-4" />
      </div>
      <div class="timeline-body">
        <p class="timeline-title">{{ titleFor(event) }}</p>
        <p v-if="detailFor(event)" class="timeline-detail">{{ detailFor(event) }}</p>
        <button v-if="detailJson(event)" class="detail-toggle" type="button" @click="toggleExpanded(event.id)">
          <ChevronDown class="h-3 w-3" :class="{ open: expandedEventIds.has(event.id) }" />
          详情
        </button>
        <pre v-if="expandedEventIds.has(event.id) && detailJson(event)" class="detail-json">{{ detailJson(event) }}</pre>
      </div>
    </article>
  </section>
</template>

<style scoped>
.run-timeline {
  width: 100%;
  max-height: 200px;
  overflow-y: auto;
  margin: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
}

.run-timeline.inline {
  max-height: 150px;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
}

.timeline-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}

.timeline-row:last-child {
  border-bottom: 0;
}

.timeline-icon {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--secondary);
  color: var(--foreground);
}

.timeline-row.success .timeline-icon {
  background: #ecfdf3;
  color: #027a48;
}

.timeline-row.warning .timeline-icon {
  background: #fffaeb;
  color: #b54708;
}

.timeline-row.info .timeline-icon {
  background: #eef4ff;
  color: #175cd3;
}

.timeline-row.error .timeline-icon {
  background: #fef3f2;
  color: #b42318;
}

.timeline-title {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--foreground);
}

.timeline-detail {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--muted-foreground);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-toggle {
  margin-top: 6px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 0;
  background: transparent;
  color: var(--muted-foreground);
  cursor: pointer;
  font-size: 12px;
  padding: 0;
}

.detail-toggle .open {
  transform: rotate(180deg);
}

.detail-json {
  margin: 8px 0 0;
  max-height: 140px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--secondary);
  color: var(--foreground);
  font-size: 11px;
  line-height: 1.5;
  padding: 8px;
  white-space: pre-wrap;
}

.timeline-title {
  font-size: 12px;
}
.timeline-detail {
  font-size: 11px;
}

.run-timeline::-webkit-scrollbar {
  width: 4px;
}
.run-timeline::-webkit-scrollbar-thumb {
  background: var(--muted-foreground);
  border-radius: 4px;
}
</style>
