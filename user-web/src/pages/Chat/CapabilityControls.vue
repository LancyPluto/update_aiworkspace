<script setup lang="ts">
import { computed, ref, watch } from "vue"
import type { Capability } from "@/api/aiToolTypes"
import { Paperclip, Mic, X, Loader2 } from "lucide-vue-next"

export interface PendingAttachment {
  localId: string
  file: File
  name: string
  size: number
  type: string
  fileId?: string
  uploading?: boolean
  error?: string
}

export interface CapabilityState {
  imageRatio?: string
  webSearch?: boolean
  language?: string
  attachments: PendingAttachment[]
}

const props = defineProps<{
  capabilities: Capability[]
}>()

const emit = defineEmits<{
  upload: [file: File, localId: string]
}>()

const state = ref<CapabilityState>({
  attachments: [],
})

function resetState() {
  state.value = buildDefaultState(props.capabilities)
}

function buildDefaultState(capabilities: Capability[]): CapabilityState {
  const next: CapabilityState = { attachments: [] }
  for (const cap of capabilities) {
    switch (cap.type) {
      case "imageGeneration":
        next.imageRatio =
          typeof cap.config.defaultRatio === "string"
            ? cap.config.defaultRatio
            : Array.isArray(cap.config.aspectRatios) && cap.config.aspectRatios.length > 0
              ? String(cap.config.aspectRatios[0])
              : "1:1"
        break
      case "webSearch":
        next.webSearch = cap.config.defaultEnabled === true
        break
      case "codeExecution":
        next.language =
          Array.isArray(cap.config.supportedLanguages) && cap.config.supportedLanguages.length > 0
            ? String(cap.config.supportedLanguages[0])
            : "python"
        break
    }
  }
  return next
}

watch(
  () => props.capabilities,
  () => resetState(),
  { immediate: true, deep: true },
)

const imageCapability = computed(() => props.capabilities.find((c) => c.type === "imageGeneration"))
const fileCapability = computed(() => props.capabilities.find((c) => c.type === "fileReading"))
const webSearchCapability = computed(() => props.capabilities.find((c) => c.type === "webSearch"))
const codeCapability = computed(() => props.capabilities.find((c) => c.type === "codeExecution"))
const voiceCapability = computed(() => props.capabilities.find((c) => c.type === "voiceInput"))

const aspectRatios = computed(() => {
  const config = imageCapability.value?.config
  if (Array.isArray(config?.aspectRatios) && config.aspectRatios.length > 0) {
    return config.aspectRatios.map(String)
  }
  return ["1:1", "16:9", "9:16"]
})

const supportedFileTypes = computed(() => {
  const config = fileCapability.value?.config
  if (Array.isArray(config?.supportedFileTypes)) {
    return config.supportedFileTypes.map(String)
  }
  return ["pdf", "txt", "png"]
})

const maxSizeMB = computed(() => {
  const value = fileCapability.value?.config?.maxSizeMB
  return typeof value === "number" ? value : 20
})

const codeLanguages = computed(() => {
  const config = codeCapability.value?.config
  if (Array.isArray(config?.supportedLanguages) && config.supportedLanguages.length > 0) {
    return config.supportedLanguages.map(String)
  }
  return ["python", "javascript"]
})

const showWebSearch = computed(() => webSearchCapability.value?.config?.enabled !== false)

function acceptFileTypes(): string {
  return supportedFileTypes.value.map((ext) => `.${ext}`).join(",")
}

function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ""
  if (!file) return

  const ext = file.name.split(".").pop()?.toLowerCase() || ""
  if (!supportedFileTypes.value.includes(ext)) {
    alert(`不支持 .${ext} 文件`)
    return
  }
  if (file.size > maxSizeMB.value * 1024 * 1024) {
    alert(`文件不能超过 ${maxSizeMB.value}MB`)
    return
  }

  const localId = `local-${Date.now()}-${Math.random().toString(36).slice(2)}`
  state.value.attachments.push({
    localId,
    file,
    name: file.name,
    size: file.size,
    type: file.type,
    uploading: true,
  })
  emit("upload", file, localId)
}

