<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch, withDefaults } from "vue"
import {
  Check,
  Clock,
  Database,
  FileText,
  Image,
  Images,
  Loader2,
  Maximize2,
  Minimize2,
  Paperclip,
  Search,
  Send,
  Sparkles,
  StopCircle,
  Trash2,
  Upload,
  Wrench,
  X,
} from "lucide-vue-next"
import type { AgentFile, AgentModelConfig, AgentToolPickerItem, AgentUrlAttachment } from "@/api/types"
import { isImageAttachment, resolveAgentFileUrl } from "@/utils/agentAttachment"
import { readAssetDragPayload } from "@/utils/agentChatAssetRefs"
import { useReducedMotion } from "@/composables/useReducedMotion"
import ModelProviderIcon from "@/components/ModelProviderIcon.vue"
import {
  buildAgentModelGroups,
  groupKeyForModel,
  resolveAgentModelVendor,
  vendorIconClassForGroup,
} from "@/utils/agentModelGroups"
import { formatModelPriceSummary } from "@/utils/formatModelPrice"
import { lightTap } from "@/utils/haptic"

const props = withDefaults(defineProps<{
  modelConfigId?: number | null
  agentModels: AgentModelConfig[]
  modelsLoading: boolean
  draft: string
  files: AgentFile[]
  urlAttachments?: AgentUrlAttachment[]
  recentAttachments?: AgentMaterialAttachment[]
  materialAssets?: AgentMaterialAttachment[]
  materialAssetsLoading?: boolean
  filePreviewUrls?: Record<number, string>
  pendingUploadPreview?: { name: string; url: string } | null
  uploading: boolean
  removingFileId: number | null
  hasActiveRun: boolean
  sending: boolean
  editingRegenerating: boolean
  regeneratingMessageId: number | null
  cancellingRun: boolean
  memoryPanelOpen: boolean
  agentTools?: AgentToolPickerItem[]
  agentToolsLoading?: boolean
  selectedToolCode?: string | null
}>(), {
  agentTools: () => [],
  agentToolsLoading: false,
  selectedToolCode: null,
})

interface AgentMaterialAttachment extends AgentUrlAttachment {
  id: string
  kind: "image" | "video" | "audio" | "file"
  previewUrl?: string
  uploadedAt?: string
  subtitle?: string
}

const emit = defineEmits<{
  "update:draft": [value: string]
  "change-model": [value: number | null]
  submit: []
  "cancel-run": []
  "open-file-picker": []
  "remove-file": [file: AgentFile]
  "select-url-attachment": [file: AgentUrlAttachment]
  "sync-uploaded-files": [fileIds: number[]]
  "remove-url-attachment": [file: AgentUrlAttachment]
  "remove-recent-attachment": [file: AgentMaterialAttachment]
  "refresh-material-assets": []
  "open-memory": []
  "update:selectedToolCode": [value: string | null]
  "refresh-agent-tools": []
  "add-reference-attachment": [payload: import("@/utils/agentChatAssetRefs").ChatAssetDragPayload]
  "file-selected": [event: Event]
  "files-dropped": [files: File[], options?: { autoSelect?: boolean }]
  "preview-attachment": [payload: { name: string; url: string; contentType?: string | null }]
}>()

const { reducedMotion } = useReducedMotion()
const composerTextareaRef = ref<HTMLTextAreaElement | null>(null)
const composerExpanded = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const modelDropdownOpen = ref(false)
const modelPickerRef = ref<HTMLElement | null>(null)
const attachmentDialogOpen = ref(false)
const attachmentDialogTab = ref<"upload" | "material">("upload")
const pickerSelectedUrls = ref<Set<string>>(new Set())
const materialUploadDropActive = ref(false)
const toolMenuOpen = ref(false)
const toolMenuRef = ref<HTMLElement | null>(null)
const toolSearch = ref("")
const selectedToolModality = ref("all")
const composerDropActive = ref(false)
const selectedProviderKey = ref("")

const input = computed({
  get: () => props.draft,
  set: (val: string) => emit("update:draft", val),
})

const selectedAgentModel = computed(
  () => props.agentModels.find((m) => m.id === props.modelConfigId) ?? props.agentModels[0] ?? null,
)

const selectedModelVendor = computed(() =>
  selectedAgentModel.value ? resolveAgentModelVendor(selectedAgentModel.value) : null,
)

const modelGroups = computed(() => buildAgentModelGroups(props.agentModels))

const activeModelGroup = computed(() => {
  const selected = selectedAgentModel.value
  const selectedKey = selected ? groupKeyForModel(selected) : selectedProviderKey.value
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
  if (input.value.trim() || props.files.length > 0 || (props.urlAttachments?.length ?? 0) > 0) return "ready"
  return "idle"
})

const sendDisabled = computed(
  () =>
    props.editingRegenerating ||
    props.regeneratingMessageId != null ||
    ((!props.sending &&
      !props.hasActiveRun &&
      ((!input.value.trim() && !props.files.length && !(props.urlAttachments?.length ?? 0)) ||
        props.modelsLoading ||
        !props.modelConfigId)) ||
      props.cancellingRun),
)

const selectedMaterialAttachments = computed(() => props.urlAttachments ?? [])
const recentMaterialAttachments = computed(() => props.recentAttachments ?? [])
const libraryMaterialAttachments = computed(() => props.materialAssets ?? [])

const selectedTool = computed(
  () => props.agentTools.find((tool) => tool.toolCode === props.selectedToolCode) ?? null,
)

