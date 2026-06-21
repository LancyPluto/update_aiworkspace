<script setup lang="ts">
import { computed, ref } from "vue"
import { AlertTriangle, Bot, CheckCircle2, ChevronDown, Database, FileText, Hammer, Loader2, Sparkles, Store } from "lucide-vue-next"
import type { AgentRunEvent } from "@/api/types"
import { filterUserFacingRunEvents } from "./runTimelineEvents"

const props = defineProps<{
  events: AgentRunEvent[]
  inlineMode?: boolean
  processMode?: boolean
}>()

const emit = defineEmits<{
  "open-memory": [memoryId: number]
  "delete-memory": [memoryId: number]
}>()

type TimelineTone = "info" | "success" | "warning" | "error"

interface MemoryTraceItem {
  id?: number
  type?: string
  title?: string
  preview?: string
  reason?: string
}

const TOOL_PROCESS_EVENT_TYPES = new Set([
  "tool.started",
  "tool.task_dispatched",
  "tool.task_progress",
  "tool.finished",
])

const expandedEventIds = ref<Set<number>>(new Set())
const toolTimelineExpanded = ref(false)

const visibleEvents = computed(() => filterUserFacingRunEvents(props.events, props.inlineMode).slice(-40))

const toolProcessEvents = computed(() => visibleEvents.value.filter((event) => TOOL_PROCESS_EVENT_TYPES.has(event.eventType)))

const nonToolEvents = computed(() => visibleEvents.value.filter((event) => !TOOL_PROCESS_EVENT_TYPES.has(event.eventType)))

const latestToolProcessEvent = computed(() => toolProcessEvents.value[toolProcessEvents.value.length - 1] ?? null)

const shouldCollapseToolProcess = computed(
  () =>
    (props.processMode || props.inlineMode) &&
    toolProcessEvents.value.length > 1 &&
    !toolTimelineExpanded.value,
)

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

function memoryTraceItems(event: AgentRunEvent): MemoryTraceItem[] {
  const payload = parseEventJson(event.eventJson)
  if (!Array.isArray(payload.items)) return []
  return payload.items
    .map((item) => (typeof item === "object" && item !== null ? item as MemoryTraceItem : null))
    .filter((item): item is MemoryTraceItem => item != null)
}

function memoryReasonLabel(reason?: string) {
  const value = (reason || "").trim()
  if (!value) return "相关检索"
  const labels: Record<string, string> = {
    safe_pack: "安全上下文",
    explicit: "显式引用",
    context_pack: "上下文包",
    relevance: "相关检索",
    skipped: "已跳过注入",
  }
  return labels[value] || value
}