function markUploadSuccess(localId: string, fileId: string) {
  const item = state.value.attachments.find((a) => a.localId === localId)
  if (item) {
    item.fileId = fileId
    item.uploading = false
    item.error = undefined
  }
}

function markUploadError(localId: string, message: string) {
  const item = state.value.attachments.find((a) => a.localId === localId)
  if (item) {
    item.uploading = false
    item.error = message
  }
}

function removeAttachment(localId: string) {
  state.value.attachments = state.value.attachments.filter((a) => a.localId !== localId)
}

function getRequestParams(): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  if (imageCapability.value && state.value.imageRatio) {
    params.imageRatio = state.value.imageRatio
  }
  if (webSearchCapability.value && showWebSearch.value) {
    params.webSearch = state.value.webSearch === true
  }
  if (codeCapability.value && state.value.language) {
    params.language = state.value.language
  }
  return params
}

function getAttachmentIds(): string[] {
  return state.value.attachments
    .filter((a) => a.fileId && !a.error)
    .map((a) => a.fileId as string)
}

function hasPendingUploads(): boolean {
  return state.value.attachments.some((a) => a.uploading)
}

defineExpose({
  resetState,
  getRequestParams,
  getAttachmentIds,
  markUploadSuccess,
  markUploadError,
  hasPendingUploads,
})
</script>

<template>
  <div v-if="capabilities.length > 0" class="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2">
    <div v-if="imageCapability" class="flex items-center gap-2 text-xs">
      <span class="text-muted-foreground">图片比例：</span>
      <select
        v-model="state.imageRatio"
        class="h-8 rounded-md border border-border bg-background px-2 text-xs"
      >
        <option v-for="ratio in aspectRatios" :key="ratio" :value="ratio">{{ ratio }}</option>
      </select>
    </div>

    <div v-if="fileCapability" class="flex items-center gap-2">
      <label class="inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-border px-2.5 py-1.5 text-xs hover:bg-secondary">
        <Paperclip class="h-3.5 w-3.5" />
        上传文件
        <input type="file" class="hidden" :accept="acceptFileTypes()" @change="handleFileSelect" />
      </label>
    </div>

    <div v-if="webSearchCapability && showWebSearch" class="flex items-center gap-2 text-xs">
      <label class="inline-flex cursor-pointer items-center gap-2">
        <input v-model="state.webSearch" type="checkbox" class="rounded border-border" />
        联网搜索
      </label>
    </div>

    <div v-if="codeCapability && codeLanguages.length > 1" class="flex items-center gap-2 text-xs">
      <span class="text-muted-foreground">语言：</span>
      <select
        v-model="state.language"
        class="h-8 rounded-md border border-border bg-background px-2 text-xs"
      >
        <option v-for="lang in codeLanguages" :key="lang" :value="lang">{{ lang }}</option>
      </select>
    </div>

    <button
      v-if="voiceCapability"
      type="button"
      class="inline-flex items-center gap-1.5 rounded-md border border-border px-2.5 py-1.5 text-xs text-muted-foreground"
      title="语音输入（需浏览器支持）"
      disabled
    >
      <Mic class="h-3.5 w-3.5" />
      语音
    </button>
  </div>

  <div v-if="state.attachments.length > 0" class="flex flex-wrap gap-2 border-b border-border px-4 py-2">
    <div
      v-for="item in state.attachments"
      :key="item.localId"
      class="inline-flex items-center gap-1.5 rounded-md border border-border bg-secondary/40 px-2 py-1 text-xs"
      :class="item.error ? 'border-destructive/50 text-destructive' : ''"
    >
      <Loader2 v-if="item.uploading" class="h-3 w-3 animate-spin" />
      <span class="max-w-[160px] truncate">{{ item.name }}</span>
      <button type="button" class="text-muted-foreground hover:text-foreground" @click="removeAttachment(item.localId)">
        <X class="h-3 w-3" />
      </button>
    </div>
  </div>
</template>
