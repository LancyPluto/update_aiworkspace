<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { CheckCircle2, ChevronDown, Loader2 } from "lucide-vue-next"
import type { AgentRunEvent } from "@/api/types"
import { filterUserFacingRunEvents } from "./runTimelineEvents"

const props = defineProps<{
  events: AgentRunEvent[]
  inlineMode?: boolean
  processMode?: boolean
  running?: boolean
}>()

const emit = defineEmits<{
  "open-memory": [memoryId: number]
  "delete-memory": [memoryId: number]
}>()

interface MemoryTraceItem {
  id?: number
  type?: string
  title?: string
  preview?: string
  reason?: string
}

const expandedEventIds = ref<Set<number>>(new Set())
const timelineExpanded = ref(false)

const visibleEvents = computed(() => filterUserFacingRunEvents(props.events, props.inlineMode).slice(-40))
const latestEvent = computed(() => visibleEvents.value[visibleEvents.value.length - 1] ?? null)
const activeEventId = computed(() => (props.running ? latestEvent.value?.id ?? null : null))
const hasTerminalFailure = computed(() => visibleEvents.value.some((event) => event.eventType === "run.failed"))
const summaryText = computed(() => {
  const count = visibleEvents.value.length
  const suffix = `（共 ${count} 步）`
  if (props.running && latestEvent.value) return `${titleFor(latestEvent.value)} ${suffix}`
  if (hasTerminalFailure.value) return `Agent 运行遇到问题 ${suffix}`
  return `Agent 已完成运行与工具调用 ${suffix}`
})

watch(
  () => props.running,
  (running) => {
    if (!running) timelineExpanded.value = false
  },
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

function toggleTimelineExpanded() {
  timelineExpanded.value = !timelineExpanded.value
}

function isEventExpanded(eventId: number) {
  return expandedEventIds.value.has(eventId)
}

function hasMemoryTrace(event: AgentRunEvent) {
  return memoryTraceItems(event).length > 0
}

function hasDetail(event: AgentRunEvent) {
  return Boolean(detailFor(event) || detailJson(event) || hasMemoryTrace(event))
}

function stepTitleFor(event: AgentRunEvent, index: number) {
  return `${index + 1}. ${titleFor(event)}`
}
</script>

<template>
  <section
    v-if="visibleEvents.length > 0"
    class="run-timeline border border-white/[0.05] bg-[#121216]/50 rounded-xl"
    :class="{ inline: inlineMode, open: timelineExpanded }"
    aria-label="Agent run timeline"
  >
    <button
      type="button"
      class="timeline-summary"
      :aria-expanded="timelineExpanded"
      @click="toggleTimelineExpanded"
    >
      <span class="summary-leading" :class="{ running: props.running }">
        <Loader2 v-if="props.running" class="h-3.5 w-3.5 animate-spin" />
        <CheckCircle2 v-else class="h-3.5 w-3.5" />
      </span>
      <span class="summary-copy">{{ summaryText }}</span>
      <ChevronDown class="summary-chevron h-4 w-4" :class="{ open: timelineExpanded }" />
    </button>

    <div class="timeline-content transition-all duration-300" :aria-hidden="!timelineExpanded">
      <div class="stepper">
        <div class="stepper-line" aria-hidden="true" />
        <article
          v-for="(event, index) in visibleEvents"
          :key="event.id"
          class="timeline-step"
          :class="{ active: event.id === activeEventId, failed: event.eventType === 'run.failed' }"
        >
          <span class="step-dot" aria-hidden="true" />
          <div class="step-body">
            <div class="step-main">
              <span class="step-title">{{ stepTitleFor(event, index) }}</span>
              <button
                v-if="hasDetail(event)"
                class="detail-toggle"
                type="button"
                @click="toggleExpanded(event.id)"
              >
                {{ isEventExpanded(event.id) ? "收起" : "详情" }}
              </button>
            </div>
            <div v-if="isEventExpanded(event.id)" class="step-detail-wrap">
              <p v-if="detailFor(event)" class="timeline-detail">{{ detailFor(event) }}</p>
              <ul v-if="hasMemoryTrace(event)" class="memory-trace-list">
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
              <pre v-if="detailJson(event)" class="detail-json">{{ detailJson(event) }}</pre>
            </div>
          </div>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.run-timeline {
  width: 100%;
  max-height: 44px;
  overflow: hidden;
  margin: 0;
  transition: max-height 0.3s ease, border-color 0.2s ease, background 0.2s ease;
}

.run-timeline.inline {
  margin: 0;
  padding: 0;
}

.run-timeline.open {
  max-height: 520px;
}

.timeline-summary {
  width: 100%;
  min-height: 44px;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) 20px;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.58);
  padding: 0 12px;
  cursor: pointer;
  text-align: left;
}

.summary-leading {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: rgb(255 255 255 / 0.42);
}

.summary-leading.running {
  color: rgb(192 132 252);
}

.summary-copy {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
}

.summary-chevron {
  color: rgb(255 255 255 / 0.34);
  transition: transform 0.2s ease, color 0.2s ease;
}

.summary-chevron.open {
  color: rgb(255 255 255 / 0.64);
  transform: rotate(180deg);
}

.timeline-content {
  max-height: 0;
  opacity: 0;
  overflow-y: auto;
  padding: 0 12px;
  scrollbar-width: thin;
  scrollbar-color: rgb(255 255 255 / 0.14) transparent;
  transition-property: max-height, opacity, padding;
}

.run-timeline.open .timeline-content {
  max-height: 470px;
  opacity: 1;
  padding: 4px 12px 12px;
}

.stepper {
  position: relative;
  display: grid;
  gap: 0;
  padding-left: 16px;
}

.stepper-line {
  position: absolute;
  left: 4px;
  top: 11px;
  bottom: 11px;
  width: 1px;
  background: rgb(255 255 255 / 0.10);
}

.timeline-step {
  position: relative;
  min-height: 28px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  padding: 5px 0 5px 8px;
}

.step-dot {
  position: absolute;
  left: -15px;
  top: 12px;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.20);
  box-shadow: 0 0 0 3px rgb(18 18 22 / 0.92);
}

.timeline-step.active .step-dot {
  background: rgb(168 85 247);
  box-shadow: 0 0 0 3px rgb(18 18 22 / 0.92), 0 0 14px rgb(168 85 247 / 0.55);
}

.step-body {
  min-width: 0;
}

.step-main {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 18px;
}

.step-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgb(255 255 255 / 0.50);
  font-size: 12px;
  line-height: 18px;
}

.timeline-step.active .step-title {
  color: rgb(255 255 255 / 0.72);
}

.timeline-step.failed .step-title {
  color: rgb(252 165 165 / 0.82);
}

.step-detail-wrap {
  margin: 4px 0 2px;
  padding-left: 2px;
}

.timeline-detail {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: rgb(255 255 255 / 0.46);
  white-space: pre-wrap;
}

.memory-trace-list {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: grid;
  gap: 6px;
}

.memory-trace-item {
  padding: 0;
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
  margin-top: 6px;
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
  flex: 0 0 auto;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.38);
  font-size: 11px;
  cursor: pointer;
}

.detail-toggle:hover {
  color: rgb(216 180 254 / 0.92);
}

.detail-json {
  margin: 6px 0 0;
  padding: 7px;
  border-radius: 6px;
  background: rgb(0 0 0 / 0.25);
  font-size: 10px;
  line-height: 1.4;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
