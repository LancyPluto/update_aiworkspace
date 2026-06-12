<script setup lang="ts">
import { computed, ref, watch } from "vue"
import type { Capability } from "@/api/aiToolTypes"
import type { TaskDetail, ToolField, UserUploadAsset } from "@/api/types"
import { deleteUploadAsset, fetchUploadAssets, uploadToolFile } from "@/api/toolApi"
import { getApiOrigin } from "@/api/client"
import { fetchTasks } from "@/api/taskApi"
import { useAuthStore } from "@/store/authStore"
import { buildTaskResultBlocks, resolveAudioTracks } from "@/utils/taskResultBlocks"
import {
  defaultFieldValue as resolveDefaultFieldValue,
  fieldOptionsFromMeta,
  isFieldVisible,
  parseFieldMeta,
  resolveMaxLength,
} from "@/utils/fieldUiMeta"
import { Check, Clock, FileAudio, FileVideo, ImageIcon, ImageUp, Loader2, Mic, Paperclip, Plus, UploadCloud, X } from "lucide-vue-next"
import { BookOpen } from 'lucide-vue-next'

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
type AspectRatioOption = { label: string; value: string }
type MaterialKind = "image" | "video" | "audio" | "file"

interface MaterialAsset {
  id: string
  kind: MaterialKind
  url: string
  title: string
  subtitle: string
  previewUrl?: string
}

interface UploadHistoryItem {
  id: string
  assetId?: number
  kind: MaterialKind
  url: string
  name: string
  size?: number
  type?: string
  uploadedAt: string
  toolId?: string | null
}

export interface PrimaryReferenceMaterialInfo {
  available: boolean
  fieldName: string
  kind: MaterialKind
  count: number
  maxCount: number
  previewUrls: string[]
  uploading: boolean
  error?: string
}

const props = defineProps<{
  capabilities: Capability[]
  fields?: ToolField[]
  coreFieldKey?: string | null
  toolId?: string | null
  initialParams?: Record<string, unknown> | null
}>()

const emit = defineEmits<{
  "primary-reference-change": [info: PrimaryReferenceMaterialInfo]
}>()

const state = ref<CapabilityState>({
  attachments: [],
  fields: {},
})
const auth = useAuthStore()
const fieldUploads = ref<Record<string, { uploading?: boolean; error?: string; fileName?: string }>>({})
const materialPickerOpen = ref(false)
const materialPickerField = ref<ToolField | null>(null)
const referencePickerOpen = ref(false)
const referencePickerTab = ref<"upload" | "material">("upload")
const materialLoading = ref(false)
const materialError = ref("")
const materialAssets = ref<MaterialAsset[]>([])
const uploadHistoryOpen = ref(false)
const uploadHistoryField = ref<ToolField | null>(null)
const uploadHistoryItems = ref<UploadHistoryItem[]>([])
const uploadHistoryUploading = ref(false)
const pickerSelectedUrls = ref<string[]>([])
const advancedOpen = ref(false)

const UPLOAD_HISTORY_LIMIT = 60
const MULTI_IMAGE_LIMIT = 8

function isAspectRatioField(field: ToolField): boolean {
  return field.fieldType === "aspect_ratio" || field.fieldKey === "aspectRatio" || field.fieldKey === "aspect_ratio" || field.fieldKey === "imageRatio"
}

function normalizeAspectRatio(value: unknown): string {
  const normalized = String(value ?? "")
    .trim()
    .replace(/：/g, ":")
    .replace(/\s+/g, "")
  if (["auto", "智能", "adaptive", "default"].includes(normalized.toLowerCase())) return "auto"
  return normalized
}

function defaultAspectRatioValue(): string {
  const fieldDefault = ratioField.value ? normalizeAspectRatio(parseFieldMeta(ratioField.value).defaultValue) : ""
  if (fieldDefault && aspectRatios.value.includes(fieldDefault)) return fieldDefault
  const configuredDefault = normalizeAspectRatio(imageCapability.value?.config.defaultRatio)
  if (configuredDefault && aspectRatios.value.includes(configuredDefault)) return configuredDefault
  if (aspectRatios.value.includes("auto")) return "auto"
  return aspectRatios.value[0] || "auto"
}

const imageCapability = computed(() => props.capabilities.find((c) => c.type === "imageGeneration"))
const fileCapability = computed(() => props.capabilities.find((c) => c.type === "fileReading"))
const webSearchCapability = computed(() => props.capabilities.find((c) => c.type === "webSearch"))
const codeCapability = computed(() => props.capabilities.find((c) => c.type === "codeExecution"))
const voiceCapability = computed(() => props.capabilities.find((c) => c.type === "voiceInput"))
const activeMaterialKind = computed(() => (materialPickerField.value ? materialKindForField(materialPickerField.value) : "file"))
const activeUploadKind = computed(() => (uploadHistoryField.value ? materialKindForField(uploadHistoryField.value) : "file"))
const ratioField = computed(() => (props.fields || []).find(isAspectRatioField))
const hasAspectRatioControl = computed(() => Boolean(imageCapability.value || ratioField.value))

function isReferenceComposerField(field: ToolField): boolean {
  if (!isReferenceMediaField(field)) return false
  const meta = parseFieldMeta(field)
  const key = field.fieldKey.toLowerCase()
  const text = `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
  const role = (meta.uiRole || "").toLowerCase()
  const placement = (meta.placement || "").toLowerCase()
  const markedForComposer =
    meta.core === true ||
    role === "reference" ||
    role === "reference_material" ||
    role === "referencematerial" ||
    role === "composer_reference" ||
    placement === "composer" ||
    placement === "prompt_left"
  const looksLikeReference =
    /reference|refimage|ref_images|sourceimage|source_image|inputimage|input_image|material|asset|参考|素材|参考图|多参考图/.test(text)
  if (field.fieldType === "multi_image") return true
  return (
    markedForComposer ||
    looksLikeReference ||
    key.includes("reference") ||
    key.includes("ref") ||
    key.includes("source") ||
    key.includes("input") ||
    key.includes("material") ||
    key.includes("asset")
  )
}

const primaryReferenceField = computed(() =>
  (props.fields || []).find((field) => field.fieldKey !== props.coreFieldKey && isReferenceComposerField(field)) || null,
)

const configuredFields = computed(() =>
  (props.fields || [])
    .filter((field) => !(field.fieldKey === props.coreFieldKey || parseFieldMeta(field).core))
    .filter((field) => field !== primaryReferenceField.value)
    .filter((field) => !isAspectRatioField(field))
    .filter((field) => isFieldVisible(field, state.value.fields)),
)

const customModeField = computed(() =>
  (props.fields || []).find((field) => field.fieldKey === "customMode" || field.fieldKey === "custom_mode"),
)

const aspectRatioOptions = computed<AspectRatioOption[]>(() => {
  const config = imageCapability.value?.config
  const options: AspectRatioOption[] = []
  const seen = new Set<string>()

  function add(label: string, rawValue: unknown) {
    const value = normalizeAspectRatio(rawValue)
    if (!value || seen.has(value)) return
    seen.add(value)
    options.push({ label: label.trim() || (value === "auto" ? "智能" : value), value })
  }

  if (ratioField.value) {
    for (const option of fieldOptions(ratioField.value)) {
      add(optionLabel(option), optionValue(option))
    }
  }
  if (Array.isArray(config?.aspectRatios)) {
    for (const ratio of config.aspectRatios) {
      add(String(ratio), ratio)
    }
  }

  return options.length > 0 ? options : [{ label: "智能", value: "auto" }]
})
const aspectRatios = computed(() => aspectRatioOptions.value.map((option) => option.value))

function aspectRatioLabel(value: string): string {
  return aspectRatioOptions.value.find((option) => option.value === value)?.label || (value === "auto" ? "智能" : value)
}

function aspectRatioIconStyle(value: string): Record<string, string> {
  const match = normalizeAspectRatio(value).match(/^(\d+(?:\.\d+)?):(\d+(?:\.\d+)?)$/)
  if (!match) return { width: "14px", height: "14px" }
  const widthRatio = Number(match[1])
  const heightRatio = Number(match[2])
  if (!Number.isFinite(widthRatio) || !Number.isFinite(heightRatio) || widthRatio <= 0 || heightRatio <= 0) {
    return { width: "14px", height: "14px" }
  }
  const max = 16
  if (widthRatio >= heightRatio) {
    return { width: `${max}px`, height: `${Math.max(5, (max * heightRatio) / widthRatio)}px` }
  }
  return { width: `${Math.max(5, (max * widthRatio) / heightRatio)}px`, height: `${max}px` }
}

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
  return fieldOptionsFromMeta(field)
}

function segmentedFieldLabel(field: ToolField): string {
  return `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
}