const toolModalityTabs = computed(() => {
  const counts = new Map<string, number>()
  for (const tool of props.agentTools) {
    const key = (tool.outputModality || "text").toLowerCase()
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  const tabs = [{ key: "all", label: "全部", count: props.agentTools.length }]
  for (const [key, count] of counts.entries()) {
    tabs.push({ key, label: toolModalityLabel(key), count })
  }
  return tabs
})

const filteredAgentTools = computed(() => {
  const keyword = toolSearch.value.trim().toLowerCase()
  return props.agentTools.filter((tool) => {
    const modality = (tool.outputModality || "text").toLowerCase()
    if (selectedToolModality.value !== "all" && modality !== selectedToolModality.value) return false
    if (!keyword) return true
    const haystack = `${tool.toolName} ${tool.description || ""} ${tool.toolCode}`.toLowerCase()
    return haystack.includes(keyword)
  })
})

function toolModalityLabel(key: string) {
  if (key === "image") return "图片"
  if (key === "video") return "视频"
  if (key === "audio" || key === "music") return "音频"
  return "文本"
}

function toolPickerButtonLabel() {
  if (!selectedTool.value) return "工具选择"
  const name = selectedTool.value.toolName || selectedTool.value.toolCode
  return name.length > 10 ? `${name.slice(0, 10)}…` : name
}

function toggleToolMenu() {
  if (props.uploading || props.sending || props.editingRegenerating || props.regeneratingMessageId != null || props.hasActiveRun) return
  toolMenuOpen.value = !toolMenuOpen.value
  if (toolMenuOpen.value && props.agentTools.length === 0) {
    emit("refresh-agent-tools")
  }
}

function chooseTool(tool: AgentToolPickerItem) {
  const next = props.selectedToolCode === tool.toolCode ? null : tool.toolCode
  emit("update:selectedToolCode", next)
  toolMenuOpen.value = false
  lightTap()
}

function clearSelectedTool() {
  emit("update:selectedToolCode", null)
}

function onComposerDragOver(event: DragEvent) {
  if (inputBlocked.value) return
  event.preventDefault()
  composerDropActive.value = true
}

function onComposerDragLeave(event: DragEvent) {
  const related = event.relatedTarget as Node | null
  const current = event.currentTarget as HTMLElement | null
  if (current && related && current.contains(related)) return
  composerDropActive.value = false
}

function onComposerDrop(event: DragEvent) {
  event.preventDefault()
  composerDropActive.value = false
  if (inputBlocked.value) return
  const payload = readAssetDragPayload(event)
  if (payload) {
    emit("add-reference-attachment", payload)
    lightTap()
  }
}

function shortAttachmentName(name?: string | null) {
  const cleaned = (name || "图片").replace(/^@[^-]+-/, "").trim() || "图片"
  return cleaned.length > 14 ? `${cleaned.slice(0, 14)}…` : cleaned
}

function imageReferenceLabel(index: number, name?: string | null) {
  return `@图片${index + 1}-${shortAttachmentName(name)}`
}

function selectedUrlAttachmentLabel(file: AgentUrlAttachment, index: number) {
  return isImageAttachment(file.contentType, file.name) ? imageReferenceLabel(index, file.name) : file.name
}

function selectedFileAttachmentLabel(file: AgentFile, index: number) {
  return isImageAttachment(file.contentType, file.originalFilename)
    ? imageReferenceLabel(index, file.originalFilename)
    : file.originalFilename
}

function modelLabel(model: AgentModelConfig) {
  const base = model.displayName || model.modelName || model.configCode || `Model ${model.id}`
  return model.chatSelectable === false ? `${base}（工具）` : base
}

function modelVendorMeta(model: AgentModelConfig) {
  return resolveAgentModelVendor(model)
}

function modelMeta(model: AgentModelConfig) {
  const capabilities = model.capabilities?.filter(Boolean).slice(0, 2).join(" · ")
  const vendorLabel = modelVendorMeta(model).label
  return capabilities || model.modelName || vendorLabel || model.configCode || model.provider
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
  if (!modelDropdownOpen.value) {
    selectedProviderKey.value = selectedAgentModel.value
      ? groupKeyForModel(selectedAgentModel.value)
      : modelGroups.value[0]?.key || ""
  }
  modelDropdownOpen.value = !modelDropdownOpen.value
}

function onDocumentPointerDown(event: PointerEvent) {
  const target = event.target as Node
  const modelRoot = modelPickerRef.value
  if (modelDropdownOpen.value && modelRoot && !modelRoot.contains(target)) {
    modelDropdownOpen.value = false
  }
  const toolRoot = toolMenuRef.value
  if (toolMenuOpen.value && toolRoot && !toolRoot.contains(target)) {
    toolMenuOpen.value = false
  }
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
  const target = composerExpanded.value ? Math.min(window.innerHeight * 0.44, 360) : 52
  el.style.height = `${target}px`
  el.style.overflowY = el.scrollHeight > target ? "auto" : "hidden"
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

function onMaterialUploadDragOver(event: DragEvent) {
  if (props.uploading) return
  event.preventDefault()
  materialUploadDropActive.value = true
}

function onMaterialUploadDragLeave(event: DragEvent) {
  const related = event.relatedTarget as Node | null
  const current = event.currentTarget as HTMLElement | null
  if (current && related && current.contains(related)) return
  materialUploadDropActive.value = false
}

function onMaterialUploadDrop(event: DragEvent) {
  event.preventDefault()
  materialUploadDropActive.value = false
  if (props.uploading) return
  const droppedFiles = Array.from(event.dataTransfer?.files || [])
  if (droppedFiles.length === 0) return
  emit("files-dropped", droppedFiles, { autoSelect: false })
  lightTap()
}

function openAttachmentDialog(tab: "upload" | "material" = "upload") {
  if (props.uploading || props.sending || props.editingRegenerating || props.regeneratingMessageId != null || props.hasActiveRun) return
  attachmentDialogTab.value = tab
  const selected = new Set(selectedMaterialAttachments.value.map((item) => item.url))
  for (const file of props.files) {
    if (file.downloadUrl) selected.add(file.downloadUrl)
  }
  pickerSelectedUrls.value = selected
  attachmentDialogOpen.value = true
  if (libraryMaterialAttachments.value.length === 0) {
    emit("refresh-material-assets")
  }
}

function closeAttachmentDialog() {
  attachmentDialogOpen.value = false
}

const pickerMaterials = computed(() => {
  const seen = new Set<string>()
  const items: AgentMaterialAttachment[] = []
  for (const item of [...recentMaterialAttachments.value, ...libraryMaterialAttachments.value]) {
    if (!item.url || seen.has(item.url)) continue
    seen.add(item.url)
    items.push(item)
  }
  return items
})

const materialSelectedCount = computed(() => pickerSelectedUrls.value.size)

function isPickerSelected(item: AgentMaterialAttachment) {
  return pickerSelectedUrls.value.has(item.url)
}

function togglePickerMaterial(item: AgentMaterialAttachment) {
  const next = new Set(pickerSelectedUrls.value)
  if (next.has(item.url)) {
    next.delete(item.url)
  } else {
    if (next.size + props.files.length >= 8) return
    next.add(item.url)
  }
  pickerSelectedUrls.value = next
}

function resolveAgentUploadedFileId(item: AgentMaterialAttachment): number | null {
  const parsed = Number(item.id)
  if (!Number.isFinite(parsed) || parsed <= 0) return null
  const url = item.url || ""
  return url.includes("/api/v1/agent/sessions/") ? parsed : null
}

function confirmPickerMaterials() {
  const selectedUrls = pickerSelectedUrls.value
  const selectedAgentFileIds = props.files
    .filter((file) => file.downloadUrl && selectedUrls.has(file.downloadUrl))
    .map((file) => file.id)
  for (const item of pickerMaterials.value) {
    if (!selectedUrls.has(item.url)) continue
    const agentFileId = resolveAgentUploadedFileId(item)
    if (agentFileId != null && !selectedAgentFileIds.includes(agentFileId)) {
      selectedAgentFileIds.push(agentFileId)
    }
  }
  emit("sync-uploaded-files", selectedAgentFileIds)
  for (const item of selectedMaterialAttachments.value) {
    if (!selectedUrls.has(item.url)) emit("remove-url-attachment", item)
  }
  for (const item of pickerMaterials.value) {
    if (!selectedUrls.has(item.url)) continue
    if (resolveAgentUploadedFileId(item) != null) continue
    if (!selectedMaterialAttachments.value.some((current) => current.url === item.url)) {
      emit("select-url-attachment", item)
    }
  }
  closeAttachmentDialog()
  lightTap()
}

function materialKindLabel(kind?: string) {
  if (kind === "image") return "图片"
  if (kind === "video") return "视频"
  if (kind === "audio") return "音频"
  return "文件"
}

function urlAttachmentPreviewUrl(file: AgentUrlAttachment) {
  return resolveAgentFileUrl(file.url)
}

function onFileChange(event: Event) {
  const inputEl = event.target as HTMLInputElement
  const selectedFiles = Array.from(inputEl.files || [])
  inputEl.value = ""
  if (attachmentDialogOpen.value) {
    if (selectedFiles.length > 0) {
      emit("files-dropped", selectedFiles, { autoSelect: false })
    }
    return
  }
  emit("file-selected", event)
}

watch(input, () => {
  void nextTick(() => adjustComposerTextareaHeight())
})

watch(composerExpanded, () => {
  void nextTick(() => adjustComposerTextareaHeight())
})

onMounted(() => {
  window.addEventListener("resize", adjustComposerTextareaHeight)
  document.addEventListener("pointerdown", onDocumentPointerDown)
  void nextTick(() => adjustComposerTextareaHeight())
})

onUnmounted(() => {
  window.removeEventListener("resize", adjustComposerTextareaHeight)
  document.removeEventListener("pointerdown", onDocumentPointerDown)
})

defineExpose({ adjustComposerTextareaHeight })
</script>

<template>
  <form
    class="composer"
    :class="{ 'composer--drop-active': composerDropActive }"
    @submit.prevent="onSubmit"
    @dragover.prevent="onComposerDragOver"
    @dragleave="onComposerDragLeave"
    @drop.prevent="onComposerDrop"
  >
    <input
      ref="fileInputRef"
      type="file"
      multiple
      class="sr-only"
      @change="onFileChange"
    />

    <div ref="modelPickerRef" class="composer-model-picker">
      <button
        type="button"
        class="composer-model-pill"
        :class="{ open: modelDropdownOpen }"
        :disabled="
          modelsLoading ||
          hasActiveRun ||
          sending ||
          editingRegenerating ||
          regeneratingMessageId != null ||
          agentModels.length === 0
        "
        @click.stop="toggleModelDropdown"
      >
        <ModelProviderIcon
          v-if="selectedModelVendor"
          :icon-url="selectedModelVendor.iconUrl"
          :mark="selectedModelVendor.mark"
          :label="selectedModelVendor.label"
          :tint-class="vendorIconClassForGroup(selectedModelVendor)"
        />
        <span class="composer-model-pill-label">
          {{ selectedAgentModel ? modelLabel(selectedAgentModel) : (modelsLoading ? "加载中" : "选择模型") }}
        </span>
      </button>

      <Transition :name="reducedMotion ? '' : 'model-picker-fade'">
        <div v-if="modelDropdownOpen" class="model-picker-card" @click.stop>
          <aside class="model-picker-groups">
            <button
              v-for="group in modelGroups"
              :key="group.key"
              type="button"
              class="model-provider-item"
              :class="{ active: activeModelGroup?.key === group.key }"
              @click="selectedProviderKey = group.key"
            >
              <ModelProviderIcon
                :icon-url="group.iconUrl"
                :mark="group.mark"
                :label="group.label"
                :tint-class="vendorIconClassForGroup(group)"
              />
              <span class="model-provider-label">{{ group.label }}</span>
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
              <ModelProviderIcon
                :icon-url="modelVendorMeta(model).iconUrl"
                :mark="modelVendorMeta(model).mark"
                :label="modelLabel(model)"
                :tint-class="vendorIconClassForGroup(modelVendorMeta(model))"
              />
              <span class="model-detail-copy">
                <strong>{{ modelLabel(model) }}</strong>
                <small>{{ modelMeta(model) }}</small>
              </span>
              <span class="model-detail-price">{{ formatModelPriceSummary(model) }}</span>
              <Check v-if="model.id === modelConfigId" class="h-4 w-4 shrink-0 text-primary" />
            </button>
          </section>
        </div>
      </Transition>
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

    <div v-if="files.length > 0 || selectedMaterialAttachments.length > 0 || pendingUploadPreview || uploading" class="inner-file-list">
      <div v-if="pendingUploadPreview && !files.length" class="inner-file-item inner-file-item--image">
        <button
          type="button"
          class="inner-file-thumb-btn"
          :disabled="uploading"
          aria-label="预览图片"
          @click="emit('preview-attachment', { name: pendingUploadPreview.name, url: pendingUploadPreview.url })"
        >
          <img :src="pendingUploadPreview.url" :alt="pendingUploadPreview.name" class="inner-file-thumb" />
        </button>
        <span class="inner-file-tag">图片</span>
        <Loader2 v-if="uploading" class="h-3.5 w-3.5 animate-spin inner-file-uploading" />
      </div>
      <div
        v-for="(file, index) in selectedMaterialAttachments"
        :key="`url-${file.id ?? file.url}`"
        class="inner-file-item"
        :class="{ 'inner-file-item--image': isImageAttachment(file.contentType, file.name) }"
      >
        <button
          v-if="isImageAttachment(file.contentType, file.name) && urlAttachmentPreviewUrl(file)"
          type="button"
          class="inner-file-thumb-btn"
          aria-label="预览图片"
          @click="
            emit('preview-attachment', {
              name: file.name,
              url: urlAttachmentPreviewUrl(file),
              contentType: file.contentType,
            })
          "
        >
          <img
            :src="urlAttachmentPreviewUrl(file)"
            :alt="file.name"
            class="inner-file-thumb"
            loading="lazy"
          />
        </button>
        <Paperclip v-else class="h-4 w-4 shrink-0" />
        <span v-if="file.refLabel || isImageAttachment(file.contentType, file.name)" class="inner-file-tag">{{ selectedUrlAttachmentLabel(file, index) }}</span>
        <template v-else>
          <span class="inner-file-name">{{ file.name }}</span>
          <span v-if="file.size" class="inner-file-size">{{ formatFileSize(file.size) }}</span>
        </template>
        <button
          type="button"
          class="inner-file-close"
          aria-label="移除素材"
          @click.stop="emit('remove-url-attachment', file)"
        >
          <X class="h-3 w-3" />
        </button>
      </div>
      <div
        v-for="(file, index) in files"
        :key="file.id"
        class="inner-file-item"
        :class="{ 'inner-file-item--image': isImageAttachment(file.contentType, file.originalFilename) }"
      >
        <button
          v-if="isImageAttachment(file.contentType, file.originalFilename) && (props.filePreviewUrls?.[file.id] || resolveAgentFileUrl(file.downloadUrl))"
          type="button"
          class="inner-file-thumb-btn"
          aria-label="预览图片"
          @click="
            emit('preview-attachment', {
              name: file.originalFilename,
              url: props.filePreviewUrls?.[file.id] || resolveAgentFileUrl(file.downloadUrl),
              contentType: file.contentType,
            })
          "
        >
          <img
            :src="props.filePreviewUrls?.[file.id] || resolveAgentFileUrl(file.downloadUrl)"
            :alt="file.originalFilename"
            class="inner-file-thumb"
            loading="lazy"
          />
        </button>
        <FileText v-else class="h-4 w-4 shrink-0" />
        <span v-if="isImageAttachment(file.contentType, file.originalFilename)" class="inner-file-tag">{{ selectedFileAttachmentLabel(file, selectedMaterialAttachments.length + index) }}</span>
        <template v-else>
          <span class="inner-file-name">{{ file.originalFilename }}</span>
          <span class="inner-file-size">{{ formatFileSize(file.fileSize) }}</span>
        </template>
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
        <div class="attachment-menu-host">
          <button
            type="button"
            class="tool-btn"
            :class="{ 'tool-btn--active': files.length > 0 || selectedMaterialAttachments.length > 0 || attachmentDialogOpen }"
            :disabled="uploading || sending || editingRegenerating || regeneratingMessageId != null || hasActiveRun"
            @click.stop="openAttachmentDialog()"
          >
            <Paperclip class="h-4 w-4 tool-icon" />
            素材
          </button>
        </div>
        <button
          type="button"
          class="tool-btn tool-btn--placeholder"
          aria-disabled="true"
          title="即将推出"
        >
          <Sparkles class="h-4 w-4 tool-icon" />
          深度思考
        </button>
        <div ref="toolMenuRef" class="attachment-menu-host">
          <button
            type="button"
            class="tool-btn"
            :class="{ 'tool-btn--active': toolMenuOpen || !!selectedToolCode }"
            :disabled="uploading || sending || editingRegenerating || regeneratingMessageId != null || hasActiveRun"
            @click.stop="toggleToolMenu"
          >
            <Wrench class="h-4 w-4 tool-icon" />
            {{ toolPickerButtonLabel() }}
          </button>
          <Transition :name="reducedMotion ? '' : 'attachment-menu-fade'">
            <div v-if="toolMenuOpen" class="attachment-menu-card tool-picker-menu" @click.stop>
              <div class="tool-picker-search">
                <Search class="h-4 w-4 text-white/45" />
                <input
                  v-model="toolSearch"
                  class="tool-picker-search-input"
                  placeholder="搜索工具"
                />
              </div>
              <div class="tool-picker-tabs">
                <button
                  v-for="tab in toolModalityTabs"
                  :key="tab.key"
                  type="button"
                  class="tool-picker-tab"
                  :class="{ 'tool-picker-tab--active': selectedToolModality === tab.key }"
                  @click="selectedToolModality = tab.key"
                >
                  {{ tab.label }} {{ tab.count }}
                </button>
              </div>
              <div v-if="agentToolsLoading" class="attachment-empty">工具列表加载中...</div>
              <div v-else-if="filteredAgentTools.length === 0" class="attachment-empty">暂无可用工具</div>
              <section v-else class="tool-picker-list">
                <button
                  v-for="tool in filteredAgentTools"
                  :key="tool.toolCode"
                  type="button"
                  class="tool-picker-item"
                  :class="{ 'tool-picker-item--active': selectedToolCode === tool.toolCode }"
                  @click="chooseTool(tool)"
                >
                  <span class="tool-picker-item-copy">
                    <strong>{{ tool.toolName }}</strong>
                    <small>{{ tool.description || tool.toolCode }}</small>
                  </span>
                  <Check v-if="selectedToolCode === tool.toolCode" class="h-4 w-4 shrink-0 text-primary" />
                </button>
              </section>
              <button
                v-if="selectedToolCode"
                type="button"
                class="tool-picker-clear"
                @click="clearSelectedTool"
              >
                清除选择
              </button>
            </div>
          </Transition>
        </div>
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

    <Teleport to="body">
      <Transition :name="reducedMotion ? '' : 'model-picker-fade'">
        <div
          v-if="attachmentDialogOpen"
          class="material-dialog-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="选择素材"
          @click.self="closeAttachmentDialog"
        >
          <section class="material-dialog">
            <header class="material-dialog-header">
              <div class="material-dialog-tabs" role="tablist" aria-label="素材来源">
                <button
                  type="button"
                  class="material-dialog-tab"
                  :class="{ active: attachmentDialogTab === 'upload' }"
                  @click="attachmentDialogTab = 'upload'"
                >
                  上传
                </button>
                <button
                  type="button"
                  class="material-dialog-tab"
                  :class="{ active: attachmentDialogTab === 'material' }"
                  @click="attachmentDialogTab = 'material'"
                >
                  素材
                </button>
              </div>
              <button type="button" class="material-dialog-close" aria-label="关闭" @click="closeAttachmentDialog">
                <X class="h-4 w-4" />
              </button>
            </header>

            <div v-if="attachmentDialogTab === 'upload'" class="material-dialog-body">
              <button
                type="button"
                class="material-upload-card"
                :class="{ 'material-upload-card--drop': materialUploadDropActive }"
                :disabled="uploading"
                @click="openFilePicker"
                @dragover.prevent="onMaterialUploadDragOver"
                @dragleave="onMaterialUploadDragLeave"
                @drop.prevent="onMaterialUploadDrop"
              >
                <Loader2 v-if="uploading" class="h-5 w-5 animate-spin" />
                <Upload v-else class="h-5 w-5" />
                <span>{{ uploading ? "上传中" : (materialUploadDropActive ? "松开上传素材" : "上传或拖拽图片/文件") }}</span>
              </button>

              <section class="material-section">
                <div class="material-section-title">
                  <Clock class="h-3.5 w-3.5" />
                  <span>最近上传</span>
                </div>
                <div v-if="recentMaterialAttachments.length === 0" class="attachment-empty">暂无最近素材</div>
                <div v-else class="material-grid">
                  <div
                    v-for="item in recentMaterialAttachments.slice(0, 12)"
                    :key="`recent-${item.id}`"
                    role="button"
                    tabindex="0"
                    class="material-tile"
                    :class="{ selected: isPickerSelected(item) }"
                    @click="togglePickerMaterial(item)"
                    @keydown.enter.prevent="togglePickerMaterial(item)"
                    @keydown.space.prevent="togglePickerMaterial(item)"
                  >
                    <img
                      v-if="item.kind === 'image' && item.previewUrl"
                      :src="item.previewUrl"
                      :alt="item.name"
                      class="material-tile-thumb"
                      loading="lazy"
                    />
                    <Image v-else-if="item.kind === 'image'" class="h-5 w-5" />
                    <Paperclip v-else class="h-5 w-5" />
                    <span class="material-tile-name">{{ item.name }}</span>
                    <span v-if="isPickerSelected(item)" class="material-tile-check">
                      <Check class="h-3.5 w-3.5" />
                    </span>
                    <button
                      type="button"
                      class="material-tile-delete"
                      aria-label="删除上传历史"
                      @click.stop="emit('remove-recent-attachment', item)"
                    >
                      <Trash2 class="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              </section>
            </div>

            <div v-else class="material-dialog-body">
              <div class="material-section-title">
                <Images class="h-3.5 w-3.5" />
                <span>素材库</span>
                <button type="button" class="attachment-refresh" @click.stop="emit('refresh-material-assets')">刷新</button>
              </div>
              <div v-if="materialAssetsLoading" class="attachment-empty">
                <Loader2 class="h-3.5 w-3.5 animate-spin" />
                加载中
              </div>
              <div v-else-if="libraryMaterialAttachments.length === 0" class="attachment-empty">暂无可复用素材</div>
              <div v-else class="material-grid material-grid--library">
                <button
                  v-for="item in libraryMaterialAttachments.slice(0, 30)"
                  :key="`library-${item.id}`"
                  type="button"
                  class="material-tile"
                  :class="{ selected: isPickerSelected(item) }"
                  @click="togglePickerMaterial(item)"
                >
                  <img
                    v-if="item.kind === 'image' && item.previewUrl"
                    :src="item.previewUrl"
                    :alt="item.name"
                    class="material-tile-thumb"
                    loading="lazy"
                  />
                  <Image v-else-if="item.kind === 'image'" class="h-5 w-5" />
                  <Paperclip v-else class="h-5 w-5" />
                  <span class="material-tile-name">{{ item.name }}</span>
                  <small class="material-tile-subtitle">{{ item.subtitle || materialKindLabel(item.kind) }}</small>
                  <span v-if="isPickerSelected(item)" class="material-tile-check">
                    <Check class="h-3.5 w-3.5" />
                  </span>
                </button>
              </div>
            </div>

            <footer class="material-dialog-footer">
              <span>{{ materialSelectedCount }} 个素材已选择</span>
              <button type="button" class="material-confirm-btn" @click="confirmPickerMaterials">
                确认选择
              </button>
            </footer>
          </section>
        </div>
      </Transition>
    </Teleport>
  </form>
</template>

<style scoped>
.composer {
  width: min(720px, calc(100% - 112px));
  margin: 0 auto;
  border: 1px solid rgb(255 255 255 / 0.105);
  border-radius: 28px;
  background: var(--agent-composer-bg);
  padding: 10px 12px 11px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
  position: relative;
  z-index: 2;
  box-shadow:
    0 -18px 56px var(--agent-accent-glow, rgb(176 92 255 / 0.10)),
    0 24px 72px rgb(0 0 0 / 0.52),
    0 0 0 1px color-mix(in srgb, var(--theme-color), transparent 86%),
    inset 0 1px 0 rgb(255 255 255 / 0.08);
  backdrop-filter: blur(24px) saturate(145%);
}

.composer::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
  background:
    linear-gradient(90deg, transparent 8%, color-mix(in srgb, var(--theme-color), transparent 76%), transparent 44%),
    radial-gradient(circle at 92% 16%, var(--agent-bg-mesh-2), transparent 24%);
  opacity: 0.72;
  mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  mask-composite: exclude;
  padding: 1px;
}

.composer-model-picker {
  position: relative;
  align-self: flex-start;
  max-width: min(520px, 100%);
}

.composer-model-select {
  display: none;
}

.composer-model-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: min(240px, 72vw);
  min-height: 30px;
  padding: 4px 12px 4px 6px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.9);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.composer-model-pill:hover:not(:disabled),
