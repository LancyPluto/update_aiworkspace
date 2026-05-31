<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue"
import { Layers, X } from "lucide-vue-next"
import type { ConversationPhase } from "@/utils/conversationPhases"
import { selectionTick } from "@/utils/haptic"

defineProps<{
  phases: ConversationPhase[]
}>()

const emit = defineEmits<{
  navigate: [messageId: number]
}>()

const open = ref(false)

function toggle() {
  open.value = !open.value
}

function close() {
  open.value = false
}

function onSelect(phase: ConversationPhase) {
  if (phase.messageId == null) return
  selectionTick()
  emit("navigate", phase.messageId)
  close()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") close()
}

onMounted(() => {
  window.addEventListener("keydown", onKeydown)
})

onUnmounted(() => {
  window.removeEventListener("keydown", onKeydown)
})

function phaseIcon(type: ConversationPhase["type"]) {
  switch (type) {
    case "session_start":
      return "○"
    case "user_prompt":
      return "◆"
    case "media_insert":
      return "▣"
    case "tool_run":
      return "⚡"
    case "generation":
      return "✦"
    case "summary":
      return "▤"
    default:
      return "·"
  }
}
</script>

<template>
  <div class="phase-timeline">
    <button
      type="button"
      class="phase-timeline__toggle"
      :class="{ 'phase-timeline__toggle--open': open }"
      aria-label="对话目录"
      title="对话目录"
      @click="toggle"
    >
      <Layers class="h-4 w-4" />
    </button>

    <Transition name="phase-overlay">
      <div v-if="open" class="phase-timeline__backdrop" @click.self="close">
        <aside class="phase-timeline__panel" role="dialog" aria-label="对话阶段">
          <header class="phase-timeline__header">
            <div>
              <p class="phase-timeline__kicker">Conversation</p>
              <h3>对话阶段</h3>
            </div>
            <button type="button" class="phase-timeline__close" aria-label="关闭" @click="close">
              <X class="h-4 w-4" />
            </button>
          </header>

          <ul v-if="phases.length" class="phase-timeline__list">
            <li v-for="phase in phases" :key="phase.id">
              <button
                type="button"
                class="phase-timeline__item"
                :disabled="phase.messageId == null"
                @click="onSelect(phase)"
              >
                <span class="phase-timeline__icon">{{ phaseIcon(phase.type) }}</span>
                <span class="phase-timeline__label">{{ phase.label }}</span>
              </button>
            </li>
          </ul>
          <p v-else class="phase-timeline__empty">暂无阶段记录</p>
        </aside>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.phase-timeline {
  position: relative;
}

.phase-timeline__toggle {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  border: 1px solid rgb(255 255 255 / 0.12);
  background: rgb(24 24 28 / 0.88);
  color: rgb(255 255 255 / 0.55);
  display: grid;
  place-items: center;
  cursor: pointer;
  backdrop-filter: blur(12px);
  box-shadow: 0 8px 24px rgb(0 0 0 / 0.35);
  transition: color 0.18s ease, background 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}

.phase-timeline__toggle:hover,
.phase-timeline__toggle--open {
  transform: translateY(-2px);
  color: var(--agent-accent);
  border-color: var(--agent-accent-soft);
  background: rgb(24 24 28 / 0.92);
  box-shadow: 0 8px 28px var(--agent-accent-glow);
}

.phase-timeline__backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  justify-content: flex-end;
  background: rgb(0 0 0 / 0.42);
  backdrop-filter: blur(6px);
}

.phase-timeline__panel {
  width: min(320px, calc(100vw - 24px));
  height: 100%;
  background:
    radial-gradient(circle at 20% 0%, var(--agent-composer-tint), transparent 34%),
    rgb(18 18 22 / 0.96);
  border-left: 1px solid rgb(255 255 255 / 0.08);
  padding: 20px 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-shadow: -20px 0 80px rgb(0 0 0 / 0.45);
}

.phase-timeline__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.phase-timeline__kicker {
  margin: 0 0 4px;
  font-size: 11px;
  color: var(--agent-accent);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.phase-timeline__header h3 {
  margin: 0;
  font-size: 18px;
  color: #fff;
}

.phase-timeline__close {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  border: 1px solid rgb(255 255 255 / 0.08);
  background: transparent;
  color: rgb(255 255 255 / 0.6);
  cursor: pointer;
  display: grid;
  place-items: center;
}

.phase-timeline__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}

.phase-timeline__item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid transparent;
  background: transparent;
  color: rgb(255 255 255 / 0.78);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease, transform 0.16s ease;
}

.phase-timeline__item:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.05);
  border-color: rgb(255 255 255 / 0.06);
  transform: translateX(-2px);
}

.phase-timeline__item:disabled {
  opacity: 0.45;
  cursor: default;
}

.phase-timeline__icon {
  width: 20px;
  flex-shrink: 0;
  color: var(--agent-accent);
  font-size: 12px;
  text-align: center;
}

.phase-timeline__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.phase-timeline__empty {
  margin: 0;
  color: rgb(255 255 255 / 0.45);
  font-size: 13px;
}

.phase-overlay-enter-active .phase-timeline__panel,
.phase-overlay-leave-active .phase-timeline__panel {
  transition: transform 0.28s cubic-bezier(0.34, 1.2, 0.64, 1);
}

.phase-overlay-enter-from .phase-timeline__panel,
.phase-overlay-leave-to .phase-timeline__panel {
  transform: translateX(100%);
}

.phase-overlay-enter-active,
.phase-overlay-leave-active {
  transition: opacity 0.2s ease;
}

.phase-overlay-enter-from,
.phase-overlay-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .phase-overlay-enter-active .phase-timeline__panel,
  .phase-overlay-leave-active .phase-timeline__panel {
    transition: transform 0.12s ease;
  }
}

@media (max-width: 720px) {
  .phase-timeline__toggle {
    width: 34px;
    height: 34px;
  }
}
</style>
