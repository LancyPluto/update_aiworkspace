<script setup lang="ts">
import { Check, Loader2, Store, X } from "lucide-vue-next"
import type { AgentRunEvent } from "@/api/types"

defineProps<{
  items: Array<{ event: AgentRunEvent; payload: Record<string, unknown> }>
  confirmingEventIds: Set<number>
  rememberTool: boolean
}>()

const emit = defineEmits<{
  "update:rememberTool": [value: boolean]
  confirm: [payload: { eventId: number; toolCode: string; approved: boolean }]
}>()

function toolCodeFor(event: AgentRunEvent, payload: Record<string, unknown>) {
  return String(payload.toolCode || event.eventText)
}

function titleFor(event: AgentRunEvent, payload: Record<string, unknown>) {
  return String(payload.toolName || payload.toolCode || event.eventText)
}

function descriptionFor(payload: Record<string, unknown>) {
  return String(payload.description || "确认后 Agent 会继续执行该工具并生成结果。")
}
</script>

<template>
  <article
    v-for="{ event, payload } in items"
    :key="event.id"
    class="confirmation-card"
  >
    <div class="card-icon"><Store class="h-4 w-4" /></div>
    <div class="card-body">
      <p class="card-title">建议调用 {{ titleFor(event, payload) }}</p>
      <p class="card-desc">{{ descriptionFor(payload) }}</p>
      <label class="remember-row">
        <input
          :checked="rememberTool"
          type="checkbox"
          @change="emit('update:rememberTool', ($event.target as HTMLInputElement).checked)"
        />
        以后调用该工具不再提示
      </label>
      <div class="card-actions">
        <button
          type="button"
          class="ghost-btn"
          :disabled="confirmingEventIds.has(event.id)"
          @click="emit('confirm', { eventId: event.id, toolCode: toolCodeFor(event, payload), approved: false })"
        >
          <Loader2 v-if="confirmingEventIds.has(event.id)" class="h-4 w-4 animate-spin" />
          <X v-else class="h-4 w-4" />
          取消
        </button>
        <button
          type="button"
          class="primary-btn"
          :disabled="confirmingEventIds.has(event.id)"
          @click="emit('confirm', { eventId: event.id, toolCode: toolCodeFor(event, payload), approved: true })"
        >
          <Loader2 v-if="confirmingEventIds.has(event.id)" class="h-4 w-4 animate-spin" />
          <Check v-else class="h-4 w-4" />
          确认调用
        </button>
      </div>
    </div>
  </article>
</template>

<style scoped>
.confirmation-card {
  display: grid;
  grid-template-columns: 42px minmax(0, 820px);
  gap: 14px;
  max-width: 1040px;
  margin: 24px auto;
}

.card-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 15px;
  border: 1px solid rgb(255 255 255 / 0.075);
  background: rgb(255 255 255 / 0.045);
  color: rgb(255 255 255 / 0.82);
}

.card-body {
  border: 1px solid rgb(255 255 255 / 0.10);
  border-radius: 22px;
  background: rgb(255 255 255 / 0.055);
  padding: 16px;
  box-shadow: 0 18px 44px rgb(0 0 0 / 0.18);
}

.card-title {
  margin: 0;
  font-weight: 700;
}

.card-desc {
  margin: 6px 0 12px;
  color: rgb(255 255 255 / 0.52);
  font-size: 13px;
}

.remember-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.78);
  font-size: 13px;
}

.card-actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}

.primary-btn,
.ghost-btn {
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 999px;
  padding: 0 12px;
}

.primary-btn:disabled,
.ghost-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.primary-btn {
  border-color: var(--agent-accent-soft);
  background: var(--agent-send-gradient);
  color: #fff;
  box-shadow: 0 10px 26px var(--agent-accent-glow);
}

.ghost-btn {
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.76);
}

@media (max-width: 900px) {
  .confirmation-card {
    grid-template-columns: 34px minmax(0, 1fr);
    gap: 10px;
    margin: 20px auto;
  }

  .card-icon {
    width: 34px;
    height: 34px;
    border-radius: 13px;
  }
}
</style>