.composer-model-pill.open {
  background: rgb(255 255 255 / 0.085);
  border-color: color-mix(in srgb, var(--theme-color), transparent 62%);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--theme-color), transparent 86%);
}

.composer-model-pill:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.composer-model-pill-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.composer-model-pill :deep(.model-provider-icon) {
  width: 22px;
  height: 22px;
  border-radius: 6px;
}

.composer-model-pill :deep(.model-provider-icon-img) {
  width: 14px;
  height: 14px;
}

.model-picker-card {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  z-index: 30;
  display: grid;
  grid-template-columns: 148px minmax(240px, 1fr);
  width: min(520px, calc(100vw - 32px));
  max-height: min(360px, 52vh);
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 12px;
  background: rgb(30 32 36 / 0.98);
  box-shadow: 0 16px 48px rgb(0 0 0 / 0.45);
  backdrop-filter: blur(16px);
}

.model-picker-groups,
.model-picker-models {
  display: grid;
  align-content: start;
  gap: 4px;
  max-height: min(360px, 52vh);
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
  gap: 8px;
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

.model-detail-price {
  flex-shrink: 0;
  max-width: 108px;
  font-size: 11px;
  color: rgb(255 255 255 / 0.42);
  text-align: right;
  white-space: nowrap;
}

.model-picker-fade-enter-active,
.model-picker-fade-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.model-picker-fade-enter-from,
.model-picker-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
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
  overflow: hidden;
}

