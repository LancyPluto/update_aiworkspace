<script setup lang="ts">
import { ref, watch } from "vue"
import { Palette } from "lucide-vue-next"
import {
  AGENT_AMBIENT_THEMES,
  applyAgentThemeToElement,
  getStoredAgentTheme,
  storeAgentTheme,
  type AgentAmbientThemeId,
} from "@/utils/agentTheme"

const props = defineProps<{
  targetSelector?: string
}>()

const emit = defineEmits<{
  change: [id: AgentAmbientThemeId]
}>()

const open = ref(false)
const selectedId = ref<AgentAmbientThemeId>(getStoredAgentTheme())

function applyTheme(id: AgentAmbientThemeId) {
  selectedId.value = id
  storeAgentTheme(id)
  const selector = props.targetSelector ?? ".agent-page"
  const el = document.querySelector(selector) as HTMLElement | null
  if (el) applyAgentThemeToElement(el, id)
  emit("change", id)
}

function toggle() {
  open.value = !open.value
}

watch(
  () => props.targetSelector,
  () => {
    applyTheme(selectedId.value)
  },
  { immediate: true },
)
</script>

<template>
  <div class="theme-picker">
    <button
      type="button"
      class="theme-picker__toggle"
      aria-label="氛围主题"
      title="氛围主题"
      @click="toggle"
    >
      <Palette class="h-4 w-4" />
    </button>

    <Transition name="theme-pop">
      <div v-if="open" class="theme-picker__popover" @click.stop>
        <p class="theme-picker__title">氛围主题</p>
        <div class="theme-picker__grid">
          <button
            v-for="theme in AGENT_AMBIENT_THEMES"
            :key="theme.id"
            type="button"
            class="theme-picker__swatch"
            :class="{ 'theme-picker__swatch--active': selectedId === theme.id }"
            :style="{ background: theme.swatch }"
            :title="theme.label"
            :aria-label="theme.label"
            @click="applyTheme(theme.id)"
          />
        </div>
      </div>
    </Transition>
    <div v-if="open" class="theme-picker__backdrop" @click="open = false" />
  </div>
</template>

<style scoped>
.theme-picker {
  position: relative;
  margin-top: auto;
  padding-top: 12px;
}

.theme-picker__toggle {
  width: 100%;
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 12px;
  border: 1px solid rgb(255 255 255 / 0.08);
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.62);
  cursor: pointer;
  font-size: 12px;
  transition: background 0.16s ease, color 0.16s ease;
}

.theme-picker__toggle:hover {
  background: rgb(255 255 255 / 0.07);
  color: #fff;
}

.theme-picker__backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
}

.theme-picker__popover {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 8px);
  z-index: 21;
  padding: 12px;
  border-radius: 16px;
  background: rgb(22 22 26 / 0.96);
  border: 1px solid rgb(255 255 255 / 0.08);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.5);
  backdrop-filter: blur(16px);
}

.theme-picker__title {
  margin: 0 0 10px;
  font-size: 11px;
  color: rgb(255 255 255 / 0.45);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.theme-picker__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.theme-picker__swatch {
  aspect-ratio: 1;
  border-radius: 10px;
  border: 2px solid transparent;
  cursor: pointer;
  transition: transform 0.15s ease, border-color 0.15s ease;
}

.theme-picker__swatch:hover {
  transform: scale(1.06);
}

.theme-picker__swatch--active {
  border-color: #fff;
  box-shadow: 0 0 0 2px var(--agent-accent);
}

.theme-pop-enter-active,
.theme-pop-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.theme-pop-enter-from,
.theme-pop-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
