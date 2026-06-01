<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import {
  Check,
  ChevronDown,
  Database,
  FileText,
  Loader2,
  Maximize2,
  Minimize2,
  Send,
  Sparkles,
  StopCircle,
  Store,
  Upload,
  X,
} from "lucide-vue-next"
import type { AgentFile, AgentModelConfig } from "@/api/types"
import { useReducedMotion } from "@/composables/useReducedMotion"
import { lightTap } from "@/utils/haptic"

const props = defineProps<{
  modelConfigId?: number | null
  agentModels: AgentModelConfig[]
  modelsLoading: boolean
  draft: string
  files: AgentFile[]
  uploading: boolean
  removingFileId: number | null
  hasActiveRun: boolean
  sending: boolean
  editingRegenerating: boolean
  regeneratingMessageId: number | null
  cancellingRun: boolean
  memoryPanelOpen: boolean
}>()

const emit = defineEmits<{
  "update:draft": [value: string]
  "change-model": [value: number | null]
  submit: []
  "cancel-run": []
  "open-file-picker": []
  "remove-file": [file: AgentFile]
  "open-memory": []
  "file-selected": [event: Event]
}>()

const { reducedMotion } = useReducedMotion()
const composerTextareaRef = ref<HTMLTextAreaElement | null>(null)
const composerExpanded = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const modelDropdownOpen = ref(false)
const selectedProviderKey = ref("")

const input = computed({
  get: () => props.draft,
  set: (val: string) => emit("update:draft", val),
})

const selectedAgentModel = computed(
  () => props.agentModels.find((m) => m.id === props.modelConfigId) ?? props.agentModels[0] ?? null,
)

const modelGroups = computed(() => {
  const groups = new Map<string, { key: string; label: string; icon: string; models: AgentModelConfig[] }>()
  for (const model of props.agentModels) {
    const key = model.provider || "unknown"
    const existing = groups.get(key)
    if (existing) {
      existing.models.push(model)
    } else {
      groups.set(key, {
        key,
        label: providerLabel(key),
        icon: providerIcon(key),
        models: [model],
      })
    }
  }
  return Array.from(groups.values())
})

const activeModelGroup = computed(() => {
  const selected = selectedAgentModel.value
  const selectedKey = selected?.provider || selectedProviderKey.value
  return modelGroups.value.find((group) => group.key === selectedProviderKey.value)
    ?? modelGroups.value.find((group) => group.key === selectedKey)
    ?? modelGroups.value[0]
    ?? null
})

const inputBlocked = computed(
  () => props.hasActiveRun || props.editingRegenerating || props.regeneratingMessageId != null,
)

const sendButtonState = computed(() => {
  if (props.sending || props.hasActiveRun) return "stop"
  if (input.value.trim() || props.files.length > 0) return "ready"
  return "idle"
})

const sendDisabled = computed(
  () =>
    props.editingRegenerating ||
    props.regeneratingMessageId != null ||
    ((!props.sending &&
      !props.hasActiveRun &&
      ((!input.value.trim() && !props.files.length) ||
        props.modelsLoading ||
        !props.modelConfigId)) ||
      props.cancellingRun),
)

function modelLabel(model: AgentModelConfig) {
  return model.displayName || model.modelName || model.configCode || `Model ${model.id}`
}

function providerLabel(provider: string) {
  const normalized = provider.toLowerCase()
  if (normalized.includes("openai")) return "OpenAI"
  if (normalized.includes("anthropic")) return "Anthropic"
  if (normalized.includes("deepseek")) return "DeepSeek"
  if (normalized.includes("doubao") || normalized.includes("volc")) return "豆包"
  if (normalized.includes("qwen") || normalized.includes("dashscope")) return "通义千问"
  if (normalized.includes("gemini") || normalized.includes("google")) return "Google"
  if (normalized.includes("mock")) return "Mock"
  return provider || "Models"
}

function providerIcon(provider: string) {
  const label = providerLabel(provider)
  if (/^[A-Za-z]/.test(label)) return label.slice(0, 1).toUpperCase()
  return label.slice(0, 1)
}