function isSegmentedOptionField(field: ToolField): boolean {
  if (!(field.fieldType === "select" || field.fieldType === "radio")) return false
  const options = fieldOptions(field)
  if (!options.length || options.length > 8) return false
  const text = segmentedFieldLabel(field)
  if (/count|num|quantity|生成数量|张数|数量|quality|清晰度|质量|品质/.test(text)) return true
  return field.fieldType === "radio"
}

function defaultFieldValue(field: ToolField): unknown {
  return resolveDefaultFieldValue(field)
}

function buildDefaultState(): CapabilityState {
  const next: CapabilityState = { attachments: [], fields: {} }
  if (hasAspectRatioControl.value) {
    next.imageRatio = defaultAspectRatioValue()
    if (!aspectRatios.value.includes(next.imageRatio)) next.imageRatio = defaultAspectRatioValue()
  }
  if (webSearchCapability.value) next.webSearch = webSearchCapability.value.config.defaultEnabled === true
  if (codeCapability.value) next.language = codeLanguages.value[0] || "python"
  const initialFields = [...configuredFields.value]
  if (primaryReferenceField.value && !initialFields.some((field) => field.fieldKey === primaryReferenceField.value?.fieldKey)) {
    initialFields.push(primaryReferenceField.value)
  }
  for (const field of initialFields) {
    const initial = props.initialParams?.[field.fieldKey]
    next.fields[field.fieldKey] = initial !== undefined && initial !== null ? initial : defaultFieldValue(field)
  }
  if (typeof props.initialParams?.imageRatio === "string") next.imageRatio = normalizeAspectRatio(props.initialParams.imageRatio)
  if (typeof props.initialParams?.aspectRatio === "string") next.imageRatio = normalizeAspectRatio(props.initialParams.aspectRatio)
  if (typeof props.initialParams?.aspect_ratio === "string") next.imageRatio = normalizeAspectRatio(props.initialParams.aspect_ratio)
  if (next.imageRatio && !aspectRatios.value.includes(next.imageRatio)) next.imageRatio = aspectRatios.value[0]
  if (typeof props.initialParams?.webSearch === "boolean") next.webSearch = props.initialParams.webSearch
  if (typeof props.initialParams?.language === "string") next.language = props.initialParams.language
  return next
}

function resetState() {
  state.value = buildDefaultState()
  const customMode = state.value.fields.customMode ?? state.value.fields.custom_mode
  advancedOpen.value = customMode === true || String(customMode ?? "").toLowerCase() === "true"
  fieldUploads.value = {}
}

watch(
  () => [props.capabilities, props.fields, props.coreFieldKey, props.initialParams],
  () => resetState(),
  { immediate: true, deep: true },
)

watch(
  configuredFields,
  (fields) => {
    const next = { ...state.value.fields }
    let changed = false
    for (const field of fields) {
      if (!(field.fieldKey in next)) {
        next[field.fieldKey] = defaultFieldValue(field)
        changed = true
      }
    }
    if (changed) state.value.fields = next
  },
  { deep: true },
)

function strField(key: string): string {
  const value = state.value.fields[key]
  return value === undefined || value === null ? "" : String(value)
}

function isMultiImageField(field: ToolField): boolean {
  return field.fieldType === "multi_image"
}

function multiImageLimit(field: ToolField): number {
  return parseFieldMeta(field).maxCount ?? MULTI_IMAGE_LIMIT
}

function multiImageValues(field: ToolField): string[] {
  const value = state.value.fields[field.fieldKey]
  const limit = multiImageLimit(field)
  if (Array.isArray(value)) {
    return value.map((item) => String(item).trim()).filter(Boolean).slice(0, limit)
  }
  if (typeof value === "string" && value.trim()) {
    try {
      const parsed = JSON.parse(value) as unknown
      if (Array.isArray(parsed)) {
        return parsed.map((item) => String(item).trim()).filter(Boolean).slice(0, limit)
      }
    } catch {
      return value.split(",").map((item) => item.trim()).filter(Boolean).slice(0, limit)
    }
  }
  return []
}

function setMultiImageValues(field: ToolField, urls: string[]) {
  const seen = new Set<string>()
  const limit = multiImageLimit(field)
  const next = urls
    .map((url) => url.trim())
    .filter((url) => {
      if (!url || seen.has(url)) return false
      seen.add(url)
      return true
    })
    .slice(0, limit)
  setField(field.fieldKey, next)
}

function addMultiImageUrls(field: ToolField, urls: string[]) {
  setMultiImageValues(field, [...multiImageValues(field), ...urls])
}

function removeMultiImageUrl(field: ToolField, url: string) {
  setMultiImageValues(field, multiImageValues(field).filter((item) => item !== url))
}

function togglePickerUrl(url: string) {
  const field = uploadHistoryOpen.value ? uploadHistoryField.value : materialPickerField.value
  const limit = field ? multiImageLimit(field) : MULTI_IMAGE_LIMIT
  pickerSelectedUrls.value = pickerSelectedUrls.value.includes(url)
    ? pickerSelectedUrls.value.filter((item) => item !== url)
    : [...pickerSelectedUrls.value, url].slice(0, limit)
}