function memoryTypeLabel(type?: string) {
  const labels: Record<string, string> = {
    user_profile: "用户画像",
    preference: "稳定偏好",
    workspace_fact: "项目知识",
    tool_lesson: "工具经验",
    workflow_recipe: "流程配方",
    custom: "自定义",
  }
  return labels[type || ""] || type || "记忆"
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
  if (event.eventType === "agent.step") {
    const iteration = typeof payload.iteration === "number" ? payload.iteration : null
    return iteration ? `Agent 思考第 ${iteration} 步` : "Agent 正在思考"
  }
  if (event.eventType === "plan.updated") {
    const steps = Array.isArray(payload.steps) ? payload.steps.length : 0
    return steps ? `已更新执行计划（${steps} 步）` : "已更新执行计划"
  }
  if (event.eventType === "reflect.retry") {
    return `工具失败，正在反思重试：${String(payload.toolCode || event.eventText || "工具")}`
  }
  if (event.eventType === "tool.selected") return `已选择工具：${String(payload.toolCode || event.eventText || "工具")}`
  if (event.eventType === "tool.confirmation_required") return `等待确认：${String(payload.toolName || payload.toolCode || event.eventText || "工具")}`
  if (event.eventType === "subagent.started") return `子 Agent 已启动：${String(payload.subagentName || event.eventText || "agent")}`
  if (event.eventType === "subagent.completed") return `子 Agent 已完成：${String(payload.subagentName || event.eventText || "agent")}`
  if (event.eventType === "subagent.failed") return `子 Agent 执行失败：${String(payload.subagentName || event.eventText || "agent")}`
  if (event.eventType === "memory.context_injected") return "已注入工作区记忆"
  if (event.eventType === "memory.context_frozen") return "已冻结工作区记忆快照"
  if (event.eventType === "memory.retrieved") {
    if (payload.memoryInjectionSkipped === true) return "已跳过工具记忆注入"
    const count = typeof payload.count === "number" ? payload.count : Array.isArray(payload.items) ? payload.items.length : 0
    return count > 0 ? `已读取 ${count} 条长期记忆` : "未读取长期记忆"
  }
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
    if (payload.memoryInjectionSkipped === true) {
      const promptMode = typeof payload.promptMode === "string" ? payload.promptMode : ""
      return promptMode ? `参考图编辑模式（${promptMode}），未注入 workspace 项目记忆。` : "参考图编辑模式，未注入 workspace 项目记忆。"
    }
    const policy = typeof payload.policy === "string" ? payload.policy : ""
    const view = typeof payload.view === "string" ? payload.view : ""
    const parts = [policy, view].filter(Boolean)
    return parts.length > 0 ? `策略：${parts.join(" / ")}` : ""
  }
  if (event.eventType === "intent.detected") {
    if (typeof payload.selectedToolCode === "string") return `候选工具：${payload.selectedToolCode}`
    if (Array.isArray(payload.candidateToolCodes) && payload.candidateToolCodes.length > 0) return `候选工具：${payload.candidateToolCodes.join("、")}`
    if (typeof payload.reason === "string") return payload.reason
  }
  if (event.eventType === "plan.updated" && Array.isArray(payload.steps)) {
    const marks: Record<string, string> = { done: "✓", in_progress: "▶", pending: "•" }
    return payload.steps
      .map((step) => {
        const obj = (typeof step === "object" && step !== null ? step : {}) as Record<string, unknown>
        const status = String(obj.status || "pending")
        return `${marks[status] || "•"} ${String(obj.title || "")}`
      })
      .filter(Boolean)
      .join("\n")
  }
  if (event.eventType === "reflect.retry") {
    return typeof payload.error === "string" ? payload.error : "工具执行失败，正在调整参数后重试。"
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
  if (event.eventType === "reflect.retry") return "warning"
  if (event.eventType === "plan.updated") return "info"
  if (event.eventType === "agent.step") return "info"
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
      "memory.retrieved",
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
  if (event.eventType === "agent.step") return Sparkles
  if (event.eventType === "plan.updated") return CheckCircle2
  if (event.eventType === "reflect.retry") return AlertTriangle
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

function toggleToolTimelineExpanded() {
  toolTimelineExpanded.value = !toolTimelineExpanded.value
}

function isEventExpanded(eventId: number) {
  return expandedEventIds.value.has(eventId)
}

function hasMemoryTrace(event: AgentRunEvent) {
  return memoryTraceItems(event).length > 0
}
</script>

<template>
  <section
    v-if="visibleEvents.length > 0"
    class="run-timeline"
    :class="{ inline: inlineMode }"
    aria-label="Agent run timeline"
  >
    <article v-for="event in nonToolEvents" :key="event.id" class="timeline-row" :class="toneFor(event)">
      <div class="timeline-icon">
        <component :is="iconFor(event)" class="h-4 w-4" />
      </div>
      <div class="timeline-body">
        <p class="timeline-title">{{ titleFor(event) }}</p>
        <p v-if="detailFor(event) && isEventExpanded(event.id)" class="timeline-detail">{{ detailFor(event) }}</p>
        <ul v-if="isEventExpanded(event.id) && hasMemoryTrace(event)" class="memory-trace-list">
          <li v-for="item in memoryTraceItems(event)" :key="`${event.id}-${item.id ?? item.title}`" class="memory-trace-item">
            <div class="memory-trace-main">
              <strong>#{{ item.id ?? "?" }} · {{ item.title || "未命名记忆" }}</strong>
              <span>{{ memoryTypeLabel(item.type) }} · {{ memoryReasonLabel(item.reason) }}</span>
              <p v-if="item.preview">{{ item.preview }}</p>
            </div>
            <div v-if="item.id" class="memory-trace-actions">
              <button type="button" class="memory-trace-btn" @click="emit('open-memory', item.id!)">在记忆中查看</button>
              <button type="button" class="memory-trace-btn danger" @click="emit('delete-memory', item.id!)">删除此记忆</button>
            </div>
          </li>
        </ul>
        <button
          v-if="detailFor(event) || detailJson(event) || hasMemoryTrace(event)"
          class="detail-toggle"
          type="button"
          @click="toggleExpanded(event.id)"
        >
          <ChevronDown class="h-3 w-3" :class="{ open: isEventExpanded(event.id) }" />
          {{ isEventExpanded(event.id) ? "收起" : "详情" }}
        </button>
        <pre v-if="isEventExpanded(event.id) && detailJson(event)" class="detail-json">{{ detailJson(event) }}</pre>
      </div>
    </article>

    <article
      v-if="shouldCollapseToolProcess && latestToolProcessEvent"
      :key="`tool-summary-${latestToolProcessEvent.id}`"
      class="timeline-row"
      :class="toneFor(latestToolProcessEvent)"
    >
      <div class="timeline-icon">
        <component :is="iconFor(latestToolProcessEvent)" class="h-4 w-4" />
      </div>
      <div class="timeline-body">
        <p class="timeline-title">{{ titleFor(latestToolProcessEvent) }}</p>
        <button class="detail-toggle" type="button" @click="toggleToolTimelineExpanded">
          <ChevronDown class="h-3 w-3" />
          详情（{{ toolProcessEvents.length }}）
        </button>
      </div>
    </article>

    <template v-else-if="toolProcessEvents.length > 0">
      <article v-for="event in toolProcessEvents" :key="event.id" class="timeline-row" :class="toneFor(event)">
        <div class="timeline-icon">
          <component :is="iconFor(event)" class="h-4 w-4" />
        </div>
        <div class="timeline-body">
          <p class="timeline-title">{{ titleFor(event) }}</p>
          <p v-if="detailFor(event) && isEventExpanded(event.id)" class="timeline-detail">{{ detailFor(event) }}</p>
          <button
            v-if="detailFor(event) || detailJson(event)"
            class="detail-toggle"
            type="button"
            @click="toggleExpanded(event.id)"
          >
            <ChevronDown class="h-3 w-3" :class="{ open: isEventExpanded(event.id) }" />
            {{ isEventExpanded(event.id) ? "收起" : "详情" }}
          </button>
          <pre v-if="isEventExpanded(event.id) && detailJson(event)" class="detail-json">{{ detailJson(event) }}</pre>
        </div>
      </article>
      <div v-if="toolProcessEvents.length > 1" class="timeline-collapse-row">
        <button class="detail-toggle" type="button" @click="toggleToolTimelineExpanded">
          <ChevronDown class="h-3 w-3 open" />
          收起步骤
        </button>
      </div>
    </template>
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

.timeline-row.error .timeline-icon {
  background: #fef3f2;
  color: #b42318;
}

.timeline-row.warning .timeline-icon {
  background: #fffaeb;
  color: #b54708;
}

.timeline-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--foreground);
}