function providerIconClass(provider: string) {
  const normalized = provider.toLowerCase()
  if (normalized.includes("deepseek")) return "model-provider-icon--blue"
  if (normalized.includes("doubao") || normalized.includes("volc")) return "model-provider-icon--cyan"
  if (normalized.includes("qwen") || normalized.includes("dashscope")) return "model-provider-icon--purple"
  if (normalized.includes("google") || normalized.includes("gemini")) return "model-provider-icon--rainbow"
  if (normalized.includes("openai")) return "model-provider-icon--green"
  return "model-provider-icon--neutral"
}

function modelMeta(model: AgentModelConfig) {
  const capabilities = model.capabilities?.filter(Boolean).slice(0, 2).join(" · ")
  return capabilities || model.modelName || model.configCode || model.provider
}

function changeModel(rawId: string) {
  if (!rawId) {
    emit("change-model", null)
    return
  }
  const id = Number(rawId)
  emit("change-model", Number.isFinite(id) && id > 0 ? id : null)
  modelDropdownOpen.value = false
}

function toggleModelDropdown() {
  if (props.modelsLoading || props.hasActiveRun || props.sending || props.editingRegenerating || props.regeneratingMessageId != null || props.agentModels.length === 0) return
  selectedProviderKey.value = selectedAgentModel.value?.provider || modelGroups.value[0]?.key || ""
  modelDropdownOpen.value = !modelDropdownOpen.value
}

function chooseModel(model: AgentModelConfig) {
  emit("change-model", model.id)
  modelDropdownOpen.value = false
}