.model-provider-icon-img {
  width: 16px;
  height: 16px;
  object-fit: contain;
}

.model-provider-icon--blue { background: rgb(37 99 235 / 0.32); }
.model-provider-icon--cyan { background: rgb(8 145 178 / 0.34); }
.model-provider-icon--purple { background: rgb(124 58 237 / 0.34); }
.model-provider-icon--green { background: rgb(22 163 74 / 0.34); }
.model-provider-icon--rainbow { background: linear-gradient(135deg, #4285f4, #34a853 45%, #fbbc05 70%, #ea4335); }
.model-provider-icon--neutral { background: rgb(255 255 255 / 0.09); }
.model-provider-icon--relay { background: rgb(100 116 139 / 0.35); }

.model-provider-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-provider-icon-fallback {
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}

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
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  max-height: none;
  overflow: visible;
  padding-right: 0;
}

.inner-file-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  background: rgb(255 255 255 / 0.045);
  border: 1px solid rgb(255 255 255 / 0.06);
  border-radius: 14px;
  font-size: 12px;
}

.inner-file-item--image {
  padding: 6px 10px 6px 6px;
}

.inner-file-thumb-btn {
  display: block;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: zoom-in;
  border-radius: 10px;
  overflow: hidden;
}

.inner-file-thumb {
  width: 52px;
  height: 52px;
  object-fit: cover;
  border-radius: 10px;
  display: block;
}

