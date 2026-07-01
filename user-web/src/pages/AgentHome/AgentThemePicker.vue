<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch, withDefaults } from "vue"
import { Palette } from "lucide-vue-next"
import {
  AGENT_AMBIENT_THEMES,
  applyAgentThemeToElement,
  getStoredAgentTheme,
  storeAgentTheme,
  type AgentAmbientThemeId,
} from "@/utils/agentTheme"

const props = withDefaults(defineProps<{
  targetSelector?: string
  variant?: "icon" | "toolbar"
}>(), {
  variant: "icon",
})

const emit = defineEmits<{
  change: [id: AgentAmbientThemeId]
}>()

const open = ref(false)
const rootRef = ref<HTMLElement | null>(null)
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

function close() {
  open.value = false
}

function onDocumentPointerDown(event: MouseEvent) {
  const root = rootRef.value
  if (!root || root.contains(event.target as Node)) return
  close()
}

onMounted(() => {
  document.addEventListener("mousedown", onDocumentPointerDown)
})

onUnmounted(() => {
  document.removeEventListener("mousedown", onDocumentPointerDown)
})

watch(
  () => props.targetSelector,
  () => {
    applyTheme(selectedId.value)
  },
  { immediate: true },
)
</script>

<template>
  <div ref="rootRef" class="theme-picker" :class="`theme-picker--${variant}`">
    <button
      type="button"
      class="theme-picker__toggle"
      :class="{ 'theme-picker__toggle--open': open }"
      aria-label="氛围主题"
      title="氛围主题"
      @click="toggle"
    >
      <Palette class="h-4 w-4 theme-picker__icon" />
      <span v-if="variant === 'toolbar'" class="theme-picker__label">氛围</span>
    </button>

    <Transition name="theme-pop">
      <div v-if="open" class="theme-picker__popover" @click.stop>
        <p class="theme-picker__title">选择氛围主题</p>
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
  </div>
</template>

<style scoped>
.theme-picker {
  position: relative;
  display: inline-flex;
  align-items: center;
}

.theme-picker__toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: rgb(255 255 255 / 0.40);
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.theme-picker--icon .theme-picker__toggle {
  width: 40px;
  height: 40px;
}

.theme-picker--toolbar .theme-picker__toggle {
  gap: 5px;
  padding: 6px 9px;
  font-size: 12px;
}

.theme-picker__toggle:hover {
  background: rgb(255 255 255 / 0.08);
  color: rgb(255 255 255 / 0.90);
  transform: translateY(-1px);
}

.theme-picker--toolbar .theme-picker__toggle:hover,
.theme-picker--toolbar .theme-picker__toggle--open {
  background: transparent;
  color: var(--agent-accent);
  text-shadow: 0 0 12px var(--agent-accent-glow);
  transform: none;
}

.theme-picker__icon {
  stroke-width: 1.75;
}

.theme-picker__label {
  line-height: 1;
}

.theme-picker__popover {
  position: absolute;
  right: 0;
  top: 100%;
  z-index: 50;
  margin-top: 8px;
  width: 214px;
  padding: 16px;
  border-radius: 16px;
  background: rgb(18 18 22 / 0.95);
  border: 1px solid rgb(255 255 255 / 0.08);
  box-shadow: 0 20px 50px rgb(0 0 0 / 0.5);
  backdrop-filter: blur(12px);
}

.theme-picker--toolbar .theme-picker__popover {
  left: 0;
  right: auto;
  top: auto;
  bottom: calc(100% + 10px);
  margin-top: 0;
}

.theme-picker__title {
  margin: 0 0 12px;
  font-size: 12px;
  color: rgb(255 255 255 / 0.40);
  letter-spacing: 0;
}

.theme-picker__grid {
  display: grid;
  grid-template-columns: repeat(4, 36px);
  gap: 10px;
}

.theme-picker__swatch {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  border: 2px solid transparent;
  cursor: pointer;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.24), 0 10px 24px rgb(0 0 0 / 0.24);
  transition: transform 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
}

.theme-picker__swatch:hover {
  transform: scale(1.06);
}

.theme-picker__swatch--active {
  border-color: #fff;
  box-shadow: 0 0 0 2px var(--agent-accent), inset 0 1px 0 rgb(255 255 255 / 0.30), 0 12px 28px rgb(0 0 0 / 0.30);
}

.theme-pop-enter-active,
.theme-pop-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.theme-pop-enter-from,
.theme-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.98);
}
</style>
