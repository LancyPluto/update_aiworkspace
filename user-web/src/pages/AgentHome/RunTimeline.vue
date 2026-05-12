<script setup lang="ts">
import { computed, ref } from "vue"
import { AlertTriangle, Bot, CheckCircle2, ChevronDown, Database, FileText, Hammer, Loader2 } from "lucide-vue-next"
import type { AgentRunEvent } from "@/api/types"

const props = defineProps<{
  events: AgentRunEvent[]
}>()

type TimelineTone = "info" | "success" | "warning" | "error"

const expandedEventIds = ref<Set<number>>(new Set())

const visibleEvents = computed(() =>
  props.events
    .filter((event) =>
      [
        "subagent.started",
        "subagent.completed",
        "subagent.failed",
        "memory.context_injected",
        "memory.candidate_created",
        "workspace_file.created",
        "workspace_file.updated",
        "workspace_file.read",
        "tool.started",
        "tool.finished",
        "run.completed",
        "run.failed",
      ].includes(event.eventType),
    )
    .slice(-12),
)

function parseEventJson(value?: string | null) {
  if (!value) return {} as Record<string, unknown>
  try {
    return JSON.parse(value) as Record<string, unknown>
  } catch {
    return {} as Record<string, unknown>
  }
}

function titleFor(event: AgentRunEvent) {
  const payload = parseEventJson(event.eventJson)
  if (event.eventType === "subagent.started") return `Sub-agent started: ${payload.subagentName || event.eventText || "agent"}`
  if (event.eventType === "subagent.completed") return `Sub-agent completed: ${payload.subagentName || event.eventText || "agent"}`
  if (event.eventType === "subagent.failed") return `Sub-agent failed: ${payload.subagentName || event.eventText || "agent"}`
  if (event.eventType === "memory.context_injected") return "Workspace memory injected"
  if (event.eventType === "memory.candidate_created") return "Memory candidate created"
  if (event.eventType === "workspace_file.created") return `Artifact created: ${payload.filename || event.eventText || "file"}`
  if (event.eventType === "workspace_file.updated") return `Artifact updated: ${payload.filename || event.eventText || "file"}`
  if (event.eventType === "workspace_file.read") return "Workspace files read"
  if (event.eventType === "tool.started") return `Tool started: ${payload.toolCode || event.eventText || "tool"}`
  if (event.eventType === "tool.finished") return `Tool finished: ${payload.toolCode || event.eventText || "tool"}`
  if (event.eventType === "run.completed") return "Run completed"
  if (event.eventType === "run.failed") return "Run failed"
  return event.eventType
}

function detailFor(event: AgentRunEvent) {
  const payload = parseEventJson(event.eventJson)
  if (typeof payload.taskDescription === "string") return payload.taskDescription
  if (typeof payload.error === "string") return payload.error
  if (typeof payload.title === "string") return payload.title
  if (typeof payload.filename === "string") return payload.filename
  if (Array.isArray(payload.filenames) && payload.filenames.length > 0) return payload.filenames.join(", ")
  if (typeof event.eventText === "string") return event.eventText
  return ""
}

function toneFor(event: AgentRunEvent): TimelineTone {
  if (event.eventType.endsWith(".failed") || event.eventType === "run.failed") return "error"
  if (event.eventType.endsWith(".completed") || event.eventType.endsWith(".finished") || event.eventType === "run.completed") return "success"
  if (event.eventType === "workspace_file.read" || event.eventType === "memory.context_injected") return "info"
  return "warning"
}

function iconFor(event: AgentRunEvent) {
  if (event.eventType.startsWith("subagent.")) return Bot
  if (event.eventType.startsWith("tool.")) return Hammer
  if (event.eventType.startsWith("workspace_file.")) return FileText
  if (event.eventType.startsWith("memory.")) return Database
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
  <section v-if="visibleEvents.length > 0" class="run-timeline" aria-label="Agent run timeline">
    <article v-for="event in visibleEvents" :key="event.id" class="timeline-row" :class="toneFor(event)">
      <div class="timeline-icon">
        <component :is="iconFor(event)" class="h-4 w-4" />
      </div>
      <div class="timeline-body">
        <p class="timeline-title">{{ titleFor(event) }}</p>
        <p v-if="detailFor(event)" class="timeline-detail">{{ detailFor(event) }}</p>
        <button v-if="detailJson(event)" class="detail-toggle" type="button" @click="toggleExpanded(event.id)">
          <ChevronDown class="h-3 w-3" :class="{ open: expandedEventIds.has(event.id) }" />
          Details
        </button>
        <pre v-if="expandedEventIds.has(event.id) && detailJson(event)" class="detail-json">{{ detailJson(event) }}</pre>
      </div>
    </article>
  </section>
</template>

<style scoped>
.run-timeline {
  width: min(860px, calc(100% - 32px));
  max-height: 180px;
  margin: 0 auto 10px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
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
</style>
