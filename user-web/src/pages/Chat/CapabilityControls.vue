<script setup lang="ts">
import { computed, ref, watch } from "vue"
import type { Capability } from "@/api/aiToolTypes"
import type { ToolField } from "@/api/types"
import { uploadChatFile } from "@/api/aiToolApi"
import { getApiOrigin } from "@/api/client"
import { useAuthStore } from "@/store/authStore"
import { ImageUp, Loader2, Mic, Paperclip, UploadCloud, X } from "lucide-vue-next"

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
  fields: Record<string, unknown>
}

type FieldOption = string | { label: string; value: string; promptPrefix?: string }

const props = defineProps<{
  capabilities: Capability[]
  fields?: ToolField[]
  coreFieldKey?: string | null
  toolId?: string | null
}>()

const state = ref<CapabilityState>({
  attachments: [],
  fields: {},
})
const auth = useAuthStore()
const fieldUploads = ref<Record<string, { uploading?: boolean; error?: string; fileName?: string }>>({})

const configuredFields = computed(() => (props.fields || []).filter((field) => field.fieldKey !== props.coreFieldKey))
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

const codeLanguages = computed(() => {
  const config = codeCapability.value?.config
  if (Array.isArray(config?.supportedLanguages) && config.supportedLanguages.length > 0) {
    return config.supportedLanguages.map(String)
  }
  return ["python", "javascript"]
})

const showWebSearch = computed(() => webSearchCapability.value?.config?.enabled !== false)

function optionLabel(option: FieldOption): string {
  return typeof option === "string" ? option : option.label
}

function optionValue(option: FieldOption): string {
  return typeof option === "string" ? option : option.value
}

function fieldOptions(field: ToolField): FieldOption[] {
  if (Array.isArray(field.options)) return field.options
  if (!field.optionsJson) return []
  try {
    const parsed = JSON.parse(field.optionsJson) as unknown
    const rows = Array.isArray(parsed)
      ? parsed
      : parsed && typeof parsed === "object" && Array.isArray((parsed as { options?: unknown }).options)
        ? (parsed as { options: unknown[] }).options
        : []
    return rows.filter((item): item is FieldOption => typeof item === "string" || Boolean(item && typeof item === "object"))
  } catch {
    return []
  }
}

function defaultFieldValue(field: ToolField): unknown {
  const options = fieldOptions(field)
  if ((field.fieldType === "select" || field.fieldType === "radio") && options.length) return optionValue(options[0])
  if (field.fieldType === "checkbox") return false
  if (field.fieldType === "slider") return 50
  return ""
}

function buildDefaultState(): CapabilityState {
  const next: CapabilityState = { attachments: [], fields: {} }
  if (imageCapability.value) {
    next.imageRatio =
      typeof imageCapability.value.config.defaultRatio === "string"
        ? imageCapability.value.config.defaultRatio
        : aspectRatios.value[0]
  }
  if (webSearchCapability.value) next.webSearch = webSearchCapability.value.config.defaultEnabled === true
  if (codeCapability.value) next.language = codeLanguages.value[0] || "python"
  for (const field of configuredFields.value) {
    next.fields[field.fieldKey] = defaultFieldValue(field)
  }
  return next
}

function resetState() {
  state.value = buildDefaultState()
  fieldUploads.value = {}
}

watch(
  () => [props.capabilities, props.fields, props.coreFieldKey],
  () => resetState(),
  { immediate: true, deep: true },
)

function strField(key: string): string {
  const value = state.value.fields[key]
  return value === undefined || value === null ? "" : String(value)
}

function setField(key: string, value: unknown) {
  state.value.fields = { ...state.value.fields, [key]: value }
}

function uploadState(key: string) {
  return fieldUploads.value[key] || {}
}

