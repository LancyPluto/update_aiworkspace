<script setup lang="ts">
import { computed, ref, watch } from "vue"
import type { Capability } from "@/api/aiToolTypes"
import type { TaskDetail, ToolField } from "@/api/types"
import { uploadChatFile } from "@/api/aiToolApi"
import { getApiOrigin } from "@/api/client"
import { fetchTasks } from "@/api/taskApi"
import { useAuthStore } from "@/store/authStore"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"
import {
  FileAudio,
  FileVideo,
  ImageIcon,
  ImageUp,
  BookMarked,
  Loader2,
  Mic,
  Paperclip,
  UploadCloud,
  X
} from "lucide-vue-next"

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
type MaterialKind = "image" | "video" | "audio" | "file"

interface MaterialAsset {
  id: string
  kind: MaterialKind
  url: string
  title: string
  subtitle: string
  previewUrl?: string
}

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
const materialPickerOpen = ref(false)
const materialPickerField = ref<ToolField | null>(null)
const materialLoading = ref(false)
const materialError = ref("")
const materialAssets = ref<MaterialAsset[]>([])

const configuredFields = computed(() => (props.fields || []).filter((field) => field.fieldKey !== props.coreFieldKey))
const imageCapability = computed(() => props.capabilities.find((c) => c.type === "imageGeneration"))
const fileCapability = computed(() => props.capabilities.find((c) => c.type === "fileReading"))
const webSearchCapability = computed(() => props.capabilities.find((c) => c.type === "webSearch"))
const codeCapability = computed(() => props.capabilities.find((c) => c.type === "codeExecution"))
const voiceCapability = computed(() => props.capabilities.find((c) => c.type === "voiceInput"))
const activeMaterialKind = computed(() => (materialPickerField.value ? materialKindForField(materialPickerField.value) : "file"))

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
  if (materialKindForField(field) !== "image") return ""
  return normalizeResourceUrl(strField(field.fieldKey))
}

function materialKindForField(field: ToolField): MaterialKind {
  if (field.fieldType === "image") return "image"
  const text = `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
  if (/image|img|picture|photo|frame|cover|avatar|poster|图片|图像|照片|帧|封面|首图/.test(text)) return "image"
  if (/audio|voice|sound|speech|music|音频|语音|声音|音乐/.test(text)) return "audio"
  if (/video|clip|movie|视频|短片|影片/.test(text)) return "video"
  return "file"
}

function materialKindLabel(kind: MaterialKind): string {
  if (kind === "image") return "图片"
  if (kind === "video") return "视频"
  if (kind === "audio") return "音频"
  return "素材"
}

function uploadAccept(field: ToolField): string | undefined {
  const kind = materialKindForField(field)
  if (kind === "image") return "image/*"
  if (kind === "video") return "video/*"
  if (kind === "audio") return "audio/*"
  return undefined
}

function formatTaskTime(value?: string | null): string {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
}

function createMaterialAssets(task: TaskDetail, targetKind: MaterialKind): MaterialAsset[] {
  const content = task.result?.contentText || ""
  if (!content.trim()) return []
  const blocks = buildTaskResultBlocks(content, task)
  const taskTitle = task.toolName || task.toolCode || "历史任务"
  const subtitle = `${task.taskNo || `#${task.taskId}`} · ${formatTaskTime(task.finishedAt || task.createdAt)}`
  const assets: MaterialAsset[] = []

  for (const block of blocks) {
    if (block.type === "image" && (targetKind === "image" || targetKind === "file")) {
      block.images.forEach((image, index) => {
        assets.push({
          id: `${task.taskId}-image-${index}`,
          kind: "image",
          url: image.url,
          previewUrl: image.url,
          title: image.label || taskTitle,
          subtitle,
        })
      })
    } else if (block.type === "video" && (targetKind === "video" || targetKind === "file")) {
      assets.push({
        id: `${task.taskId}-video`,
        kind: "video",
        url: block.url,
        title: block.title || taskTitle,
        subtitle,
      })
    } else if (block.type === "audio" && (targetKind === "audio" || targetKind === "file")) {
      assets.push({
        id: `${task.taskId}-audio`,
        kind: "audio",
        url: block.url,
        title: block.title || taskTitle,
        subtitle,
      })
    }
  }

  return assets
}

async function openMaterialPicker(field: ToolField) {
  materialPickerField.value = field
  materialPickerOpen.value = true
  materialLoading.value = true
  materialError.value = ""
  materialAssets.value = []
  const targetKind = materialKindForField(field)
  try {
    const page = await fetchTasks({
      token: auth.token,
      query: { pageNo: 1, pageSize: 80, status: "SUCCESS" },
    })
    const seen = new Set<string>()
    const assets = page.list.flatMap((task) => createMaterialAssets(task, targetKind)).filter((asset) => {
      const key = `${asset.kind}:${asset.url}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    materialAssets.value = assets
  } catch (err) {
    materialError.value = (err as Error).message || "素材加载失败"
  } finally {
    materialLoading.value = false
  }
}