function pickerIsSelected(url: string) {
  return pickerSelectedUrls.value.includes(url)
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
  if (field.fieldType === "image" || field.fieldType === "multi_image") return "image"
  const text = `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
  if (/image|img|picture|photo|frame|cover|avatar|poster|图片|图像|照片|帧|封面|首图/.test(text)) return "image"
  if (/audio|voice|sound|speech|music|音频|语音|声音|音乐/.test(text)) return "audio"
  if (/video|clip|movie|视频|短片|影片/.test(text)) return "video"
  return "file"
}

function materialKindFromValue(value?: string | null): MaterialKind {
  const text = String(value || "").toLowerCase()
  if (text === "image" || text.startsWith("image/") || /\.(png|jpe?g|webp|gif|bmp|avif)(\?|$)/.test(text)) return "image"
  if (text === "video" || text.startsWith("video/") || /\.(mp4|webm|mov|m4v)(\?|$)/.test(text)) return "video"
  if (text === "audio" || text.startsWith("audio/") || /\.(mp3|wav|m4a|flac|ogg|aac)(\?|$)/.test(text)) return "audio"
  return "file"
}

function materialKindLabel(kind: MaterialKind): string {
  if (kind === "image") return "图片"
  if (kind === "video") return "视频"
  if (kind === "audio") return "音频"
  return "素材"
}

function isReferenceMediaField(field: ToolField): boolean {
  return field.fieldType === "image" || field.fieldType === "multi_image" || field.fieldType === "file" || field.fieldType === "image_upload"
}

function isAdvancedOnlyField(field: ToolField): boolean {
  if (field === customModeField.value) return true
  const meta = parseFieldMeta(field)
  if (meta.uiTier === "advanced") return true
  if (isReferenceMediaField(field)) return false
  if (field.required) return false
  return true
}

const displayFields = computed(() => configuredFields.value.filter((field) => field !== customModeField.value))
const normalFields = computed(() => displayFields.value.filter((field) => !isAdvancedOnlyField(field)))
const advancedFields = computed(() => displayFields.value.filter(isAdvancedOnlyField))
const requestFields = computed(() => {
  const fields = [...configuredFields.value]
  const primary = primaryReferenceField.value
  if (primary && isFieldVisible(primary, state.value.fields) && !fields.some((field) => field.fieldKey === primary.fieldKey)) {
    fields.push(primary)
  }
  return fields
})
const fieldSections = computed(() => [
  { key: "normal", advanced: false, fields: normalFields.value },
  { key: "advanced", advanced: true, fields: advancedFields.value },
].filter((section) => section.fields.length > 0 || (section.advanced && customModeField.value)))

const primaryReferenceInfo = computed<PrimaryReferenceMaterialInfo>(() => {
  const field = primaryReferenceField.value
  if (!field) {
    return {
      available: false,
      fieldName: "",
      kind: "file",
      count: 0,
      maxCount: MULTI_IMAGE_LIMIT,
      previewUrls: [],
      uploading: false,
    }
  }
  const urls = isMultiImageField(field) ? multiImageValues(field) : strField(field.fieldKey) ? [strField(field.fieldKey)] : []
  const upload = uploadState(field.fieldKey)
  return {
    available: true,
    fieldName: field.fieldName,
    kind: materialKindForField(field),
    count: urls.length,
    maxCount: isMultiImageField(field) ? multiImageLimit(field) : 1,
    previewUrls: urls.slice(0, 3).map(normalizeResourceUrl),
    uploading: upload.uploading === true,
    error: upload.error,
  }
})

watch(primaryReferenceInfo, (info) => emit("primary-reference-change", info), { immediate: true, deep: true })

function fieldShellClass(field: ToolField): string {
  if (isMultiImageField(field)) return "sm:col-span-2 lg:col-span-1"
  if (field.fieldType === "slider") return "min-w-0"
  return "min-w-0"
}

function shortPlaceholder(field: ToolField): string {
  const raw = field.placeholder?.trim() || ""
  if (!raw) return ""
  if (raw.length <= 18) return raw
  return raw.slice(0, 18).trim()
}

function fieldHelpText(field: ToolField): string {
  const raw = field.placeholder?.trim() || ""
  return raw.length > 18 ? raw : ""
}

function syncCustomModeField(open: boolean) {
  const field = customModeField.value
  if (!field) return
  if (field.fieldType === "checkbox") setField(field.fieldKey, open)
  else setField(field.fieldKey, open ? "true" : "false")
}

function toggleAdvancedOpen() {
  advancedOpen.value = !advancedOpen.value
  syncCustomModeField(advancedOpen.value)
}

function formatUploadSize(size?: number): string {
  if (!size || !Number.isFinite(size)) return ""
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function uploadHistoryStorageKey(kind: MaterialKind): string {
  const userId = auth.user?.id ?? "guest"
  return `ai_tool_market_upload_history:${userId}:${kind}`
}

function readUploadHistory(kind: MaterialKind): UploadHistoryItem[] {
  if (typeof window === "undefined") return []
  try {
    const raw = window.localStorage.getItem(uploadHistoryStorageKey(kind))
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item): item is UploadHistoryItem => Boolean(item && typeof item === "object" && typeof (item as UploadHistoryItem).url === "string"))
      .slice(0, UPLOAD_HISTORY_LIMIT)
  } catch {
    return []
  }
}

function writeUploadHistory(kind: MaterialKind, items: UploadHistoryItem[]) {
  if (typeof window === "undefined") return
  window.localStorage.setItem(uploadHistoryStorageKey(kind), JSON.stringify(items.slice(0, UPLOAD_HISTORY_LIMIT)))
}

function uploadAssetToHistoryItem(asset: UserUploadAsset): UploadHistoryItem | null {
  if (!asset.url) return null
  const kind = materialKindFromValue(asset.kind || asset.contentType || asset.name)
  return {
    id: asset.fileId || String(asset.id),
    assetId: asset.id,
    kind,
    url: asset.url,
    name: asset.name || asset.fileId || "上传素材",
    size: asset.size ?? undefined,
    type: asset.contentType ?? undefined,
    uploadedAt: asset.createdAt || new Date().toISOString(),
    toolId: props.toolId,
  }
}

function mergeUploadHistoryItems(items: UploadHistoryItem[]): UploadHistoryItem[] {
  const seen = new Set<string>()
  const result: UploadHistoryItem[] = []
  for (const item of items) {
    const key = item.url || item.id
    if (!key || seen.has(key)) continue
    seen.add(key)
    result.push(item)
  }
  return result.slice(0, UPLOAD_HISTORY_LIMIT)
}

async function loadUploadHistory(kind: MaterialKind) {
  const localItems = readUploadHistory(kind)
  uploadHistoryItems.value = localItems
  if (!auth.token) return
  try {
    const page = await fetchUploadAssets({ token: auth.token, kind, pageSize: UPLOAD_HISTORY_LIMIT })
    const serverItems = page.list
      .map(uploadAssetToHistoryItem)
      .filter((item): item is UploadHistoryItem => Boolean(item))
      .filter((item) => item.kind === kind)
    const merged = mergeUploadHistoryItems([...serverItems, ...localItems])
    writeUploadHistory(kind, merged)
    uploadHistoryItems.value = merged
  } catch {
    uploadHistoryItems.value = localItems
  }
}

function rememberUploadHistoryItem(field: ToolField, item: UploadHistoryItem) {
  const kind = materialKindForField(field)
  const existing = readUploadHistory(kind).filter((entry) => entry.url !== item.url)
  writeUploadHistory(kind, [{ ...item, kind }, ...existing])
  if (uploadHistoryOpen.value && uploadHistoryField.value?.fieldKey === field.fieldKey) {
    uploadHistoryItems.value = readUploadHistory(kind)
  }
}

function openUploadHistoryPicker(field: ToolField) {
  uploadHistoryField.value = field
  const kind = materialKindForField(field)
  uploadHistoryItems.value = readUploadHistory(kind)
  void loadUploadHistory(kind)
  pickerSelectedUrls.value = isMultiImageField(field) ? multiImageValues(field) : []
  uploadHistoryOpen.value = true
}

function openReferenceMaterialPicker(tab: "upload" | "material" = "upload") {
  const field = primaryReferenceField.value
  if (!field) return
  referencePickerTab.value = tab
  referencePickerOpen.value = true
  uploadHistoryField.value = field
  materialPickerField.value = field
  pickerSelectedUrls.value = isMultiImageField(field) ? multiImageValues(field) : []
  const kind = materialKindForField(field)
  uploadHistoryItems.value = readUploadHistory(kind)
  void loadUploadHistory(kind)
  if (tab === "material") void loadGeneratedMaterialAssets(field)
}

function chooseReferencePickerTab(tab: "upload" | "material") {
  referencePickerTab.value = tab
  const field = primaryReferenceField.value
  if (tab === "material" && field) void loadGeneratedMaterialAssets(field)
}

function closeReferenceMaterialPicker() {
  referencePickerOpen.value = false
  uploadHistoryField.value = null
  materialPickerField.value = null
  uploadHistoryUploading.value = false
  pickerSelectedUrls.value = []
}

function closeUploadHistoryPicker() {
  uploadHistoryOpen.value = false
  uploadHistoryField.value = null
  uploadHistoryUploading.value = false
  pickerSelectedUrls.value = []
}

function selectUploadHistoryItem(item: UploadHistoryItem) {
  const field = uploadHistoryField.value
  if (!field) return
  if (isMultiImageField(field)) {
    togglePickerUrl(item.url)
    return
  }
  setField(field.fieldKey, item.url)
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: false, fileName: item.name },
  }
  if (referencePickerOpen.value) closeReferenceMaterialPicker()
  else closeUploadHistoryPicker()
}

async function deleteUploadHistoryItem(item: UploadHistoryItem) {
  const field = uploadHistoryField.value
  const kind = field ? materialKindForField(field) : item.kind
  const next = readUploadHistory(kind).filter((entry) => entry.id !== item.id && entry.url !== item.url)
  writeUploadHistory(kind, next)
  uploadHistoryItems.value = next
  if (item.assetId && auth.token) {
    try {
      await deleteUploadAsset(item.assetId, { token: auth.token })
    } catch {
      // 本地列表已删除，服务端失败时下次刷新会重新同步。
    }
  }
  if (field && isMultiImageField(field)) removeMultiImageUrl(field, item.url)
  else if (field && strField(field.fieldKey) === item.url) clearUploadedField(field)
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
      resolveAudioTracks(block).forEach((track, index) => {
        assets.push({
          id: `${task.taskId}-audio-${index}`,
          kind: "audio",
          url: track.url,
          previewUrl: track.coverUrl || track.url,
          title: track.title || block.title || taskTitle,
          subtitle,
        })
      })
    }
  }

  return assets
}

async function openMaterialPicker(field: ToolField) {
  materialPickerField.value = field
  materialPickerOpen.value = true
  pickerSelectedUrls.value = isMultiImageField(field) ? multiImageValues(field) : []
  await loadGeneratedMaterialAssets(field)
}

async function loadGeneratedMaterialAssets(field: ToolField) {
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
  pickerSelectedUrls.value = []
}

function selectMaterialAsset(asset: MaterialAsset) {
  const field = materialPickerField.value
  if (!field) return
  if (isMultiImageField(field)) {
    togglePickerUrl(asset.url)
    return
  }
  setField(field.fieldKey, asset.url)
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: false, fileName: asset.title },
  }
  if (referencePickerOpen.value) closeReferenceMaterialPicker()
  else closeMaterialPicker()
}

function confirmPickerSelection() {
  const field = referencePickerOpen.value ? (materialPickerField.value || uploadHistoryField.value) : uploadHistoryOpen.value ? uploadHistoryField.value : materialPickerField.value
  if (!field || !isMultiImageField(field)) return
  setMultiImageValues(field, pickerSelectedUrls.value)
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: false, fileName: `${pickerSelectedUrls.value.length} 张参考图` },
  }
  if (uploadHistoryOpen.value) closeUploadHistoryPicker()
  if (materialPickerOpen.value) closeMaterialPicker()
  if (referencePickerOpen.value) closeReferenceMaterialPicker()
}

async function uploadFieldFile(field: ToolField, file: File, options: { closeHistoryAfterUpload?: boolean } = {}) {
  if (uploadHistoryOpen.value && uploadHistoryField.value?.fieldKey === field.fieldKey) {
    uploadHistoryUploading.value = true
  }
  const kind = materialKindForField(field)
  const startedAt = new Date().toISOString()
  const fallbackId = `${Date.now()}-${Math.random().toString(16).slice(2)}`
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: true, fileName: file.name },
  }
  try {
    const result = await uploadToolFile(file, { token: auth.token })
    if (isMultiImageField(field)) {
      addMultiImageUrls(field, [result.url])
      if ((uploadHistoryOpen.value || referencePickerOpen.value) && uploadHistoryField.value?.fieldKey === field.fieldKey) {
        pickerSelectedUrls.value = multiImageValues(field)
      }
    } else {
      setField(field.fieldKey, result.url)
    }
    rememberUploadHistoryItem(field, {
      id: result.fileId || fallbackId,
      assetId: result.assetId,
      kind,
      url: result.url,
      name: result.name || file.name,
      size: result.size ?? file.size,
      type: result.contentType || file.type,
      uploadedAt: startedAt,
      toolId: props.toolId,
    })
    fieldUploads.value = {
      ...fieldUploads.value,
      [field.fieldKey]: { uploading: false, fileName: file.name },
    }
    if (options.closeHistoryAfterUpload) {
      if (referencePickerOpen.value) closeReferenceMaterialPicker()
      else closeUploadHistoryPicker()
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
  } finally {
    uploadHistoryUploading.value = false
  }
}

async function handleFieldUpload(field: ToolField, files: FileList | File[] | null) {
  const selected = Array.from(files || [])
  if (selected.length === 0) return
  if (!isMultiImageField(field)) {
    await uploadFieldFile(field, selected[0]!)
    return
  }
  for (const file of selected.slice(0, multiImageLimit(field) - multiImageValues(field).length)) {
    await uploadFieldFile(field, file)
  }
}

async function handleUploadHistoryFile(files: FileList | File[] | null) {
  const field = uploadHistoryField.value
  const selected = Array.from(files || [])
  if (!field || selected.length === 0) return
  if (!isMultiImageField(field)) {
    await uploadFieldFile(field, selected[0]!, { closeHistoryAfterUpload: true })
    return
  }
  for (const file of selected.slice(0, multiImageLimit(field) - multiImageValues(field).length)) {
    await uploadFieldFile(field, file)
  }
}

function onUploadHistoryFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  void handleUploadHistoryFile(input.files)
  input.value = ""
}

function clearUploadedField(field: ToolField) {
  setField(field.fieldKey, isMultiImageField(field) ? [] : "")
  const next = { ...fieldUploads.value }
  delete next[field.fieldKey]
  fieldUploads.value = next
}

function clearPrimaryReferenceMaterial() {
  const field = primaryReferenceField.value
  if (field) clearUploadedField(field)
}

function removePrimaryReferenceMaterialAt(index: number) {
  const field = primaryReferenceField.value
  if (!field) return
  if (isMultiImageField(field)) {
    const next = multiImageValues(field).filter((_, itemIndex) => itemIndex !== index)
    setMultiImageValues(field, next)
    fieldUploads.value = {
      ...fieldUploads.value,
      [field.fieldKey]: { uploading: false, fileName: next.length > 0 ? `${next.length} 张参考图` : undefined },
    }
    return
  }
  clearUploadedField(field)
}

function sliderConfig(field: ToolField) {
  return parseFieldMeta(field).slider || { min: 0, max: 1, step: 0.01 }
}

function onSliderInput(field: ToolField, event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  setField(field.fieldKey, value)
}

function onNumberInput(key: string, event: Event) {
  const value = (event.target as HTMLInputElement).value
  setField(key, value === "" ? "" : Number(value))
}

function validate(): { valid: boolean; message?: string } {
  for (const field of requestFields.value) {
    const value = state.value.fields[field.fieldKey]
    const maxLength = resolveMaxLength(field, state.value.fields)
    if (maxLength !== undefined && typeof value === "string" && value.length > maxLength) {
      return { valid: false, message: `${field.fieldName} 超出 ${maxLength} 字限制` }
    }
    if (uploadState(field.fieldKey).uploading) {
      return { valid: false, message: `${field.fieldName} 上传中，请稍后提交` }
    }
    if (isMultiImageField(field)) {
      const minCount = parseFieldMeta(field).minCount ?? 0
      const count = multiImageValues(field).length
      if (count < minCount) {
        return { valid: false, message: `${field.fieldName} 至少需要 ${minCount} 张` }
      }
      if (count > multiImageLimit(field)) {
        return { valid: false, message: `${field.fieldName} 最多选择 ${multiImageLimit(field)} 张` }
      }
    }
    if (!field.required) continue
    if (field.fieldType === "checkbox") continue
    if (isMultiImageField(field)) {
      const minCount = Math.max(1, parseFieldMeta(field).minCount ?? 0)
      if (multiImageValues(field).length < minCount) {
        return { valid: false, message: `请填写：${field.fieldName}` }
      }
      continue
    }
    if (value === undefined || value === null || String(value).trim() === "") {
      return { valid: false, message: `请填写：${field.fieldName}` }
    }
  }
  return { valid: true }
}

function getRequestParams(): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  if (hasAspectRatioControl.value && state.value.imageRatio) {
    const ratio = normalizeAspectRatio(state.value.imageRatio)
    params.aspectRatio = ratio
    params.imageRatio = ratio
  }
  if (webSearchCapability.value && showWebSearch.value) params.webSearch = state.value.webSearch === true
  if (codeCapability.value && state.value.language) params.language = state.value.language

  for (const field of requestFields.value) {
    const value = state.value.fields[field.fieldKey]
    if (field.fieldType === "checkbox") {
      params[field.fieldKey] = Boolean(value)
    } else if (isMultiImageField(field)) {
      const urls = multiImageValues(field)
      if (urls.length > 0) params[field.fieldKey] = urls
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
  openReferenceMaterialPicker,
  clearPrimaryReferenceMaterial,
  removePrimaryReferenceMaterialAt,
  primaryReferenceInfo,
})
</script>

<template>
  <div v-if="configuredFields.length > 0 || capabilities.length > 0" class="mt-2 space-y-2">
    <div class="space-y-3">
      <section v-for="section in fieldSections" :key="section.key" class="space-y-2">
        <button
          v-if="section.advanced"
          type="button"
          class="inline-flex h-8 items-center gap-2 rounded-full border border-white/10 bg-white/[0.04] px-3 text-xs font-medium text-white/62 transition hover:border-purple-400/30 hover:bg-purple-500/10 hover:text-white"
          @click="toggleAdvancedOpen"
        >
          <span class="text-white/45">⚙</span>
          高级配置
          <span class="text-white/35">{{ advancedOpen ? "收起" : "展开" }}</span>
        </button>

        <div
          class="grid gap-3 overflow-hidden transition-all duration-300 sm:grid-cols-2 lg:grid-cols-3"
          :class="section.advanced && !advancedOpen ? 'max-h-0 opacity-0' : 'max-h-[1200px] opacity-100'"
        >
      <div
        v-for="field in section.fields"
        :key="field.fieldKey"
        :class="fieldShellClass(field)"
      >
        <label class="mb-1 flex items-center gap-1 text-[11px] font-medium text-muted-foreground">
          <span>{{ field.fieldName }}<span v-if="field.required" class="text-destructive"> *</span></span>
          <span
            v-if="fieldHelpText(field)"
            class="group/help relative inline-flex h-3.5 w-3.5 items-center justify-center rounded-full border border-white/10 text-[10px] text-white/35"
          >
            ?
            <span class="pointer-events-none absolute bottom-full left-1/2 z-20 mb-2 hidden w-52 -translate-x-1/2 rounded-xl border border-white/10 bg-[#111217]/95 p-2 text-left text-[11px] leading-5 text-white/62 shadow-2xl backdrop-blur group-hover/help:block">
              {{ fieldHelpText(field) }}
            </span>
          </span>
        </label>

        <div
          v-if="isSegmentedOptionField(field)"
          class="flex min-h-9 flex-wrap items-center gap-2"
        >
          <button
            v-for="option in fieldOptions(field)"
            :key="optionValue(option)"
            type="button"
            class="min-h-8 rounded-full border px-3 py-1 text-xs font-medium transition"
            :class="
              strField(field.fieldKey) === optionValue(option)
                ? 'border-purple-400/30 bg-purple-500/20 text-purple-300 shadow-[0_0_18px_rgb(168_85_247_/_0.12)]'
                : 'border-white/8 bg-white/[0.05] text-white/58 hover:border-white/16 hover:bg-white/[0.07] hover:text-white'
            "
            @click="setField(field.fieldKey, optionValue(option))"
          >
            {{ optionLabel(option) }}
          </button>
        </div>

        <select
          v-else-if="(field.fieldType === 'select' || field.fieldType === 'radio') && fieldOptions(field).length"
          :value="strField(field.fieldKey)"
          class="h-9 w-full rounded-xl border border-white/10 bg-white/[0.05] px-3 text-xs text-white/78 outline-none transition hover:border-white/18 focus:border-purple-400/40"
          @change="setField(field.fieldKey, ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="option in fieldOptions(field)" :key="optionValue(option)" :value="optionValue(option)">
            {{ optionLabel(option) }}
          </option>
        </select>

        <div v-else-if="field.fieldType === 'slider'" class="space-y-1">
          <input
            type="range"
            :min="sliderConfig(field).min"
            :max="sliderConfig(field).max"
            :step="sliderConfig(field).step"
            :value="Number(state.fields[field.fieldKey] ?? sliderConfig(field).min)"
            class="capability-slider w-full"
            @input="onSliderInput(field, $event)"
          />
          <span class="text-[10px] text-muted-foreground">{{ state.fields[field.fieldKey] ?? sliderConfig(field).min }}</span>
        </div>

        <input
          v-else-if="field.fieldType === 'number'"
          type="number"
          :value="strField(field.fieldKey)"
          :placeholder="shortPlaceholder(field)"
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

        <div v-else-if="isMultiImageField(field)" class="space-y-2">
          <div
            class="flex flex-wrap gap-2"
            @dragover.prevent
            @drop.prevent="handleFieldUpload(field, ($event as DragEvent).dataTransfer?.files || null)"
          >
            <button
              type="button"
              class="flex h-16 w-16 shrink-0 items-center justify-center rounded-xl border border-dashed border-border/70 bg-background text-muted-foreground transition hover:border-primary hover:text-primary"
              :title="`选择或上传${materialKindLabel(materialKindForField(field))}`"
              @click="openUploadHistoryPicker(field)"
            >
              <Loader2 v-if="uploadState(field.fieldKey).uploading" class="h-5 w-5 animate-spin text-primary" />
              <Plus v-else class="h-5 w-5" />
            </button>
            <div
              v-for="url in multiImageValues(field)"
              :key="url"
              class="group relative h-16 w-16 overflow-hidden rounded-xl border border-border bg-muted"
            >
              <img :src="normalizeResourceUrl(url)" alt="" class="h-full w-full object-cover" />
              <button
                type="button"
                class="absolute right-1 top-1 rounded-full bg-black/60 p-1 text-white opacity-0 transition group-hover:opacity-100"
                aria-label="移除参考图"
                @click="removeMultiImageUrl(field, url)"
              >
                <X class="h-3 w-3" />
              </button>
            </div>
            <button
              type="button"
              class="flex h-16 min-w-16 items-center gap-1 rounded-xl border border-border/60 bg-background px-3 text-xs text-muted-foreground transition hover:text-primary"
              :title="`从历史${materialKindLabel(materialKindForField(field))}素材中选择`"
              @click="openMaterialPicker(field)"
            >
              <BookOpen class="h-3.5 w-3.5" />
              素材库
            </button>
          </div>
          <p class="text-[11px] text-muted-foreground">
            已选 {{ multiImageValues(field).length }}/{{ multiImageLimit(field) }} 张参考图
          </p>
          <p v-if="uploadState(field.fieldKey).error" class="text-[11px] text-destructive">
            {{ uploadState(field.fieldKey).error }}
          </p>
        </div>

        <div v-else-if="field.fieldType === 'image' || field.fieldType === 'file'" class="space-y-1">
          <div
            class="flex items-center gap-1"
            @dragover.prevent
            @drop.prevent="handleFieldUpload(field, ($event as DragEvent).dataTransfer?.files || null)"
          >
<div
  class="flex h-8 min-w-0 flex-1 items-center justify-between rounded-lg border border-border/60 bg-background px-3 text-xs text-muted-foreground transition"
>
  <button
    type="button"
    class="flex cursor-pointer items-center rounded-md px-1 py-1 transition hover:text-primary"
    :title="`选择或上传${materialKindLabel(materialKindForField(field))}`"
    @click="openUploadHistoryPicker(field)"
  >
    <Loader2 v-if="uploadState(field.fieldKey).uploading" class="h-3.5 w-3.5 animate-spin text-primary" />
    <ImageUp v-else-if="materialKindForField(field) === 'image'" class="h-3.5 w-3.5" />
    <UploadCloud v-else class="h-3.5 w-3.5" />
  </button>

  <span class="text-muted-foreground"> | </span>

  <!-- 右侧：素材库 → 保留原有提示 + 鼠标小手 -->
  <button
    type="button"
    class="cursor-pointer flex items-center rounded-md px-1 py-1 text-xs hover:text-primary transition"
    :title="`从历史${materialKindLabel(materialKindForField(field))}素材中选择`"
    @click="openMaterialPicker(field)"
  >
    <BookOpen class="h-3.5 w-3.5" />
  </button>
</div>

          </div>
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
          :placeholder="shortPlaceholder(field)"
          class="h-7 w-full rounded-lg border border-border/60 bg-background px-2 text-xs"
          @input="setField(field.fieldKey, ($event.target as HTMLInputElement).value)"
        />
      </div>
        </div>
      </section>
    </div>

    <div class="space-y-1.5">
      <label v-if="hasAspectRatioControl" class="block text-[11px] font-medium text-muted-foreground">比例</label>
      <div
        v-if="hasAspectRatioControl"
        class="grid min-h-14 overflow-hidden rounded-2xl border border-white/10 bg-white/[0.04] p-1"
        :style="{ gridTemplateColumns: `repeat(${aspectRatioOptions.length}, minmax(0, 1fr))` }"
        title="图片比例"
      >
        <button
          v-for="option in aspectRatioOptions"
          :key="option.value"
          type="button"
          class="flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-lg text-xs font-medium transition"
          :class="
            normalizeAspectRatio(state.imageRatio) === option.value
              ? 'bg-purple-500/20 text-purple-200 shadow-[0_10px_24px_rgb(0_0_0_/_0.18),inset_0_0_0_1px_rgb(168_85_247_/_0.18)]'
              : 'text-white/52 hover:bg-white/[0.06] hover:text-white'
          "
          @click="state.imageRatio = option.value"
        >
          <span class="flex h-4 items-center justify-center">
            <span
              v-if="option.value === 'auto'"
              class="flex h-3.5 w-3.5 items-center justify-center rounded-[3px] border border-current opacity-70"
            >
              <span class="h-1.5 w-1.5 rounded-[2px] border border-current opacity-70"></span>
            </span>
            <span
              v-else
              class="block rounded-[3px] border border-current opacity-90"
              :style="aspectRatioIconStyle(option.value)"
            ></span>
          </span>
          <span class="truncate">{{ aspectRatioLabel(option.value) }}</span>
        </button>
      </div>
    </div>

    <div class="flex flex-wrap items-center gap-1.5">

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

    <Teleport to="body">
      <div
        v-if="referencePickerOpen"
        class="fixed inset-0 z-[130] flex items-start justify-center bg-black/65 px-4 pb-8 pt-[7vh] backdrop-blur-sm"
        @click.self="closeReferenceMaterialPicker"
      >
        <div class="flex max-h-[86vh] w-full max-w-5xl flex-col overflow-hidden rounded-3xl border border-white/10 bg-[#191a1f] text-white shadow-[0_28px_100px_rgb(0_0_0_/_0.72)]">
          <div class="flex items-center justify-between border-b border-white/10 px-5 py-4">
            <div class="inline-flex rounded-full border border-white/10 bg-white/[0.06] p-1">
              <button
                type="button"
                class="h-10 rounded-full px-8 text-sm font-semibold transition"
                :class="referencePickerTab === 'upload' ? 'bg-[#557296] text-white shadow-inner' : 'text-white/48 hover:text-white'"
                @click="chooseReferencePickerTab('upload')"
              >
                上传
              </button>
              <button
                type="button"
                class="h-10 rounded-full px-8 text-sm font-semibold transition"
                :class="referencePickerTab === 'material' ? 'bg-[#557296] text-white shadow-inner' : 'text-white/48 hover:text-white'"
                @click="chooseReferencePickerTab('material')"
              >
                素材
              </button>
            </div>
            <button
              type="button"
              class="inline-flex h-11 w-11 items-center justify-center rounded-full bg-white/[0.06] text-white/55 transition hover:bg-white/10 hover:text-white"
              aria-label="关闭素材选择"
              @click="closeReferenceMaterialPicker"
            >
              <X class="h-5 w-5" />
            </button>
          </div>

          <div v-if="referencePickerTab === 'upload'" class="min-h-[420px] overflow-y-auto p-5">
            <label
              class="flex min-h-40 cursor-pointer flex-col items-center justify-center gap-4 rounded-2xl border border-dashed border-white/18 bg-white/[0.035] text-white/72 transition hover:border-primary/60 hover:bg-white/[0.055]"
              @dragover.prevent
              @drop.prevent="uploadHistoryField && handleUploadHistoryFile(($event as DragEvent).dataTransfer?.files || null)"
            >
              <UploadCloud class="h-8 w-8 text-white/70" />
              <span class="text-base font-semibold">{{ uploadHistoryUploading ? "上传中..." : "上传或拖拽图片/文件" }}</span>
              <input
                type="file"
                class="hidden"
                :accept="uploadHistoryField ? uploadAccept(uploadHistoryField) : undefined"
                :disabled="uploadHistoryUploading"
                :multiple="uploadHistoryField ? isMultiImageField(uploadHistoryField) : false"
                @change="onUploadHistoryFileChange"
              />
            </label>

            <section class="mt-7">
              <div class="mb-4 flex items-center gap-2 text-sm font-semibold text-white/70">
                <Clock class="h-4 w-4" />
                最近上传
              </div>
              <div v-if="uploadHistoryItems.length === 0" class="flex h-40 flex-col items-center justify-center rounded-2xl border border-white/8 bg-white/[0.03] text-center text-sm text-white/42">
                <UploadCloud class="mb-3 h-7 w-7 text-white/22" />
                <p>还没有上传历史</p>
                <p class="mt-1 text-xs text-white/30">上传一次后，下次可以直接复用。</p>
              </div>
              <div v-else class="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">
                <article
                  v-for="item in uploadHistoryItems"
                  :key="item.id"
                  class="group overflow-hidden rounded-2xl border border-white/10 bg-white/[0.05] transition hover:-translate-y-0.5 hover:border-primary/60 hover:bg-white/[0.075]"
                >
                  <button
                    type="button"
                    class="block w-full text-left"
                    :class="{ 'ring-2 ring-primary': uploadHistoryField && isMultiImageField(uploadHistoryField) && pickerIsSelected(item.url) }"
                    @click="selectUploadHistoryItem(item)"
                  >
                    <div class="relative flex aspect-[4/3] items-center justify-center bg-black/20">
                      <img
                        v-if="item.kind === 'image'"
                        :src="normalizeResourceUrl(item.url)"
                        alt=""
                        class="h-full w-full object-cover"
                      />
                      <FileVideo v-else-if="item.kind === 'video'" class="h-9 w-9 text-white/35 group-hover:text-primary" />
                      <FileAudio v-else-if="item.kind === 'audio'" class="h-9 w-9 text-white/35 group-hover:text-primary" />
                      <ImageIcon v-else class="h-9 w-9 text-white/35 group-hover:text-primary" />
                      <span
                        v-if="uploadHistoryField && isMultiImageField(uploadHistoryField) && pickerIsSelected(item.url)"
                        class="absolute right-3 top-3 rounded-full bg-primary p-1 text-white"
                      >
                        <Check class="h-3.5 w-3.5" />
                      </span>
                    </div>
                    <div class="space-y-1 p-3">
                      <p class="truncate text-sm font-semibold text-white/86">{{ item.name }}</p>
                      <p class="truncate text-xs text-white/38">{{ formatUploadSize(item.size) || item.type || "已上传" }}</p>
                    </div>
                  </button>
                  <div class="border-t border-white/8 px-3 py-2">
                    <button
                      type="button"
                      class="text-xs text-white/38 transition hover:text-red-300"
                      @click="deleteUploadHistoryItem(item)"
                    >
                      删除历史
                    </button>
                  </div>
                </article>
              </div>
            </section>
          </div>

          <div v-else class="min-h-[420px] overflow-y-auto p-5">
            <div class="mb-4 flex items-center justify-between">
              <div class="flex items-center gap-2 text-sm font-semibold text-white/70">
                <BookOpen class="h-4 w-4" />
                已生成素材
              </div>
              <button type="button" class="rounded-full border border-white/10 px-3 py-1 text-xs text-white/45 transition hover:border-white/20 hover:text-white" @click="primaryReferenceField && loadGeneratedMaterialAssets(primaryReferenceField)">
                刷新
              </button>
            </div>
            <div v-if="materialLoading" class="flex h-56 items-center justify-center gap-2 text-sm text-white/45">
              <Loader2 class="h-4 w-4 animate-spin" />
              正在加载素材...
            </div>
            <div v-else-if="materialError" class="flex h-56 items-center justify-center text-sm text-red-300">
              {{ materialError }}
            </div>
            <div v-else-if="materialAssets.length === 0" class="flex h-56 items-center justify-center rounded-2xl border border-white/8 bg-white/[0.03] text-sm text-white/42">
              暂无可用{{ materialKindLabel(activeMaterialKind) }}素材
            </div>
            <div v-else class="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">
              <button
                v-for="asset in materialAssets"
                :key="asset.id"
                type="button"
                class="group overflow-hidden rounded-2xl border border-white/10 bg-white/[0.05] text-left transition hover:-translate-y-0.5 hover:border-primary/60 hover:bg-white/[0.075]"
                :class="{ 'ring-2 ring-primary': materialPickerField && isMultiImageField(materialPickerField) && pickerIsSelected(asset.url) }"
                @click="selectMaterialAsset(asset)"
              >
                <div class="relative flex aspect-[4/3] items-center justify-center bg-black/20">
                  <img
                    v-if="asset.kind === 'image' && asset.previewUrl"
                    :src="normalizeResourceUrl(asset.previewUrl)"
                    alt=""
                    class="h-full w-full object-cover"
                  />
                  <FileVideo v-else-if="asset.kind === 'video'" class="h-9 w-9 text-white/35 group-hover:text-primary" />
                  <FileAudio v-else-if="asset.kind === 'audio'" class="h-9 w-9 text-white/35 group-hover:text-primary" />
                  <ImageIcon v-else class="h-9 w-9 text-white/35 group-hover:text-primary" />
                  <span
                    v-if="materialPickerField && isMultiImageField(materialPickerField) && pickerIsSelected(asset.url)"
                    class="absolute right-3 top-3 rounded-full bg-primary p-1 text-white"
                  >
                    <Check class="h-3.5 w-3.5" />
                  </span>
                </div>
                <div class="space-y-1 p-3">
                  <p class="truncate text-sm font-semibold text-white/86">{{ asset.title }}</p>
                  <p class="truncate text-xs text-white/38">{{ asset.subtitle }}</p>
                </div>
              </button>
            </div>
          </div>

          <footer
            v-if="primaryReferenceField && isMultiImageField(primaryReferenceField)"
            class="flex items-center justify-between border-t border-white/10 px-5 py-4"
          >
            <span class="text-sm text-white/45">已选 {{ pickerSelectedUrls.length }}/{{ multiImageLimit(primaryReferenceField) }} 个素材</span>
            <button
              type="button"
              class="rounded-full bg-[#5da8ff] px-6 py-3 text-sm font-semibold text-white transition hover:brightness-110"
              @click="confirmPickerSelection"
            >
              确认选择
            </button>
          </footer>
        </div>
      </div>

      <div
        v-if="uploadHistoryOpen"
        class="fixed inset-0 z-[125] flex items-start justify-center bg-black/65 px-4 pb-8 pt-[9vh] backdrop-blur-sm"
        @click.self="closeUploadHistoryPicker"
      >
        <div class="flex max-h-[82vh] w-full max-w-4xl flex-col overflow-hidden rounded-3xl border border-white/10 bg-[#111217] text-white shadow-[0_28px_100px_rgb(0_0_0_/_0.72)]">
          <div class="flex flex-wrap items-center justify-between gap-4 border-b border-white/10 px-6 py-5">
            <div>
              <h3 class="text-lg font-semibold text-white">
                上传{{ materialKindLabel(activeUploadKind) }}
              </h3>
              <p class="mt-1 text-sm text-white/45">
                先选择上传历史，或上传新的参考{{ materialKindLabel(activeUploadKind) }}。
              </p>
            </div>
            <div class="flex items-center gap-2">
              <label
                class="inline-flex h-10 cursor-pointer items-center gap-2 rounded-full bg-primary px-4 text-sm font-semibold text-white shadow-[0_12px_32px_rgb(176_92_255_/_0.28)] transition hover:brightness-110"
              >
                <Loader2 v-if="uploadHistoryUploading" class="h-4 w-4 animate-spin" />
                <UploadCloud v-else class="h-4 w-4" />
                上传新文件
                <input
                  type="file"
                  class="hidden"
                  :accept="uploadHistoryField ? uploadAccept(uploadHistoryField) : undefined"
                  :disabled="uploadHistoryUploading"
                  :multiple="uploadHistoryField ? isMultiImageField(uploadHistoryField) : false"
                  @change="onUploadHistoryFileChange"
                />
              </label>
              <button
                type="button"
                class="rounded-full p-2 text-white/45 transition hover:bg-white/10 hover:text-white"
                @click="closeUploadHistoryPicker"
              >
                <X class="h-5 w-5" />
              </button>
            </div>
          </div>

          <div class="min-h-[260px] overflow-y-auto p-6">
            <div v-if="uploadHistoryItems.length === 0" class="flex h-56 flex-col items-center justify-center text-center text-sm text-white/45">
              <UploadCloud class="mb-3 h-8 w-8 text-white/25" />
              <p>还没有上传历史</p>
              <p class="mt-1 text-xs text-white/32">上传一次后，下次可以直接复用同一张参考图。</p>
            </div>
            <div v-else class="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">
              <article
                v-for="item in uploadHistoryItems"
                :key="item.id"
                class="group overflow-hidden rounded-2xl border border-white/10 bg-white/[0.04] transition hover:-translate-y-0.5 hover:border-primary/60 hover:bg-white/[0.07]"
              >
                <button
                  type="button"
                  class="block w-full text-left"
                  :class="{ 'ring-2 ring-primary': uploadHistoryField && isMultiImageField(uploadHistoryField) && pickerIsSelected(item.url) }"
                  @click="selectUploadHistoryItem(item)"
                >
                  <div class="relative flex aspect-[4/3] items-center justify-center bg-white/[0.04]">
                    <img
                      v-if="item.kind === 'image'"
                      :src="normalizeResourceUrl(item.url)"
                      alt=""
                      class="h-full w-full object-cover"
                    />
                    <FileVideo v-else-if="item.kind === 'video'" class="h-9 w-9 text-white/35 group-hover:text-primary" />
                    <FileAudio v-else-if="item.kind === 'audio'" class="h-9 w-9 text-white/35 group-hover:text-primary" />
                    <ImageIcon v-else class="h-9 w-9 text-white/35 group-hover:text-primary" />
                    <span
                      v-if="uploadHistoryField && isMultiImageField(uploadHistoryField) && pickerIsSelected(item.url)"
                      class="absolute right-3 top-3 rounded-full bg-primary p-1 text-white"
                    >
                      <Check class="h-3.5 w-3.5" />
                    </span>
                  </div>
                  <div class="space-y-1.5 p-4">
                    <p class="truncate text-sm font-semibold text-white">{{ item.name }}</p>
                    <p class="truncate text-xs text-white/40">
                      {{ formatUploadSize(item.size) || item.type || "已上传" }}
                    </p>
                  </div>
                </button>
                <div class="border-t border-white/8 px-4 py-2">
                  <button
                    type="button"
                    class="text-xs text-white/40 transition hover:text-red-300"
                    @click="deleteUploadHistoryItem(item)"
                  >
                    删除历史
                  </button>
                </div>
              </article>
            </div>
          </div>
          <div
            v-if="uploadHistoryField && isMultiImageField(uploadHistoryField)"
            class="flex items-center justify-between border-t border-white/10 px-6 py-4"
          >
            <span class="text-sm text-white/45">已选 {{ pickerSelectedUrls.length }}/{{ materialPickerField ? multiImageLimit(materialPickerField) : MULTI_IMAGE_LIMIT }} 张</span>
            <button
              type="button"
              class="rounded-full bg-primary px-4 py-2 text-sm font-semibold text-white transition hover:brightness-110"
              @click="confirmPickerSelection"
            >
              确认选择
            </button>
          </div>
        </div>
      </div>

      <div
        v-if="materialPickerOpen"
        class="fixed inset-0 z-[120] flex items-start justify-center bg-black/65 px-4 pb-8 pt-[9vh] backdrop-blur-sm"
        @click.self="closeMaterialPicker"
      >
        <div class="flex max-h-[82vh] w-full max-w-5xl flex-col overflow-hidden rounded-3xl border border-white/10 bg-[#111217] text-white shadow-[0_28px_100px_rgb(0_0_0_/_0.72)]">
          <div class="flex items-center justify-between border-b border-white/10 px-6 py-5">
            <div>
              <h3 class="text-lg font-semibold text-white">
                选择{{ materialKindLabel(activeMaterialKind) }}素材
              </h3>
              <p class="mt-1 text-sm text-white/45">
                来自你已生成成功的历史任务，选择后会填入当前上传字段。
              </p>
            </div>
            <button
              type="button"
              class="rounded-full p-2 text-white/45 transition hover:bg-white/10 hover:text-white"
              @click="closeMaterialPicker"
            >
              <X class="h-5 w-5" />
            </button>
          </div>

          <div class="min-h-[260px] overflow-y-auto p-6">
            <div v-if="materialLoading" class="flex h-56 items-center justify-center gap-2 text-sm text-white/45">
              <Loader2 class="h-4 w-4 animate-spin" />
              正在加载素材库...
            </div>
            <div v-else-if="materialError" class="flex h-56 items-center justify-center text-sm text-red-300">
              {{ materialError }}
            </div>
            <div v-else-if="materialAssets.length === 0" class="flex h-56 items-center justify-center text-sm text-white/45">
              暂无可用{{ materialKindLabel(activeMaterialKind) }}素材
            </div>
            <div v-else class="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">
              <button
                v-for="asset in materialAssets"
                :key="asset.id"
                type="button"
                class="group overflow-hidden rounded-2xl border border-white/10 bg-white/[0.04] text-left transition hover:-translate-y-0.5 hover:border-primary/60 hover:bg-white/[0.07]"
                :class="{ 'ring-2 ring-primary': materialPickerField && isMultiImageField(materialPickerField) && pickerIsSelected(asset.url) }"
                @click="selectMaterialAsset(asset)"
              >
                <div class="relative flex aspect-[4/3] items-center justify-center bg-white/[0.04]">
                  <img
                    v-if="asset.kind === 'image' && asset.previewUrl"
                    :src="asset.previewUrl"
                    alt=""
                    class="h-full w-full object-cover"
                  />
                  <FileVideo v-else-if="asset.kind === 'video'" class="h-9 w-9 text-white/35 group-hover:text-primary" />
                  <FileAudio v-else-if="asset.kind === 'audio'" class="h-9 w-9 text-white/35 group-hover:text-primary" />
                  <ImageIcon v-else class="h-9 w-9 text-white/35 group-hover:text-primary" />
                  <span
                    v-if="materialPickerField && isMultiImageField(materialPickerField) && pickerIsSelected(asset.url)"
                    class="absolute right-3 top-3 rounded-full bg-primary p-1 text-white"
                  >
                    <Check class="h-3.5 w-3.5" />
                  </span>
                </div>
                <div class="space-y-1.5 p-4">
                  <p class="truncate text-sm font-semibold text-white">{{ asset.title }}</p>
                  <p class="truncate text-xs text-white/40">{{ asset.subtitle }}</p>
                </div>
              </button>
            </div>
          </div>
          <div
            v-if="materialPickerField && isMultiImageField(materialPickerField)"
            class="flex items-center justify-between border-t border-white/10 px-6 py-4"
          >
            <span class="text-sm text-white/45">已选 {{ pickerSelectedUrls.length }}/{{ uploadHistoryField ? multiImageLimit(uploadHistoryField) : MULTI_IMAGE_LIMIT }} 张</span>
            <button
              type="button"
              class="rounded-full bg-primary px-4 py-2 text-sm font-semibold text-white transition hover:brightness-110"
              @click="confirmPickerSelection"
            >
              确认选择
            </button>
          </div>
        </div>
              </div>
    </Teleport>
  </div>
</template>

<style scoped>
.capability-slider {
  height: 12px;
  appearance: none;
  background: transparent;
}

.capability-slider::-webkit-slider-runnable-track {
  height: 3px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.14);
}

.capability-slider::-webkit-slider-thumb {
  width: 12px;
  height: 12px;
  margin-top: -4.5px;
  appearance: none;
  border: 0;
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 2px 8px rgb(0 0 0 / 0.35);
}

.capability-slider::-moz-range-track {
  height: 3px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.14);
}

.capability-slider::-moz-range-thumb {
  width: 12px;
  height: 12px;
  border: 0;
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 2px 8px rgb(0 0 0 / 0.35);
}
</style>