.timeline-detail {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--muted-foreground);
  white-space: pre-wrap;
}

.memory-trace-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: grid;
  gap: 8px;
}

.memory-trace-item {
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 8px;
  padding: 8px 10px;
  background: rgb(255 255 255 / 0.03);
}

.memory-trace-main {
  display: grid;
  gap: 4px;
}

.memory-trace-main strong {
  font-size: 12px;
}

.memory-trace-main span {
  font-size: 11px;
  color: rgb(255 255 255 / 0.55);
}

.memory-trace-main p {
  margin: 0;
  font-size: 11px;
  line-height: 1.45;
  color: rgb(255 255 255 / 0.72);
}

.memory-trace-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.memory-trace-btn {
  border: 1px solid rgb(255 255 255 / 0.12);
  background: transparent;
  color: rgb(255 255 255 / 0.78);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 11px;
  cursor: pointer;
}

.memory-trace-btn.danger {
  color: #fda29b;
  border-color: rgb(253 162 155 / 0.35);
}

.detail-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--muted-foreground);
  font-size: 11px;
  cursor: pointer;
}

.detail-toggle .open {
  transform: rotate(180deg);
}

.detail-json {
  margin: 8px 0 0;
  padding: 8px;
  border-radius: 6px;
  background: rgb(0 0 0 / 0.25);
  font-size: 10px;
  line-height: 1.4;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.timeline-collapse-row {
  padding: 4px 12px 8px;
}
</style>