.inner-file-tag {
  color: rgb(255 255 255 / 0.62);
  font-size: 12px;
}

.inner-file-uploading {
  color: var(--agent-accent);
}

.inner-file-name {
  color: rgb(255 255 255 / 0.84);
  font-weight: 500;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  min-height: 52px;
}

.chat-input {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 18px;
  line-height: 1.6;
  height: 52px;
  min-height: 52px;
  max-height: 52px;
  resize: none;
  padding: 8px 40px 6px 4px;
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
  height: min(360px, 44vh);
  min-height: 118px;
  max-height: min(360px, 44vh);
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
  gap: 12px;
}

.left-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.composer--drop-active {
  border-color: color-mix(in srgb, var(--theme-color) 55%, rgb(255 255 255 / 0.12));
  box-shadow:
    0 -18px 56px var(--agent-accent-glow, rgb(176 92 255 / 0.18)),
    0 24px 72px rgb(0 0 0 / 0.52),
    0 0 0 1px color-mix(in srgb, var(--theme-color), transparent 70%),
    inset 0 1px 0 rgb(255 255 255 / 0.08);
}

.tool-picker-menu {
  width: min(380px, calc(100vw - 32px));
}

.tool-picker-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  background: rgb(255 255 255 / 0.06);
  margin-bottom: 8px;
}