function normalizeResourceUrl(value: string): string {
  const raw = value.trim()
  if (!raw || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function imagePreviewUrl(field: ToolField): string {
  if (field.fieldType !== "image") return ""
  return normalizeResourceUrl(strField(field.fieldKey))
}

async function handleFieldUpload(field: ToolField, files: FileList | File[] | null) {
  const file = files?.[0]
  if (!file) return
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: true, fileName: file.name },
  }
  try {
    const result = await uploadChatFile(file, { token: auth.token, toolId: props.toolId })
    setField(field.fieldKey, result.url)
    fieldUploads.value = {
      ...fieldUploads.value,
      [field.fieldKey]: { uploading: false, fileName: file.name },
    }
  } catch (err) {
    fieldUploads.value = {
      ...fieldUploads.value,
      [field.fieldKey]: {
        uploading: false,
        fileName: file.name,
        error: (err as Error).message || "上传失败",
      },
    }
  }
}

function clearUploadedField(field: ToolField) {
  setField(field.fieldKey, "")
  const next = { ...fieldUploads.value }
  delete next[field.fieldKey]
  fieldUploads.value = next
}

function onNumberInput(key: string, event: Event) {
  const value = (event.target as HTMLInputElement).value
  setField(key, value === "" ? "" : Number(value))
}

function validate(): { valid: boolean; message?: string } {
  for (const field of configuredFields.value) {
    if (!field.required) continue
    const value = state.value.fields[field.fieldKey]
    if (value === undefined || value === null || String(value).trim() === "") {
      return { valid: false, message: `请填写：${field.fieldName}` }
    }
  }
  return { valid: true }
}

function getRequestParams(): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  if (imageCapability.value && state.value.imageRatio) params.imageRatio = state.value.imageRatio
  if (webSearchCapability.value && showWebSearch.value) params.webSearch = state.value.webSearch === true
  if (codeCapability.value && state.value.language) params.language = state.value.language

  for (const field of configuredFields.value) {
    const value = state.value.fields[field.fieldKey]
    if (field.fieldType === "checkbox") {
      params[field.fieldKey] = Boolean(value)
    } else if (field.fieldType === "number" || field.fieldType === "slider") {
      if (value !== "" && value !== undefined && value !== null && !Number.isNaN(Number(value))) {
        params[field.fieldKey] = Number(value)
      }
    } else if (value !== "" && value !== undefined && value !== null) {
      params[field.fieldKey] = typeof value === "string" ? value.trim() : value
    }
  }
  return params
}

function getAttachmentIds(): string[] {
  return state.value.attachments.filter((a) => a.fileId && !a.error).map((a) => a.fileId as string)
}