function formatFileSize(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function toggleComposerExpanded() {
  composerExpanded.value = !composerExpanded.value
  void nextTick(() => {
    const el = composerTextareaRef.value
    if (el) el.style.removeProperty("height")
    adjustComposerTextareaHeight()
  })
}

function adjustComposerTextareaHeight() {
  const el = composerTextareaRef.value
  if (!el) return
  const minH = composerExpanded.value ? 120 : 28
  const maxH = composerExpanded.value ? Math.min(window.innerHeight * 0.5, 420) : 150
  el.style.overflowY = "hidden"
  el.style.height = "0px"
  void el.offsetHeight
  const scrollH = el.scrollHeight
  const target = Math.min(Math.max(scrollH, minH), maxH)
  el.style.height = `${target}px`
  el.style.overflowY = scrollH > maxH ? "auto" : "hidden"
}

function onSubmit() {
  if (sendButtonState.value === "stop") {
    emit("cancel-run")
    return
  }
  if (sendDisabled.value) return
  lightTap()
  emit("submit")
}

function openFilePicker() {
  fileInputRef.value?.click()
}

function onFileChange(event: Event) {
  emit("file-selected", event)
  const inputEl = event.target as HTMLInputElement
  inputEl.value = ""
}

watch(input, () => {
  void nextTick(() => adjustComposerTextareaHeight())
})

watch(composerExpanded, () => {
  void nextTick(() => adjustComposerTextareaHeight())
})

onMounted(() => {
  window.addEventListener("resize", adjustComposerTextareaHeight)
  void nextTick(() => adjustComposerTextareaHeight())
})

onUnmounted(() => {
  window.removeEventListener("resize", adjustComposerTextareaHeight)
})

defineExpose({ adjustComposerTextareaHeight })
</script>

<template>
  <form class="composer" @submit.prevent="onSubmit">
    <input
      ref="fileInputRef"
      type="file"
      class="sr-only"
      @change="onFileChange"
    />

    <div class="composer-model-row">
      <div class="composer-model-copy">
        <span class="composer-model-kicker">Agent 模型</span>
        <strong v-if="selectedAgentModel">{{ modelLabel(selectedAgentModel) }}</strong>
        <strong v-else>{{ modelsLoading ? "模型加载中" : "未选择模型" }}</strong>
      </div>
      <button
        type="button"
        class="composer-model-trigger"
        :disabled="
          modelsLoading ||
          hasActiveRun ||
          sending ||
          editingRegenerating ||
          regeneratingMessageId != null ||
          agentModels.length === 0
        "
        @click="toggleModelDropdown"
      >
        <span>{{ selectedAgentModel ? modelLabel(selectedAgentModel) : (modelsLoading ? "加载中" : "暂无模型") }}</span>
        <ChevronDown class="h-3.5 w-3.5" :class="{ 'rotate-180': modelDropdownOpen }" />
      </button>
      <div v-if="modelDropdownOpen" class="model-picker-popover">
        <aside class="model-picker-groups">
          <button
            v-for="group in modelGroups"
            :key="group.key"
            type="button"
            class="model-provider-item"
            :class="{ active: activeModelGroup?.key === group.key }"
            @click="selectedProviderKey = group.key"
          >
            <span class="model-provider-icon" :class="providerIconClass(group.key)">{{ group.icon }}</span>
            <span>{{ group.label }}</span>
          </button>
        </aside>
        <section class="model-picker-models">
          <button
            v-for="model in activeModelGroup?.models ?? []"
            :key="model.id"
            type="button"
            class="model-detail-item"
            :class="{ active: model.id === modelConfigId }"
            @click="chooseModel(model)"
          >
            <span class="model-provider-icon" :class="providerIconClass(model.provider)">{{ providerIcon(model.provider) }}</span>
            <span class="model-detail-copy">
              <strong>{{ modelLabel(model) }}</strong>
              <small>{{ modelMeta(model) }}</small>
            </span>
            <Check v-if="model.id === modelConfigId" class="h-4 w-4 text-primary" />
          </button>
        </section>
      </div>
      <select
        class="composer-model-select"
        :value="modelConfigId ?? ''"
        :disabled="
          modelsLoading ||
          hasActiveRun ||
          sending ||
          editingRegenerating ||
          regeneratingMessageId != null ||
          agentModels.length === 0
        "
        @change="changeModel(($event.target as HTMLSelectElement).value)"
      >
        <option v-if="modelsLoading" value="">加载中...</option>
        <option v-else-if="agentModels.length === 0" value="">暂无可选模型</option>
        <option v-for="model in agentModels" :key="model.id" :value="model.id">
          {{ modelLabel(model) }}
        </option>
      </select>
    </div>

    <div v-if="files.length > 0" class="inner-file-list">
      <div v-for="file in files" :key="file.id" class="inner-file-item">
        <FileText class="h-4 w-4" />
        <div class="inner-file-info">
          <span class="inner-file-name">{{ file.originalFilename }}</span>
          <span class="inner-file-size">{{ formatFileSize(file.fileSize) }}</span>
        </div>
        <button
          type="button"
          class="inner-file-close"
          :disabled="removingFileId === file.id"
          aria-label="移除附件"
          @click.stop="emit('remove-file', file)"
        >
          <Loader2 v-if="removingFileId === file.id" class="h-3 w-3 animate-spin" />
          <X v-else class="h-3 w-3" />
        </button>
      </div>
    </div>

    <div class="input-wrap">
      <textarea
        ref="composerTextareaRef"
        v-model="input"
        rows="1"
        class="chat-input"
        :class="{
          'input-expand': composerExpanded,
          'chat-input--spring': !reducedMotion,
        }"
        :placeholder="
          inputBlocked ? 'Agent 正在处理当前请求' : '输入消息，回车发送'
        "
        :disabled="inputBlocked"
        @keydown.enter.exact.prevent="onSubmit()"
      />
      <button
        class="expand-btn"
        type="button"
        :disabled="inputBlocked"
        @click="toggleComposerExpanded"
      >
        <Minimize2 v-if="composerExpanded" class="h-4 w-4" />
        <Maximize2 v-else class="h-4 w-4" />
      </button>
    </div>

    <div class="toolbar-row">
      <div class="left-tools">
        <button
          type="button"
          class="tool-btn"
          :class="{ 'tool-btn--active': files.length > 0 }"
          :disabled="uploading || sending || editingRegenerating || regeneratingMessageId != null || hasActiveRun"
          @click="openFilePicker"
        >
          <Upload class="h-4 w-4 tool-icon" />
          附件
        </button>
        <button
          type="button"
          class="tool-btn tool-btn--placeholder"
          aria-disabled="true"
          title="即将推出"
        >
          <Sparkles class="h-4 w-4 tool-icon" />
          深度思考
        </button>
        <button
          type="button"
          class="tool-btn tool-btn--placeholder"
          aria-disabled="true"
          title="即将推出"
        >
          <Store class="h-4 w-4 tool-icon" />
          智能搜索
        </button>
        <button
          type="button"
          class="tool-btn"
          :class="{ 'tool-btn--active': memoryPanelOpen }"
          @click="emit('open-memory')"
        >
          <Database class="h-4 w-4 tool-icon" />
          记忆
        </button>
      </div>

      <button
        type="button"
        class="send-circle-btn"
        :class="{
          stop: sendButtonState === 'stop',
          ready: sendButtonState === 'ready',
          idle: sendButtonState === 'idle',
        }"
        :disabled="sendDisabled"
        @click="onSubmit()"
      >
        <Loader2 v-if="cancellingRun" class="h-4 w-4 animate-spin" />
        <StopCircle v-else-if="sendButtonState === 'stop'" class="h-4 w-4" />
        <Send v-else class="h-4 w-4" />
      </button>
    </div>
  </form>