.tool-picker-search-input {
  min-width: 0;
  flex: 1;
  border: 0;
  background: transparent;
  color: #fff;
  font-size: 13px;
  outline: none;
}

.tool-picker-search-input::placeholder {
  color: rgb(255 255 255 / 0.35);
}

.tool-picker-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.tool-picker-tab {
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: transparent;
  color: rgb(255 255 255 / 0.55);
  font-size: 12px;
  padding: 4px 10px;
  cursor: pointer;
}

.tool-picker-tab--active {
  border-color: color-mix(in srgb, var(--theme-color) 60%, transparent);
  background: color-mix(in srgb, var(--theme-color) 16%, transparent);
  color: #fff;
}

.tool-picker-list {
  display: grid;
  gap: 4px;
  max-height: min(320px, 42vh);
  overflow-y: auto;
}

.tool-picker-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: rgb(255 255 255 / 0.82);
  padding: 10px;
  cursor: pointer;
  text-align: left;
}

.tool-picker-item:hover,
.tool-picker-item--active {
  background: rgb(255 255 255 / 0.07);
}

.tool-picker-item-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.tool-picker-item-copy strong {
  font-size: 13px;
  font-weight: 600;
}

.tool-picker-item-copy small {
  color: rgb(255 255 255 / 0.45);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-picker-clear {
  width: 100%;
  margin-top: 8px;
  border: 0;
  border-radius: 9px;
  background: rgb(255 255 255 / 0.05);
  color: rgb(255 255 255 / 0.62);
  font-size: 12px;
  padding: 8px 10px;
  cursor: pointer;
}

.attachment-menu-host {
  position: relative;
}

.attachment-menu-card {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  z-index: 35;
  width: min(360px, calc(100vw - 32px));
  max-height: min(520px, 62vh);
  overflow-y: auto;
  border: 1px solid rgb(255 255 255 / 0.09);
  border-radius: 14px;
  background: rgb(28 30 35 / 0.98);
  box-shadow: 0 18px 54px rgb(0 0 0 / 0.48);
  padding: 10px;
  backdrop-filter: blur(18px);
}

.attachment-action,
.attachment-choice {
  width: 100%;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: rgb(255 255 255 / 0.78);
  cursor: pointer;
  text-align: left;
}

.attachment-action {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  font-size: 13px;
  font-weight: 600;
}

.attachment-action:hover,
.attachment-choice:hover {
  background: rgb(255 255 255 / 0.07);
  color: #fff;
}

.attachment-section {
  display: grid;
  gap: 5px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgb(255 255 255 / 0.07);
}

.attachment-section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 4px 4px;
  color: rgb(255 255 255 / 0.52);
  font-size: 12px;
}