function hasPendingUploads(): boolean {
  return state.value.attachments.some((a) => a.uploading) || Object.values(fieldUploads.value).some((item) => item.uploading)
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

defineExpose({
  resetState,
  validate,
  getRequestParams,
  getAttachmentIds,
  markUploadSuccess,
  markUploadError,
  hasPendingUploads,
})
</script>

<template>
  <div v-if="configuredFields.length > 0 || capabilities.length > 0" class="mt-2 space-y-2">
    <div class="flex flex-wrap items-center gap-1.5">
      <div v-for="field in configuredFields" :key="field.fieldKey" class="min-w-[100px] max-w-[180px]">
        <label class="mb-1 block text-[11px] font-medium text-muted-foreground">
          {{ field.fieldName }}<span v-if="field.required" class="text-destructive"> *</span>
        </label>

        <select
          v-if="(field.fieldType === 'select' || field.fieldType === 'radio') && fieldOptions(field).length"
          :value="strField(field.fieldKey)"
          class="h-7 w-full rounded-lg border border-border/60 bg-background px-2 text-xs"
          @change="setField(field.fieldKey, ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="option in fieldOptions(field)" :key="optionValue(option)" :value="optionValue(option)">
            {{ optionLabel(option) }}
          </option>
        </select>

        <input
          v-else-if="field.fieldType === 'number' || field.fieldType === 'slider'"
          type="number"
          :value="strField(field.fieldKey)"
          :placeholder="field.placeholder || ''"
          class="h-7 w-full rounded-lg border border-border/60 bg-background px-2 text-xs"
          @input="onNumberInput(field.fieldKey, $event)"
        />

        <label
          v-else-if="field.fieldType === 'checkbox'"
          class="inline-flex h-7 items-center gap-2 rounded-lg border border-border/60 bg-background px-2 text-xs"
        >
          <input
            type="checkbox"
            :checked="Boolean(state.fields[field.fieldKey])"
            class="rounded border-border"
            @change="setField(field.fieldKey, ($event.target as HTMLInputElement).checked)"
          />
          {{ field.placeholder || "启用" }}
        </label>

        <div v-else-if="field.fieldType === 'image' || field.fieldType === 'file'" class="space-y-1">
          <label
            class="flex h-7 cursor-pointer items-center gap-2 rounded-lg border border-dashed border-border/60 bg-background px-2 text-xs text-muted-foreground hover:border-primary/60"
            @dragover.prevent
            @drop.prevent="handleFieldUpload(field, ($event as DragEvent).dataTransfer?.files || null)"
          >
            <input
              type="file"
              class="hidden"
              :accept="field.fieldType === 'image' ? 'image/*' : undefined"
              @change="handleFieldUpload(field, ($event.target as HTMLInputElement).files)"
            />
            <Loader2 v-if="uploadState(field.fieldKey).uploading" class="h-3.5 w-3.5 animate-spin" />
            <ImageUp v-else-if="field.fieldType === 'image'" class="h-3.5 w-3.5" />
            <UploadCloud v-else class="h-3.5 w-3.5" />
            <span class="truncate text-xs">
              {{ uploadState(field.fieldKey).uploading ? "上传中..." : (uploadState(field.fieldKey).fileName || "上传文件") }}
            </span>
          </label>
          <div v-if="strField(field.fieldKey)" class="flex items-center gap-1">
            <img
              v-if="imagePreviewUrl(field)"
              :src="imagePreviewUrl(field)"
              alt=""
              class="h-7 w-7 shrink-0 rounded-md border border-border object-cover"
            />
            <input
              :value="strField(field.fieldKey)"
              class="h-6 flex-1 rounded-md border border-border/60 bg-muted/30 px-2 text-[11px]"
              readonly
            />
            <button type="button" class="text-muted-foreground hover:text-foreground" @click="clearUploadedField(field)">
              <X class="h-3.5 w-3.5" />
            </button>
          </div>
          <p v-if="uploadState(field.fieldKey).error" class="text-[11px] text-destructive">
            {{ uploadState(field.fieldKey).error }}
          </p>
        </div>

        <input
          v-else
          type="text"
          :value="strField(field.fieldKey)"
          :placeholder="field.placeholder || ''"
          class="h-7 w-full rounded-lg border border-border/60 bg-background px-2 text-xs"
          @input="setField(field.fieldKey, ($event.target as HTMLInputElement).value)"
        />
      </div>
    </div>

    <div class="flex flex-wrap items-center gap-1.5">
      <select
        v-if="imageCapability && configuredFields.length === 0"
        v-model="state.imageRatio"
        class="h-7 rounded-lg border border-border/60 bg-background px-2 text-xs"
        title="图片比例"
      >
        <option v-for="ratio in aspectRatios" :key="ratio" :value="ratio">{{ ratio }}</option>
      </select>

      <label
        v-if="fileCapability"
        class="inline-flex h-7 items-center gap-1.5 rounded-lg border border-border/60 bg-background px-2 text-xs text-muted-foreground"
      >
        <Paperclip class="h-3.5 w-3.5" />
        上传
      </label>

      <label v-if="webSearchCapability && showWebSearch" class="inline-flex h-7 cursor-pointer items-center gap-2 rounded-lg border border-border/60 bg-background px-2 text-xs">
        <input v-model="state.webSearch" type="checkbox" class="rounded border-border" />
        联网搜索
      </label>

      <select
        v-if="codeCapability && codeLanguages.length > 1"
        v-model="state.language"
        class="h-7 rounded-lg border border-border/60 bg-background px-2 text-xs"
        title="代码语言"
      >
        <option v-for="lang in codeLanguages" :key="lang" :value="lang">{{ lang }}</option>
      </select>

      <button
        v-if="voiceCapability"
        type="button"
        class="inline-flex h-7 items-center gap-1.5 rounded-lg border border-border/60 bg-background px-2 text-xs text-muted-foreground"
        disabled
      >
        <Mic class="h-3.5 w-3.5" />
        语音
      </button>
    </div>
  </div>
</template>