</template>

<style scoped>
.composer {
  width: min(760px, calc(100% - 96px));
  margin: 0 auto 22px;
  border: 0;
  border-radius: 24px;
  background: var(--agent-composer-bg);
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
  position: relative;
  z-index: 2;
  box-shadow:
    0 -12px 40px var(--agent-accent-glow, rgb(176 92 255 / 0.06)),
    0 20px 60px rgb(0 0 0 / 0.38),
    inset 0 1px 0 rgb(255 255 255 / 0.06),
    inset 0 0 0 1px rgb(255 255 255 / 0.04);
  backdrop-filter: blur(20px) saturate(135%);
}

.composer-model-row {
  display: flex;
  align-items: center;
  align-self: flex-start;
  gap: 6px;
  max-width: min(360px, 100%);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.028);
  padding: 2px 5px 2px 8px;
  position: relative;
}

.composer-model-kicker {
  color: rgb(255 255 255 / 0.32);
  font-size: 10px;
  line-height: 1;
  white-space: nowrap;
}

.composer-model-copy strong {
  display: none;
}

.composer-model-select {
  display: none;
  width: min(164px, 40vw);
  min-height: 24px;
  border: 0;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.14);
  color: rgb(255 255 255 / 0.72);
  padding: 0 24px 0 9px;
  outline: none;
  font-size: 11px;
}

.composer-model-trigger {
  min-height: 24px;
  max-width: min(210px, 46vw);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.14);
  color: rgb(255 255 255 / 0.78);
  padding: 0 9px;
  cursor: pointer;
  font-size: 11px;
}

.composer-model-trigger span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.composer-model-trigger svg {
  transition: transform 0.16s ease;
}

.composer-model-trigger:disabled {
  opacity: 0.62;
  cursor: not-allowed;
}

.model-picker-popover {
  position: absolute;
  left: 0;
  bottom: calc(100% + 10px);
  z-index: 20;
  display: grid;
  grid-template-columns: 168px minmax(260px, 360px);
  width: min(540px, calc(100vw - 36px));
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 14px;
  background: rgb(21 21 29 / 0.98);
  box-shadow: 0 18px 60px rgb(0 0 0 / 0.42);
}

.model-picker-groups,
.model-picker-models {
  display: grid;
  align-content: start;
  gap: 4px;
  max-height: 310px;
  overflow-y: auto;
  padding: 8px;
}

.model-picker-groups {
  border-right: 1px solid rgb(255 255 255 / 0.07);
}

.model-provider-item,
.model-detail-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgb(255 255 255 / 0.72);
  padding: 8px;
  cursor: pointer;
  text-align: left;
}

.model-provider-item.active,
.model-provider-item:hover,
.model-detail-item.active,
.model-detail-item:hover {
  background: rgb(255 255 255 / 0.07);
  color: #fff;
}