.attachment-refresh {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--agent-accent);
  font-size: 12px;
  cursor: pointer;
}

.attachment-empty {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px;
  color: rgb(255 255 255 / 0.38);
  font-size: 12px;
}

.attachment-choice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  min-height: 42px;
}

.attachment-choice-thumb {
  width: 34px;
  height: 34px;
  border-radius: 7px;
  object-fit: cover;
  flex-shrink: 0;
}

.attachment-choice-copy {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 2px;
}

.attachment-choice-copy strong,
.attachment-choice-copy small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-choice-copy strong {
  font-size: 12px;
  color: rgb(255 255 255 / 0.82);
}

.attachment-choice-copy small {
  font-size: 11px;
  color: rgb(255 255 255 / 0.42);
}

.attachment-choice-delete {
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.38);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
}

.attachment-choice-delete:hover {
  background: rgb(255 255 255 / 0.08);
  color: #fff;
}

.material-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(0 0 0 / 0.62);
  padding: 18px;
  backdrop-filter: blur(8px);
}

.material-dialog {
  width: min(640px, 100%);
  max-height: min(720px, calc(100vh - 36px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.10);
  border-radius: 20px;
  background: rgb(25 26 31 / 0.98);
  box-shadow: 0 24px 90px rgb(0 0 0 / 0.58);
}

.material-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 14px 10px;
  border-bottom: 1px solid rgb(255 255 255 / 0.07);
}