function closeMaterialPicker() {
  materialPickerOpen.value = false
  materialPickerField.value = null
}

function selectMaterialAsset(asset: MaterialAsset) {
  const field = materialPickerField.value
  if (!field) return
  setField(field.fieldKey, asset.url)
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: false, fileName: asset.title },
  }
  closeMaterialPicker()
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
  <div v-if="configuredFields.length > 0 || capabilities.length > 0" class="mt-3 space-y-3">
    <!-- 自定义字段区域 -->
    <div class="flex flex-wrap items-start gap-2">
      <div v-for="field in configuredFields" :key="field.fieldKey" class="min-w-[100px] max-w-[180px]">
        <label class="mb-1.5 block text-[11px] font-medium text-muted-foreground">
          {{ field.fieldName }}<span v-if="field.required" class="text-destructive"> *</span>
        </label>

        <!-- 下拉/单选框 -->
        <select
          v-if="(field.fieldType === 'select' || field.fieldType === 'radio') && fieldOptions(field).length"
          :value="strField(field.fieldKey)"
          class="h-8 w-full rounded-lg border border-border/70 bg-background px-2 text-xs transition-all hover:border-primary/40 focus:border-primary focus:ring-1 focus:ring-primary/20 outline-none"
          @change="setField(field.fieldKey, ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="option in fieldOptions(field)" :key="optionValue(option)" :value="optionValue(option)">
            {{ optionLabel(option) }}
          </option>
        </select>

        <!-- 数字输入框 -->
        <input
          v-else-if="field.fieldType === 'number' || field.fieldType === 'slider'"
          type="number"
          :value="strField(field.fieldKey)"
          :placeholder="field.placeholder || ''"
          class="h-8 w-full rounded-lg border border-border/70 bg-background px-2 text-xs transition-all hover:border-primary/40 focus:border-primary focus:ring-1 focus:ring-primary/20 outline-none"
          @input="onNumberInput(field.fieldKey, $event)"
        />

        <!-- 复选框 -->
        <label
          v-else-if="field.fieldType === 'checkbox'"
          class="inline-flex h-8 items-center gap-2 rounded-lg border border-border/70 bg-background px-2 text-xs transition-all hover:border-primary/40 cursor-pointer"
        >
          <input
            type="checkbox"
            :checked="Boolean(state.fields[field.fieldKey])"
            class="rounded border-border accent-primary"
            @change="setField(field.fieldKey, ($event.target as HTMLInputElement).checked)"
          />
          {{ field.placeholder || "启用" }}
        </label>

        <!-- 图片/文件上传区域 -->
        <div v-else-if="field.fieldType === 'image' || field.fieldType === 'file'" class="space-y-1.5">
          <div
            class="flex items-center gap-1.5"
            @dragover.prevent
            @drop.prevent="handleFieldUpload(field, ($event as DragEvent).dataTransfer?.files || null)"
          >

          <div
            class="flex h-8 min-w-0 items-center justify-center gap-2 rounded-lg border border-dashed border-border/70 bg-background px-3 text-xs text-muted-foreground transition-all hover:border-primary/50 hover:bg-primary/5"
          >
            <input
              type="file"
              :id="`file-${field.fieldKey}`"
              class="hidden"
              :accept="uploadAccept(field)"
              @change="handleFieldUpload(field, ($event.target as HTMLInputElement).files)"
            />

            <label
              :for="`file-${field.fieldKey}`"
              class="flex cursor-pointer items-center gap-1.5 text-muted-foreground transition-colors hover:text-primary"
              title="从本地上传"
            >
              <Loader2 v-if="uploadState(field.fieldKey).uploading" class="h-4 w-4 animate-spin" />
              <ImageUp v-else class="h-4 w-4" />
              <span>本地</span>
            </label>

            <span class="text-muted-foreground">|</span>

            <button
              type="button"
              class="flex items-center gap-1.5 text-muted-foreground transition-colors hover:text-primary"
              title="从素材库上传"
              @click="openMaterialPicker(field)"
            >
              <BookMarked class="h-4 w-4" />
              <span>素材库</span>
            </button>
          </div>
          </div>

          <!-- 已上传预览 -->
          <div v-if="strField(field.fieldKey)" class="flex items-center gap-1.5">
            <img
              v-if="imagePreviewUrl(field)"
              :src="imagePreviewUrl(field)"
              alt=""
              class="h-8 w-8 shrink-0 rounded-md border border-border object-cover shadow-sm"
            />
            
            <button type="button" class="p-1 rounded-md text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive" @click="clearUploadedField(field)">
              <X class="h-4 w-4" />
            </button>
          </div>

          <p v-if="uploadState(field.fieldKey).error" class="text-[11px] text-destructive pl-0.5">
            {{ uploadState(field.fieldKey).error }}
          </p>
        </div>

        <!-- 普通文本输入框 -->
        <input
          v-else
          type="text"
          :value="strField(field.fieldKey)"
          :placeholder="field.placeholder || ''"
          class="h-8 w-full rounded-lg border border-border/70 bg-background px-2 text-xs transition-all hover:border-primary/40 focus:border-primary focus:ring-1 focus:ring-primary/20 outline-none"
          @input="setField(field.fieldKey, ($event.target as HTMLInputElement).value)"
        />
      </div>
    </div>

    <!-- 能力快捷选项区域 -->
    <div class="flex flex-wrap items-center gap-2">
      <select
        v-if="imageCapability && configuredFields.length === 0"
        v-model="state.imageRatio"
        class="h-8 rounded-lg border border-border/70 bg-background px-2 text-xs transition-all hover:border-primary/40 focus:border-primary focus:ring-1 focus:ring-primary/20 outline-none"
        title="图片比例"
      >
        <option v-for="ratio in aspectRatios" :key="ratio" :value="ratio">{{ ratio }}</option>
      </select>

      <label
        v-if="fileCapability"
        class="inline-flex h-8 items-center gap-1.5 rounded-lg border border-border/70 bg-background px-2 text-xs text-muted-foreground transition-all hover:border-primary/40"
      >
        <Paperclip class="h-4 w-4" />
        上传
      </label>

      <label v-if="webSearchCapability && showWebSearch" class="inline-flex h-8 cursor-pointer items-center gap-2 rounded-lg border border-border/70 bg-background px-2 text-xs transition-all hover:border-primary/40">
        <input v-model="state.webSearch" type="checkbox" class="rounded border-border accent-primary" />
        联网搜索
      </label>

      <select
        v-if="codeCapability && codeLanguages.length > 1"
        v-model="state.language"
        class="h-8 rounded-lg border border-border/70 bg-background px-2 text-xs transition-all hover:border-primary/40 focus:border-primary focus:ring-1 focus:ring-primary/20 outline-none"
        title="代码语言"
      >
        <option v-for="lang in codeLanguages" :key="lang" :value="lang">{{ lang }}</option>
      </select>

      <button
        v-if="voiceCapability"
        type="button"
        class="inline-flex h-8 items-center gap-1.5 rounded-lg border border-border/70 bg-background px-2 text-xs text-muted-foreground transition-all hover:border-primary/40"
        disabled
      >
        <Mic class="h-4 w-4" />
        语音
      </button>
    </div>

    <!-- 素材选择弹窗 -->
    <div
      v-if="materialPickerOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm px-4 py-6 transition-opacity duration-200"
      @click.self="closeMaterialPicker"
    >
      <div class="flex max-h-[78vh] w-full max-w-3xl flex-col rounded-2xl border border-border bg-background shadow-xl transition-transform duration-200 scale-100">
        <!-- 弹窗头部 -->
        <div class="flex items-center justify-between border-b border-border/60 px-5 py-4">
          <div>
            <h3 class="text-base font-semibold text-foreground">
              选择{{ materialKindLabel(activeMaterialKind) }}素材
            </h3>
            <p class="mt-1 text-xs text-muted-foreground">
              来自你已生成成功的历史任务，选择后会填入当前上传字段。
            </p>
          </div>
          <button
            type="button"
            class="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            @click="closeMaterialPicker"
          >
            <X class="h-4 w-4" />
          </button>
        </div>

        <!-- 弹窗内容区 -->
        <div class="min-h-[220px] overflow-y-auto p-5">
          <div v-if="materialLoading" class="flex h-48 items-center justify-center gap-2 text-sm text-muted-foreground">
            <Loader2 class="h-4 w-4 animate-spin" />
            正在加载素材库...
          </div>
          <div v-else-if="materialError" class="flex h-48 items-center justify-center text-sm text-destructive">
            {{ materialError }}
          </div>
          <div v-else-if="materialAssets.length === 0" class="flex h-48 items-center justify-center text-sm text-muted-foreground">
            暂无可用{{ materialKindLabel(activeMaterialKind) }}素材
          </div>
          <!-- 素材卡片网格 -->
          <div v-else class="grid grid-cols-2 gap-3 md:grid-cols-3">
            <button
              v-for="asset in materialAssets"
              :key="asset.id"
              type="button"
              class="group overflow-hidden rounded-xl border border-border/70 bg-card text-left transition-all duration-200 hover:border-primary/50 hover:shadow-md hover:-translate-y-0.5 active:scale-[0.98]"
              @click="selectMaterialAsset(asset)"
            >
              <div class="flex aspect-[4/3] items-center justify-center bg-muted/30">
                <img
                  v-if="asset.kind === 'image' && asset.previewUrl"
                  :src="asset.previewUrl"
                  alt=""
                  class="h-full w-full object-cover"
                />
                <FileVideo v-else-if="asset.kind === 'video'" class="h-10 w-10 text-muted-foreground group-hover:text-primary transition-colors" />
                <FileAudio v-else-if="asset.kind === 'audio'" class="h-10 w-10 text-muted-foreground group-hover:text-primary transition-colors" />
                <ImageIcon v-else class="h-10 w-10 text-muted-foreground group-hover:text-primary transition-colors" />
              </div>
              <div class="space-y-1 p-3">
                
                <p class="truncate text-xs text-muted-foreground">{{ asset.subtitle }}</p>
              </div>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>