.model-provider-icon {
  width: 24px;
  height: 24px;
  display: inline-grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 7px;
  background: rgb(255 255 255 / 0.09);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.model-provider-icon--blue { background: rgb(37 99 235 / 0.32); }
.model-provider-icon--cyan { background: rgb(8 145 178 / 0.34); }
.model-provider-icon--purple { background: rgb(124 58 237 / 0.34); }
.model-provider-icon--green { background: rgb(22 163 74 / 0.34); }
.model-provider-icon--rainbow { background: linear-gradient(135deg, #4285f4, #34a853 45%, #fbbc05 70%, #ea4335); }
.model-provider-icon--neutral { background: rgb(255 255 255 / 0.09); }

.model-detail-copy {
  min-width: 0;
  display: grid;
  flex: 1;
  gap: 3px;
}

.model-detail-copy strong,
.model-detail-copy small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-detail-copy strong {
  color: rgb(255 255 255 / 0.88);
  font-size: 13px;
}

.model-detail-copy small {
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
}

.composer-model-select:disabled {
  opacity: 0.62;
  cursor: not-allowed;
}

.inner-file-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 120px;
  overflow-y: auto;
  padding-right: 4px;
}

.inner-file-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: rgb(255 255 255 / 0.045);
  border: 1px solid rgb(255 255 255 / 0.06);
  border-radius: 14px;
  font-size: 12px;
}

.inner-file-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.inner-file-name {
  color: rgb(255 255 255 / 0.84);
  font-weight: 500;
}

.inner-file-size {
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
}

.inner-file-close {
  background: transparent;
  border: none;
  color: rgb(255 255 255 / 0.42);
  cursor: pointer;
  padding: 2px;
}

.inner-file-close:hover {
  color: #fff;
}

.input-wrap {
  position: relative;
}

.chat-input {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  line-height: 1.6;
  min-height: 48px;
  max-height: 160px;
  resize: none;
  padding: 6px 36px 6px 2px;
  color: var(--agent-text-primary);
}

.chat-input--spring {
  transition: height 280ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

@media (prefers-reduced-motion: reduce) {
  .chat-input--spring {
    transition: height 120ms ease;
  }
}

.chat-input::placeholder {
  color: rgb(255 255 255 / 0.34);
}

.chat-input.input-expand {
  min-height: 110px;
  max-height: 40vh;
}

.chat-input:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.expand-btn {
  position: absolute;
  right: 0;
  bottom: 8px;
  border: none;
  background: transparent;
  color: rgb(255 255 255 / 0.42);
  cursor: pointer;
  padding: 2px;
}

.expand-btn:hover {
  color: #fff;
}

.expand-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.toolbar-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
}

.left-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 6px 8px;
  border-radius: 999px;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.38);
  cursor: pointer;
  transition: color 0.18s ease;
}

.tool-btn :deep(.tool-icon) {
  stroke-width: 1.75;
}

.tool-btn:hover:not(:disabled):not(.tool-btn--placeholder) {
  color: rgb(255 255 255 / 0.72);
}

.tool-btn--active {
  color: var(--agent-accent) !important;
  text-shadow: 0 0 12px var(--agent-accent-glow);
}

.tool-btn--placeholder {
  cursor: default;
  opacity: 0.55;
}

.tool-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.send-circle-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid rgb(255 255 255 / 0.12);
  background: #3a3a42;
  color: rgb(255 255 255 / 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transform: scale(0.96);
  transition:
    transform 0.22s cubic-bezier(0.34, 1.56, 0.64, 1),
    background 0.2s ease,
    box-shadow 0.2s ease,
    border-color 0.2s ease;
}

.send-circle-btn.ready {
  transform: scale(1);
  border-color: rgb(255 255 255 / 0.14);
  background:
    radial-gradient(circle at 28% 20%, rgb(255 255 255 / 0.42), transparent 24%),
    var(--agent-send-gradient);
  color: #fff;
  box-shadow:
    0 0 0 1px var(--agent-accent-soft),
    0 10px 32px var(--agent-accent-glow),
    0 0 50px var(--agent-accent-soft);
}

.send-circle-btn.ready:hover:not(:disabled) {
  transform: scale(1.04) translateY(-1px);
  filter: brightness(1.08);
}

.send-circle-btn.stop {
  transform: scale(1);
  border-color: rgb(248 113 113 / 0.72);
  background: rgb(127 29 29);
  color: #fff;
  box-shadow: 0 10px 30px rgb(248 113 113 / 0.22);
}

.send-circle-btn.stop:hover:not(:disabled) {
  background: rgb(153 27 27);
}

.send-circle-btn:disabled {
  opacity: 0.42;
  cursor: not-allowed;
  transform: scale(0.96);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}

@media (max-width: 720px) {
  .composer {
    width: calc(100% - 24px);
    margin-bottom: max(14px, env(safe-area-inset-bottom));
  }
}
</style>