.material-dialog-tabs {
  position: relative;
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.055);
}

.material-dialog-tab {
  min-width: 84px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: rgb(255 255 255 / 0.54);
  font-size: 13px;
  font-weight: 600;
  padding: 8px 16px;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.material-dialog-tab.active {
  background: color-mix(in srgb, var(--theme-color) 22%, rgb(255 255 255 / 0.10));
  color: #fff;
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--theme-color), transparent 58%);
}

.material-dialog-close {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.56);
  cursor: pointer;
}

.material-dialog-close:hover {
  background: rgb(255 255 255 / 0.10);
  color: #fff;
}

.material-dialog-body {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 14px;
}

.material-upload-card {
  width: 100%;
  min-height: 108px;
  display: grid;
  place-items: center;
  gap: 8px;
  border: 1px dashed rgb(255 255 255 / 0.18);
  border-radius: 14px;
  background: rgb(255 255 255 / 0.045);
  color: rgb(255 255 255 / 0.78);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.material-upload-card:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--theme-color), transparent 45%);
  background: color-mix(in srgb, var(--theme-color) 12%, rgb(255 255 255 / 0.05));
}

.material-upload-card--drop {
  border-color: color-mix(in srgb, var(--theme-color), transparent 18%);
  background: color-mix(in srgb, var(--theme-color) 18%, rgb(255 255 255 / 0.06));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--theme-color), transparent 82%);
}

.material-section {
  margin-top: 16px;
}

.material-section-title {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 30px;
  color: rgb(255 255 255 / 0.62);
  font-size: 12px;
  font-weight: 600;
}

.material-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
  gap: 10px;
  margin-top: 8px;
}

.material-grid--library {
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
}

.material-tile {
  position: relative;
  min-width: 0;
  min-height: 130px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 7px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 12px;
  background: rgb(255 255 255 / 0.045);
  color: rgb(255 255 255 / 0.72);
  padding: 8px;
  cursor: pointer;
  text-align: left;
}

.material-tile:hover,
.material-tile.selected {
  border-color: color-mix(in srgb, var(--theme-color), transparent 42%);
  background: color-mix(in srgb, var(--theme-color) 13%, rgb(255 255 255 / 0.05));
}

.material-tile-thumb {
  width: 100%;
  aspect-ratio: 1 / 1;
  object-fit: cover;
  border-radius: 9px;
  background: rgb(0 0 0 / 0.22);
}

.material-tile-name,
.material-tile-subtitle {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.material-tile-name {
  color: rgb(255 255 255 / 0.82);
  font-size: 12px;
  font-weight: 600;
}

.material-tile-subtitle {
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
}

.material-tile-check {
  position: absolute;
  right: 8px;
  top: 8px;
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 50%;
  background: var(--theme-color);
  color: #fff;
  box-shadow: 0 8px 22px var(--agent-accent-glow);
}

.material-tile-delete {
  position: absolute;
  right: 8px;
  top: 8px;
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: rgb(0 0 0 / 0.48);
  color: rgb(255 255 255 / 0.70);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.16s ease, background 0.16s ease, color 0.16s ease;
}

.material-tile:hover .material-tile-delete,
.material-tile:focus-within .material-tile-delete {
  opacity: 1;
}

.material-tile.selected .material-tile-delete {
  right: 36px;
}

.material-tile-delete:hover {
  background: rgb(239 68 68 / 0.82);
  color: #fff;
}

.material-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-top: 1px solid rgb(255 255 255 / 0.08);
  color: rgb(255 255 255 / 0.52);
  font-size: 12px;
}

.material-confirm-btn {
  border: 0;
  border-radius: 999px;
  background: var(--theme-color);
  color: #fff;
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 12px 30px var(--agent-accent-glow);
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 6px 9px;
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
  color: var(--theme-color) !important;
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
    border-radius: 24px;
  }

  .tool-btn {
    min-width: 34px;
    padding: 7px;
  }

  .tool-btn:not(.tool-btn--active) {
    font-size: 0;
    gap: 0;
  }

  .material-dialog-backdrop {
    align-items: flex-end;
    padding: 12px;
  }

  .material-dialog {
    max-height: min(82vh, 680px);
    border-radius: 18px;
  }

  .material-dialog-tab {
    min-width: 72px;
    padding: 7px 13px;
  }

  .material-grid,
  .material-grid--library {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .material-tile-delete {
    opacity: 1;
  }
}
</style>
