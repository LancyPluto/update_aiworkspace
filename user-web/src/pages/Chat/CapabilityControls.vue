<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import type { Capability } from "@/api/aiToolTypes"
import type { TaskDetail, ToolField, UserUploadAsset } from "@/api/types"
import { deleteUploadAsset, uploadToolFile } from "@/api/toolApi"
import { resolveCommunityDerivativeUrl } from "@/utils/communityPostMedia"
import { normalizeMediaFieldValue, normalizeMediaUrl, isValidImagePreviewUrl } from "@/utils/toolCoverMedia"
import { useAuthStore } from "@/store/authStore"
import { buildTaskResultBlocks, resolveAudioTracks } from "@/utils/taskResultBlocks"
import { useGeneratedMaterialList, useUploadHistoryList } from "@/composables/useMaterialPickerLists"
import { useInfiniteScroll } from "@/composables/useInfiniteScroll"
import {
  defaultFieldValue as resolveDefaultFieldValue,
  fieldOptionsFromMeta,
  groupVisibleFields,
  isFieldVisible,
  parseFieldMeta,
  resolveMaxLength,
} from "@/utils/fieldUiMeta"
import {
  isMediaListField,
  mediaListMax,
  mediaListMin,
  mediaListUnitLabel,
  parseMediaListValue,
} from "@/utils/mediaListField"
import {
  parseSubjectElementEditorItems,
  serializeSubjectElementItems,
  subjectElementMax,
  validateSubjectElementItems,
} from "@/utils/subjectElementList"
import SubjectElementListField from "@/components/DynamicForm/SubjectElementListField.vue"
import {
  hasBaseKlingOmniVideo,
  klingOmniVideoMax,
  parseKlingOmniVideoEditorItems,
  serializeKlingOmniVideoItems,
  validateKlingOmniVideoItems,
  type KlingOmniVideoReference,
} from "@/utils/klingOmniVideoList"
import KlingOmniVideoListField from "@/components/DynamicForm/KlingOmniVideoListField.vue"
import { BookOpen, Check, ChevronDown, Clock, FileAudio, FileVideo, Film, ImageIcon, ImageUp, Loader2, Mic, Plus, SlidersHorizontal, UploadCloud, X } from "lucide-vue-next"

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
  previewUrl?: string
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

export type ComposerMediaSlotPresentation = "image_thumb" | "media_card" | "frame_card"

export interface ComposerMediaSlot {
  fieldKey: string
  fieldName: string
  kind: MaterialKind
  presentation: ComposerMediaSlotPresentation
  label: string
  value: string
  previewUrl: string
  hasValue: boolean
  uploading: boolean
  error?: string
  canAdd: boolean
  previewUrls: string[]
  count: number
  maxCount: number
}

const props = defineProps<{
  capabilities: Capability[]
  fields?: ToolField[]
  coreFieldKey?: string | null
  toolId?: string | null
  initialParams?: Record<string, unknown> | null
  layout?: "default" | "composer"
  outputModality?: string | null
  inputModality?: string | null
}>()

const emit = defineEmits<{
  "primary-reference-change": [info: PrimaryReferenceMaterialInfo]
  "composer-media-slots-change": [slots: ComposerMediaSlot[]]
  "params-change": [params: Record<string, unknown>]
}>()

const state = ref<CapabilityState>({
  attachments: [],
  fields: {},
})
const auth = useAuthStore()
const fieldUploads = ref<Record<string, { uploading?: boolean; error?: string; fileName?: string }>>({})
const referencePickerOpen = ref(false)
const referencePickerTab = ref<"upload" | "material">("upload")
const materialPickerOpen = ref(false)
const materialPickerField = ref<ToolField | null>(null)
const uploadHistoryOpen = ref(false)
const uploadHistoryField = ref<ToolField | null>(null)
const uploadHistoryUploading = ref(false)
const pickerSelectedUrls = ref<string[]>([])
const advancedOpen = ref(false)
const quickParamsOpen = ref(false)
const paramsPillRef = ref<HTMLElement | null>(null)
const advancedButtonRef = ref<HTMLElement | null>(null)
const quickPopoverStyle = ref<Record<string, string>>({})
const advancedPopoverStyle = ref<Record<string, string>>({})

const isComposerLayout = computed(() => props.layout === "composer")
const referenceUploadScrollRootRef = ref<HTMLElement | null>(null)
const referenceUploadSentinelRef = ref<HTMLElement | null>(null)
const referenceMaterialScrollRootRef = ref<HTMLElement | null>(null)
const referenceMaterialSentinelRef = ref<HTMLElement | null>(null)
const standaloneUploadScrollRootRef = ref<HTMLElement | null>(null)
const standaloneUploadSentinelRef = ref<HTMLElement | null>(null)
const standaloneMaterialScrollRootRef = ref<HTMLElement | null>(null)
const standaloneMaterialSentinelRef = ref<HTMLElement | null>(null)

const MULTI_IMAGE_LIMIT = 8
const SEGMENTED_OPTION_LIMIT = 8

const uploadHistoryList = useUploadHistoryList<UploadHistoryItem>({
  getKind: () => activeUploadKind.value,
  getToken: () => auth.token,
  getUserId: () => auth.user?.id,
  toHistoryItem: (asset) => uploadAssetToHistoryItem(asset),
})

const generatedMaterialList = useGeneratedMaterialList<MaterialAsset>({
  getKind: () => activeMaterialKind.value,
  getToken: () => auth.token,
  createAssetsFromTask: (task, targetKind) => createMaterialAssets(task, targetKind),
})

useInfiniteScroll({
  sentinelRef: referenceUploadSentinelRef,
  scrollRootRef: referenceUploadScrollRootRef,
  enabled: () => referencePickerOpen.value && referencePickerTab.value === "upload",
  hasMore: () => uploadHistoryList.hasMore.value,
  loading: () => uploadHistoryList.loading.value,
  loadingMore: () => uploadHistoryList.loadingMore.value,
  onLoadMore: () => uploadHistoryList.loadMore(),
})

useInfiniteScroll({
  sentinelRef: standaloneUploadSentinelRef,
  scrollRootRef: standaloneUploadScrollRootRef,
  enabled: () => uploadHistoryOpen.value,
  hasMore: () => uploadHistoryList.hasMore.value,
  loading: () => uploadHistoryList.loading.value,
  loadingMore: () => uploadHistoryList.loadingMore.value,
  onLoadMore: () => uploadHistoryList.loadMore(),
})

useInfiniteScroll({
  sentinelRef: referenceMaterialSentinelRef,
  scrollRootRef: referenceMaterialScrollRootRef,
  enabled: () => referencePickerOpen.value && referencePickerTab.value === "material",
  hasMore: () => generatedMaterialList.hasMore.value,
  loading: () => generatedMaterialList.loading.value,
  loadingMore: () => generatedMaterialList.loadingMore.value,
  onLoadMore: () => generatedMaterialList.loadMore(),
})

useInfiniteScroll({
  sentinelRef: standaloneMaterialSentinelRef,
  scrollRootRef: standaloneMaterialScrollRootRef,
  enabled: () => materialPickerOpen.value,
  hasMore: () => generatedMaterialList.hasMore.value,
  loading: () => generatedMaterialList.loading.value,
  loadingMore: () => generatedMaterialList.loadingMore.value,
  onLoadMore: () => generatedMaterialList.loadMore(),
})

const uploadHistoryItems = computed(() => uploadHistoryList.items.value)
const uploadHistoryLoading = computed(() => uploadHistoryList.loading.value)
const uploadHistoryLoadingMore = computed(() => uploadHistoryList.loadingMore.value)
const uploadHistoryHasMore = computed(() => uploadHistoryList.hasMore.value)
const materialAssets = computed(() => generatedMaterialList.assets.value)
const materialLoading = computed(() => generatedMaterialList.loading.value)
const materialLoadingMore = computed(() => generatedMaterialList.loadingMore.value)
const materialHasMore = computed(() => generatedMaterialList.hasMore.value)
const materialError = computed(() => generatedMaterialList.error.value)

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
const webSearchCapability = computed(() => props.capabilities.find((c) => c.type === "webSearch"))
const codeCapability = computed(() => props.capabilities.find((c) => c.type === "codeExecution"))
const voiceCapability = computed(() => props.capabilities.find((c) => c.type === "voiceInput"))
const activeMaterialKind = computed(() => (materialPickerField.value ? materialKindForField(materialPickerField.value) : "file"))
const activeUploadKind = computed(() => (uploadHistoryField.value ? materialKindForField(uploadHistoryField.value) : "file"))
const referenceUploadHint = computed(() => {
  const kind = activeUploadKind.value
  if (kind === "video") return "上传或拖拽视频"
  if (kind === "audio") return "上传或拖拽音频"
  if (kind === "image") return "上传或拖拽图片"
  return "上传或拖拽文件"
})
const ratioField = computed(() => (props.fields || []).find(isAspectRatioField))
const hasAspectRatioControl = computed(() => Boolean(imageCapability.value || ratioField.value))

function isExplicitComposerReferenceField(field: ToolField): boolean {
  const meta = parseFieldMeta(field)
  const role = (meta.uiRole || "").toLowerCase()
  const placement = (meta.placement || "").toLowerCase()
  return (
    meta.core === true ||
    role === "reference" ||
    role === "reference_material" ||
    role === "referencematerial" ||
    role === "composer_reference" ||
    placement === "composer" ||
    placement === "prompt_left"
  )
}

function isReferenceComposerField(field: ToolField): boolean {
  if (!isReferenceMediaField(field)) return false
  if (field.fieldType === "multi_image" || isOmniVideoListField(field)) return true
  if (isExplicitComposerReferenceField(field)) return true
  const text = `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
  const key = field.fieldKey.toLowerCase()
  const looksLikeReference =
    /reference|refimage|ref_images|sourceimage|source_image|inputimage|input_image|参考图|多参考图|参考图片|参考素材/.test(text)
  return (
    looksLikeReference ||
    key.includes("reference") ||
    key.includes("refimage") ||
    key.includes("ref_image") ||
    key.includes("sourceimage") ||
    key.includes("source_image") ||
    key.includes("inputimage") ||
    key.includes("input_image")
  )
}

function isComposerImageReferenceField(field: ToolField): boolean {
  return field.fieldType === "image" || field.fieldType === "image_upload" || field.fieldType === "multi_image"
}

function shouldShowComposerReferenceUpload(field: ToolField): boolean {
  if (!isFieldVisible(field, state.value.fields)) return false
  if (isComposerFrameField(field)) return false
  // dashboard 的加号参考区只支持图片预览。
  if (!isComposerImageReferenceField(field)) return false
  // 多参考图场景：即便是可选字段，也需要有入口
  if (isMultiImageField(field)) return true
  if (field.required || field.executionRequired || field.userRequired) return true
  if (isExplicitComposerReferenceField(field)) return true
  const output = (props.outputModality || "").trim().toUpperCase()
  // 视频生成工具：允许可选参考图
  if (output === "VIDEO" || output.includes("VIDEO")) return true
  // 图片生成工具：仅当字段被标记为必填/显式 reference 才展示入口
  return false
}

function isComposerFrameField(field: ToolField): boolean {
  const meta = parseFieldMeta(field)
  const role = (meta.uiRole || "").toLowerCase()
  if (role === "first_frame" || role === "last_frame") return true
  const key = field.fieldKey.toLowerCase()
  const name = field.fieldName || ""
  if (key === "imagetail" || key === "image_tail" || key === "tailimageurl" || key === "lastframeurl") return true
  if (/尾帧|末帧/.test(name)) return true
  if (key === "firstframeimage" || key === "first_frame_image" || key === "firstframeurl") return true
  if (/首帧/.test(name) && (key === "imageurl" || key === "image_url")) return true
  return false
}

function isComposerLastFrameField(field: ToolField): boolean {
  const key = field.fieldKey.toLowerCase()
  const name = field.fieldName || ""
  return key === "imagetail" || key === "image_tail" || key === "tailimageurl" || key === "lastframeurl" || /尾帧|末帧/.test(name)
}

function composerFrameLabel(field: ToolField): string {
  return isComposerLastFrameField(field) ? "尾帧" : "首帧"
}

function resolveComposerSlotPresentation(field: ToolField, kind: MaterialKind): ComposerMediaSlotPresentation | null {
  if (isComposerFrameField(field)) return "frame_card"
  if (kind === "video" || kind === "audio") return "media_card"
  if (kind === "image" && isComposerImageReferenceField(field)) return "image_thumb"
  return null
}

function shouldShowComposerMediaSlot(field: ToolField): boolean {
  if (!isFieldVisible(field, state.value.fields)) return false
  const kind = materialKindForField(field)
  const presentation = resolveComposerSlotPresentation(field, kind)
  if (!presentation) return false
  if (presentation === "frame_card") return true
  if (presentation === "media_card") {
    if (field.fieldType === "video_upload") return true
    const role = (parseFieldMeta(field).uiRole || "").toLowerCase()
    if (role === "motion_video") return true
    if (field.required || field.executionRequired || field.userRequired) return true
    if (isExplicitComposerReferenceField(field)) return true
    if (kind === "audio") return field.required || field.executionRequired || field.userRequired
    return false
  }
  return shouldShowComposerReferenceUpload(field)
}

function composerSlotSortOrder(field: ToolField): number {
  const meta = parseFieldMeta(field)
  if (typeof meta.uiOrder === "number" && Number.isFinite(meta.uiOrder)) return meta.uiOrder
  return field.sortOrder ?? 999
}

function composerSlotLabel(field: ToolField, kind: MaterialKind, presentation: ComposerMediaSlotPresentation): string {
  if (presentation === "frame_card") return composerFrameLabel(field)
  if (presentation === "media_card" && kind === "audio") return "音频"
  return ""
}

function buildComposerMediaSlot(field: ToolField): ComposerMediaSlot {
  const kind = materialKindForField(field)
  const presentation = resolveComposerSlotPresentation(field, kind)!
  const upload = uploadState(field.fieldKey)
  const isMulti = presentation === "image_thumb" && isMultiImageField(field)
  const values = isMulti ? multiImageValues(field) : strField(field.fieldKey) ? [strField(field.fieldKey)] : []
  const previewUrls = values
    .map((url) => normalizeMediaUrl(url))
    .filter((url) => {
      if (kind === "image") return isValidImagePreviewUrl(url)
      return Boolean(url)
    })
  const maxCount = isMulti ? multiImageLimit(field) : 1
  const count = isMulti ? previewUrls.length : previewUrls.length > 0 ? 1 : 0
  const hasValue = count > 0
  return {
    fieldKey: field.fieldKey,
    fieldName: field.fieldName,
    kind,
    presentation,
    label: composerSlotLabel(field, kind, presentation),
    value: values[0] || "",
    previewUrl: previewUrls[0] || "",
    hasValue,
    uploading: upload.uploading === true,
    error: upload.error,
    canAdd: isMulti ? count < maxCount : !hasValue,
    previewUrls,
    count,
    maxCount,
  }
}

function referenceFieldScore(field: ToolField): number {
  let score = 0
  if (field.required || field.executionRequired || field.userRequired) score += 100
  if (isExplicitComposerReferenceField(field)) score += 70
  if (isMultiImageField(field)) score += 40
  if (field.fieldType === "image_upload") score += 25
  const text = `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
  if (/firstframe|首帧|lastframe|末帧|reference|refimage|sourceimage|inputimage|参考图|参考图片|多参考图|参考素材/.test(text)) score += 15
  return score
}

const primaryReferenceField = computed(() => {
  const fields = props.fields || []
  const candidates = fields.filter(
    (field) => isReferenceComposerField(field) && isComposerImageReferenceField(field) && !isComposerFrameField(field),
  )
  if (candidates.length === 0) return null
  return [...candidates].sort((a, b) => referenceFieldScore(b) - referenceFieldScore(a))[0] || null
})

const composerMediaSlots = computed<ComposerMediaSlot[]>(() => {
  if (!isComposerLayout.value) return []
  return (props.fields || [])
    .filter((field) => shouldShowComposerMediaSlot(field))
    .sort((a, b) => composerSlotSortOrder(a) - composerSlotSortOrder(b))
    .map((field) => buildComposerMediaSlot(field))
})

const composerSlotFieldKeys = computed(() => new Set(composerMediaSlots.value.map((slot) => slot.fieldKey)))

const configuredFields = computed(() =>
  (props.fields || [])
    .filter((field) => !(field.fieldKey === props.coreFieldKey || parseFieldMeta(field).core))
    .filter((field) => field !== primaryReferenceField.value)
    .filter((field) => !composerSlotFieldKeys.value.has(field.fieldKey))
    .filter((field) => !isAspectRatioField(field))
    .filter((field) => isFieldVisible(field, state.value.fields)),
)

const customModeField = computed(() =>
  (props.fields || []).find((field) => field.fieldKey === "customMode" || field.fieldKey === "custom_mode"),
)
const customModeOptions = computed<FieldOption[]>(() => {
  const field = customModeField.value
  if (!field) return []
  const options = fieldOptions(field)
  return options.length > 0 ? options : [{ label: "常规", value: "false" }, { label: "高级", value: "true" }]
})
const customModeValue = computed(() => {
  const field = customModeField.value
  if (!field) return "false"
  const value = state.value.fields[field.fieldKey]
  if (typeof value === "boolean") return value ? "true" : "false"
  return String(value ?? "false").trim().toLowerCase()
})

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

function isSegmentedOptionField(field: ToolField): boolean {
  if (!(field.fieldType === "select" || field.fieldType === "radio")) return false
  const count = fieldOptions(field).length
  return count > 0 && count <= SEGMENTED_OPTION_LIMIT
}

function isSelectOptionField(field: ToolField): boolean {
  return (field.fieldType === "select" || field.fieldType === "radio") && fieldOptions(field).length > SEGMENTED_OPTION_LIMIT
}

const openSelectKey = ref<string | null>(null)

function toggleSelectDropdown(key: string) {
  openSelectKey.value = openSelectKey.value === key ? null : key
}

function selectDropdownOption(key: string, value: string) {
  if (key === "sound" && value !== "off" && hasAnyOmniVideoReferences()) return
  setField(key, value)
  openSelectKey.value = null
}

function selectedOptionLabel(field: ToolField): string {
  const current = strField(field.fieldKey)
  const match = fieldOptions(field).find((option) => optionValue(option) === current)
  if (match) return optionLabel(match)
  return field.placeholder || "请选择"
}

function closeSelectDropdownOnOutsideClick(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof Element)) return
  if (target.closest("[data-capability-select]")) return
  openSelectKey.value = null
}

onMounted(() => {
  document.addEventListener("click", closeSelectDropdownOnOutsideClick)
  document.addEventListener("click", handleComposerOutsideClick)
  window.addEventListener("resize", refreshComposerPopoverPositions)
})

onUnmounted(() => {
  document.removeEventListener("click", closeSelectDropdownOnOutsideClick)
  document.removeEventListener("click", handleComposerOutsideClick)
  window.removeEventListener("resize", refreshComposerPopoverPositions)
})

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
    if (initial !== undefined && initial !== null) {
      next.fields[field.fieldKey] = isReferenceMediaField(field)
        ? normalizeMediaFieldValue(initial)
        : initial
    } else {
      next.fields[field.fieldKey] = defaultFieldValue(field)
    }
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
  return isMediaListField(field)
}

function isSubjectElementListField(field: ToolField): boolean {
  return field.fieldType === "subject_element_list"
}

function isOmniVideoListField(field: ToolField): boolean {
  return field.fieldType === "omni_video_list"
}

function omniVideoItems(field: ToolField) {
  return parseKlingOmniVideoEditorItems(state.value.fields[field.fieldKey], klingOmniVideoMax(field))
}

function hasBaseOmniVideo(field: ToolField): boolean {
  return hasBaseKlingOmniVideo(omniVideoItems(field))
}

function hasAnyOmniVideoReferences(): boolean {
  return requestFields.value
    .filter(isOmniVideoListField)
    .some((field) => serializeKlingOmniVideoItems(omniVideoItems(field)).length > 0)
}

function isSoundLockedField(field: ToolField): boolean {
  return field.fieldKey === "sound" && hasAnyOmniVideoReferences()
}

function setOmniVideoListField(key: string, value: KlingOmniVideoReference[]) {
  const next = { ...state.value.fields, [key]: value }
  if (value.length > 0 && "sound" in next) next.sound = "off"
  state.value.fields = next
}

function multiImageLimit(field: ToolField): number {
  return isMediaListField(field) ? mediaListMax(field) : parseFieldMeta(field).maxCount ?? MULTI_IMAGE_LIMIT
}

function multiImageValues(field: ToolField): string[] {
  const value = state.value.fields[field.fieldKey]
  return parseMediaListValue(value, multiImageLimit(field))
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
  if (key === "sound" && value !== "off" && hasAnyOmniVideoReferences()) return
  state.value.fields = { ...state.value.fields, [key]: value }
}

function uploadState(key: string) {
  return fieldUploads.value[key] || {}
}

function imagePreviewUrl(field: ToolField): string {
  if (materialKindForField(field) !== "image") return ""
  return normalizeMediaUrl(strField(field.fieldKey))
}

function materialKindForField(field: ToolField): MaterialKind {
  if (field.fieldType === "video_upload" || field.fieldType === "multi_video" || field.fieldType === "omni_video_list") return "video"
  if (field.fieldType === "image" || field.fieldType === "image_upload" || field.fieldType === "multi_image") return "image"
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
  return field.fieldType === "image"
    || field.fieldType === "image_upload"
    || field.fieldType === "video_upload"
    || isMediaListField(field)
    || isOmniVideoListField(field)
    || field.fieldType === "file"
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
const fieldSections = computed(() => {
  const normalGroups = groupVisibleFields(normalFields.value).map((group) => ({
    key: group.key,
    label: group.label,
    advanced: false,
    fields: group.fields,
  }))
  const sections = [
    ...normalGroups,
    { key: "advanced", label: "高级配置", advanced: true, fields: advancedFields.value },
  ]
  return sections.filter((section) => section.fields.length > 0 || (section.advanced && customModeField.value))
})

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
  const previewUrls = urls
    .map((url) => normalizeMediaUrl(url))
    .filter((url) => isValidImagePreviewUrl(url))
  const upload = uploadState(field.fieldKey)
  return {
    available: shouldShowComposerReferenceUpload(field),
    fieldName: field.fieldName,
    kind: materialKindForField(field),
    // count 需要和 previewUrls 保持一致：避免出现“有破裂图片但 count > 0”的情况。
    count: previewUrls.length,
    maxCount: isMultiImageField(field) ? multiImageLimit(field) : 1,
    previewUrls,
    uploading: upload.uploading === true,
    error: upload.error,
  }
})

watch(primaryReferenceInfo, (info) => emit("primary-reference-change", info), { immediate: true, deep: true })
watch(composerMediaSlots, (slots) => emit("composer-media-slots-change", slots), { immediate: true, deep: true })

watch(
  () => [state.value, props.fields, props.coreFieldKey],
  () => emit("params-change", getRequestParams()),
  { deep: true, immediate: true },
)

function fieldShellClass(field: ToolField): string {
  if (isMultiImageField(field) || isSubjectElementListField(field) || isOmniVideoListField(field)) return "sm:col-span-2 lg:col-span-1"
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

function setCustomModeValue(value: string) {
  const field = customModeField.value
  if (!field) return
  const enabled = value === "true" || value === "1"
  if (field.fieldType === "checkbox") setField(field.fieldKey, enabled)
  else setField(field.fieldKey, value)
  advancedOpen.value = enabled
}

function updatePopoverPosition(anchor: HTMLElement | null, target: typeof quickPopoverStyle) {
  if (!anchor) return
  const rect = anchor.getBoundingClientRect()
  target.value = {
    position: "fixed",
    left: `${Math.max(12, Math.min(rect.left, window.innerWidth - 320))}px`,
    bottom: `${window.innerHeight - rect.top + 10}px`,
    zIndex: "140",
    width: "min(360px, calc(100vw - 24px))",
  }
}

function refreshComposerPopoverPositions() {
  if (!isComposerLayout.value) return
  if (quickParamsOpen.value) updatePopoverPosition(paramsPillRef.value, quickPopoverStyle)
  if (advancedOpen.value) updatePopoverPosition(advancedButtonRef.value, advancedPopoverStyle)
}

function toggleQuickParams() {
  quickParamsOpen.value = !quickParamsOpen.value
  if (quickParamsOpen.value) {
    advancedOpen.value = false
    nextTick(() => refreshComposerPopoverPositions())
  }
}

function toggleAdvancedOpen() {
  if (isComposerLayout.value) {
    advancedOpen.value = !advancedOpen.value
    if (advancedOpen.value) {
      quickParamsOpen.value = false
      nextTick(() => refreshComposerPopoverPositions())
    }
    return
  }
  advancedOpen.value = !advancedOpen.value
  syncCustomModeField(advancedOpen.value)
}

function fieldSummaryIcon(field: ToolField): "clock" | "film" | undefined {
  const key = `${field.fieldKey} ${field.fieldName}`.toLowerCase()
  if (/duration|时长|second|秒/.test(key)) return "clock"
  if (/count|num|number|数量|条数|片段|clip|batch/.test(key)) return "film"
  return undefined
}

function fieldToolbarSummary(field: ToolField): string {
  if (isSegmentedOptionField(field) || isSelectOptionField(field)) {
    const label = selectedOptionLabel(field)
    return label && label !== "请选择" ? label : ""
  }
  if (field.fieldType === "slider" || field.fieldType === "number") {
    const raw = state.value.fields[field.fieldKey]
    if (raw === "" || raw === undefined || raw === null) return ""
    const unit = parseFieldMeta(field).unit || ""
    const key = `${field.fieldKey} ${field.fieldName}`.toLowerCase()
    if (/duration|时长|second|秒/.test(key)) return `${raw}s`
    return `${raw}${unit}`
  }
  if (field.fieldType === "checkbox") {
    return Boolean(state.value.fields[field.fieldKey]) ? field.fieldName : ""
  }
  return ""
}

const quickComposerFields = computed(() =>
  normalFields.value.filter((field) => {
    if (!isFieldVisible(field, state.value.fields)) return false
    if (isReferenceMediaField(field)) return false
    return (
      isSegmentedOptionField(field)
      || isSelectOptionField(field)
      || field.fieldType === "slider"
      || field.fieldType === "number"
      || field.fieldType === "checkbox"
    )
  }),
)

const composerSummaryParts = computed(() => {
  const parts: Array<{ key: string; label: string; icon?: "clock" | "film" }> = []
  for (const field of quickComposerFields.value) {
    const label = fieldToolbarSummary(field)
    if (label) parts.push({ key: field.fieldKey, label, icon: fieldSummaryIcon(field) })
  }
  if (hasAspectRatioControl.value && state.value.imageRatio) {
    parts.push({ key: "__aspect_ratio__", label: aspectRatioLabel(state.value.imageRatio) })
  }
  return parts
})

const showQuickParamsButton = computed(
  () => quickComposerFields.value.length > 0 || hasAspectRatioControl.value,
)

const showAdvancedButton = computed(
  () => composerAdvancedFields.value.length > 0 || Boolean(customModeField.value),
)

const composerAdvancedFields = computed(() => {
  const extraNormal = normalFields.value.filter((field) => {
    if (!isFieldVisible(field, state.value.fields)) return false
    if (isReferenceMediaField(field)) return false
    if (quickComposerFields.value.includes(field)) return false
    return true
  })
  return [...extraNormal, ...advancedFields.value]
})

function closeComposerPopovers() {
  quickParamsOpen.value = false
  advancedOpen.value = false
}

function handleComposerOutsideClick(event: MouseEvent) {
  if (!isComposerLayout.value) return
  const target = event.target as Node | null
  if (!target) return
  if (paramsPillRef.value?.contains(target)) return
  if (advancedButtonRef.value?.contains(target)) return
  const popover = document.querySelector("[data-capability-composer-popover]")
  if (popover?.contains(target)) return
  closeComposerPopovers()
}

function formatUploadSize(size?: number): string {
  if (!size || !Number.isFinite(size)) return ""
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function uploadAssetToHistoryItem(asset: UserUploadAsset): UploadHistoryItem | null {
  if (!asset.url) return null
  const kind = materialKindFromValue(asset.kind || asset.contentType || asset.name)
  return {
    id: asset.fileId || String(asset.id),
    assetId: asset.id,
    kind,
    url: asset.url,
    previewUrl: kind === "video" ? materialVideoPreviewUrl(asset.url) : undefined,
    name: asset.name || asset.fileId || "上传素材",
    size: asset.size ?? undefined,
    type: asset.contentType ?? undefined,
    uploadedAt: asset.createdAt || new Date().toISOString(),
    toolId: props.toolId,
  }
}

async function loadUploadHistory() {
  await uploadHistoryList.resetAndLoad()
}

function rememberUploadHistoryItem(field: ToolField, item: UploadHistoryItem) {
  uploadHistoryList.rememberItem({ ...item, kind: materialKindForField(field) })
}

function extensionFromContentType(contentType: string): string {
  if (contentType.includes("webp")) return "webp"
  if (contentType.includes("png")) return "png"
  if (contentType.includes("jpeg") || contentType.includes("jpg")) return "jpg"
  if (contentType.includes("gif")) return "gif"
  return "jpg"
}

function materialFileName(asset: MaterialAsset, contentType: string): string {
  const fromUrl = asset.url.split(/[?#]/)[0]?.split("/").pop()
  const baseName = (fromUrl || asset.title || asset.id || "material").replace(/[\\/:*?"<>|]+/g, "_")
  if (/\.[a-z0-9]+$/i.test(baseName)) return baseName
  return `${baseName}.${extensionFromContentType(contentType)}`
}

async function materialAssetToFile(asset: MaterialAsset): Promise<File> {
  const response = await fetch(normalizeMediaUrl(asset.url))
  if (!response.ok) throw new Error("素材下载失败")
  const blob = await response.blob()
  const contentType = blob.type || "image/jpeg"
  if (!contentType.startsWith("image/")) throw new Error("素材不是图片")
  return new File([blob], materialFileName(asset, contentType), {
    type: contentType,
    lastModified: Date.now(),
  })
}

async function uploadMaterialAssetImage(field: ToolField, asset: MaterialAsset, startedAt: string) {
  const file = await materialAssetToFile(asset)
  const result = await uploadToolFile(file, { token: auth.token })
  rememberUploadHistoryItem(field, {
    id: result.fileId || `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    assetId: result.assetId,
    kind: "image",
    url: result.url,
    name: result.name || file.name,
    size: result.size ?? file.size,
    type: result.contentType || file.type,
    uploadedAt: startedAt,
    toolId: props.toolId,
  })
  return result.url
}

function openUploadHistoryPicker(field: ToolField) {
  uploadHistoryField.value = field
  pickerSelectedUrls.value = isMultiImageField(field) ? multiImageValues(field) : []
  uploadHistoryOpen.value = true
  void loadUploadHistory()
}

function openReferenceMaterialPicker(tab: "upload" | "material" = "upload", fieldKey?: string) {
  const field = fieldKey
    ? (props.fields || []).find((item) => item.fieldKey === fieldKey) || null
    : primaryReferenceField.value || composerMediaSlots.value[0]
      ? (props.fields || []).find((item) => item.fieldKey === composerMediaSlots.value[0]?.fieldKey) || null
      : null
  if (!field) return
  referencePickerTab.value = tab
  referencePickerOpen.value = true
  uploadHistoryField.value = field
  materialPickerField.value = field
  pickerSelectedUrls.value = isMultiImageField(field) ? multiImageValues(field) : []
  void loadUploadHistory()
  if (tab === "material") void loadGeneratedMaterialAssets()
}

function chooseReferencePickerTab(tab: "upload" | "material") {
  referencePickerTab.value = tab
  if (tab === "material") void loadGeneratedMaterialAssets()
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
  uploadHistoryList.removeItem(item)
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

function materialVideoPreviewUrl(url: string): string {
  return resolveCommunityDerivativeUrl(url, "video-poster") || normalizeMediaUrl(url)
}

function isVideoPosterPreview(url?: string | null): boolean {
  if (!url) return false
  const value = url.toLowerCase()
  return value.includes("x-oss-process=video/snapshot")
    || /\.(jpe?g|png|webp|gif)(\?|$|#)/.test(value)
    || value.includes("poster-640")
}

function materialShowsImagePreview(item: { kind: MaterialKind; previewUrl?: string }): boolean {
  if (item.kind === "image") return true
  if (!item.previewUrl) return false
  if (item.kind === "video") return isVideoPosterPreview(item.previewUrl)
  return false
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
        previewUrl: materialVideoPreviewUrl(block.url),
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
  await loadGeneratedMaterialAssets()
}

async function loadGeneratedMaterialAssets() {
  await generatedMaterialList.resetAndLoad()
}

function closeMaterialPicker() {
  materialPickerOpen.value = false
  materialPickerField.value = null
  pickerSelectedUrls.value = []
}

async function selectMaterialAsset(asset: MaterialAsset) {
  const field = materialPickerField.value
  if (!field) return
  if (isMultiImageField(field)) {
    togglePickerUrl(asset.url)
    return
  }
  const startedAt = new Date().toISOString()
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: true, fileName: asset.title },
  }
  let selectedUrl = asset.url
  if (asset.kind === "image") {
    try {
      selectedUrl = await uploadMaterialAssetImage(field, asset, startedAt)
    } catch {
      selectedUrl = asset.url
    }
  }
  setField(field.fieldKey, selectedUrl)
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: false, fileName: asset.title },
  }
  if (referencePickerOpen.value) closeReferenceMaterialPicker()
  else closeMaterialPicker()
}

function materialAssetByUrl(url: string): MaterialAsset | undefined {
  return materialAssets.value.find((asset) => asset.url === url)
}

function isMaterialSelectionContext(): boolean {
  return materialPickerOpen.value || (referencePickerOpen.value && referencePickerTab.value === "material")
}

async function resolveMaterialSelectionUrls(field: ToolField, urls: string[]): Promise<string[]> {
  if (!isMaterialSelectionContext()) return urls
  const startedAt = new Date().toISOString()
  const resolved: string[] = []
  for (const url of urls) {
    const asset = materialAssetByUrl(url)
    if (!asset || asset.kind !== "image") {
      resolved.push(url)
      continue
    }
    try {
      resolved.push(await uploadMaterialAssetImage(field, asset, startedAt))
    } catch {
      resolved.push(url)
    }
  }
  return resolved
}

async function confirmPickerSelection() {
  const field = referencePickerOpen.value ? (materialPickerField.value || uploadHistoryField.value) : uploadHistoryOpen.value ? uploadHistoryField.value : materialPickerField.value
  if (!field || !isMultiImageField(field)) return
  const selectedCount = pickerSelectedUrls.value.length
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: true, fileName: `${selectedCount} 张参考图` },
  }
  const selectedUrls = await resolveMaterialSelectionUrls(field, pickerSelectedUrls.value)
  setMultiImageValues(field, selectedUrls)
  fieldUploads.value = {
    ...fieldUploads.value,
    [field.fieldKey]: { uploading: false, fileName: `${selectedUrls.length} 张参考图` },
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

function openComposerSlotPicker(fieldKey: string) {
  openReferenceMaterialPicker("upload", fieldKey)
}

function clearComposerSlot(fieldKey: string) {
  const field = (props.fields || []).find((item) => item.fieldKey === fieldKey)
  if (field) clearUploadedField(field)
}

function removeComposerSlotAt(fieldKey: string, index = 0) {
  const field = (props.fields || []).find((item) => item.fieldKey === fieldKey)
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

function removePrimaryReferenceMaterialAt(index: number) {
  const field = primaryReferenceField.value
  if (!field) return
  removeComposerSlotAt(field.fieldKey, index)
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
    if (parseFieldMeta(field).submitPolicy === "ui_only") continue
    const value = state.value.fields[field.fieldKey]
    const maxLength = resolveMaxLength(field, state.value.fields)
    if (maxLength !== undefined && typeof value === "string" && value.length > maxLength) {
      return { valid: false, message: `${field.fieldName} 超出 ${maxLength} 字限制` }
    }
    if (uploadState(field.fieldKey).uploading) {
      return { valid: false, message: `${field.fieldName} 上传中，请稍后提交` }
    }
    if (isMultiImageField(field)) {
      const minCount = mediaListMin(field)
      const count = multiImageValues(field).length
      if (count < minCount) {
        return { valid: false, message: `${field.fieldName} 至少需要 ${minCount} ${mediaListUnitLabel(field)}` }
      }
      if (count > multiImageLimit(field)) {
        return { valid: false, message: `${field.fieldName} 最多选择 ${multiImageLimit(field)} ${mediaListUnitLabel(field)}` }
      }
    }
    if (isSubjectElementListField(field)) {
      const items = parseSubjectElementEditorItems(state.value.fields[field.fieldKey], subjectElementMax(field))
      const check = validateSubjectElementItems(items, {
        ...field,
        required: field.required,
      })
      if (!check.valid) return check
    }
    if (isOmniVideoListField(field)) {
      const items = omniVideoItems(field)
      const check = validateKlingOmniVideoItems(items, {
        ...field,
        required: field.required,
      })
      if (!check.valid) return check
    }
    if (!field.required) continue
    if (field.fieldType === "checkbox") continue
    if (isMultiImageField(field)) {
      const minCount = Math.max(1, mediaListMin(field))
      if (multiImageValues(field).length < minCount) {
        return { valid: false, message: `请填写：${field.fieldName}` }
      }
      continue
    }
    if (isSubjectElementListField(field)) {
      const items = parseSubjectElementEditorItems(state.value.fields[field.fieldKey], subjectElementMax(field))
      if (serializeSubjectElementItems(items).length === 0) {
        return { valid: false, message: `请填写：${field.fieldName}` }
      }
      continue
    }
    if (isOmniVideoListField(field)) {
      if (serializeKlingOmniVideoItems(omniVideoItems(field)).length === 0) {
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
    } else if (isSubjectElementListField(field)) {
      const items = parseSubjectElementEditorItems(value, subjectElementMax(field))
      const serialized = serializeSubjectElementItems(items)
      if (serialized.length > 0) params[field.fieldKey] = serialized
    } else if (isOmniVideoListField(field)) {
      const serialized = serializeKlingOmniVideoItems(parseKlingOmniVideoEditorItems(value, klingOmniVideoMax(field)))
      if (serialized.length > 0) params[field.fieldKey] = serialized
    } else if (field.fieldType === "number" || field.fieldType === "slider") {
      if (value !== "" && value !== undefined && value !== null && !Number.isNaN(Number(value))) {
        params[field.fieldKey] = Number(value)
      }
    } else if ((field.fieldType === "select" || field.fieldType === "radio") && value === "__none__") {
      continue
    } else if (value !== "" && value !== undefined && value !== null) {
      params[field.fieldKey] = typeof value === "string" ? value.trim() : value
    }
  }
  if (Array.isArray(params.videoList) && params.videoList.length > 0) params.sound = "off"
  if (Array.isArray(params.video_list) && params.video_list.length > 0) params.sound = "off"
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

function hasOpenOverlay(): boolean {
  return (
    referencePickerOpen.value ||
    uploadHistoryOpen.value ||
    materialPickerOpen.value ||
    quickParamsOpen.value ||
    advancedOpen.value ||
    openSelectKey.value !== null
  )
}

defineExpose({
  resetState,
  validate,
  getRequestParams,
  getAttachmentIds,
  markUploadSuccess,
  markUploadError,
  hasPendingUploads,
  hasOpenOverlay,
  closeComposerPopovers,
  openReferenceMaterialPicker,
  openComposerSlotPicker,
  clearPrimaryReferenceMaterial,
  clearComposerSlot,
  removePrimaryReferenceMaterialAt,
  removeComposerSlotAt,
  primaryReferenceInfo,
  composerMediaSlots,
})
</script>

<template>
  <div v-if="configuredFields.length > 0 || capabilities.length > 0" :class="isComposerLayout ? 'capability-controls--composer' : 'mt-2 space-y-2'">
    <div v-if="isComposerLayout" class="flex min-w-0 items-center gap-2">
      <button
        v-if="showQuickParamsButton"
        ref="paramsPillRef"
        type="button"
        class="inline-flex h-10 max-w-[min(280px,42vw)] items-center gap-1.5 overflow-hidden rounded-xl bg-white/[0.06] px-3 text-sm text-white/72 ring-1 ring-white/8 transition hover:bg-white/[0.1] hover:text-white"
        @click.stop="toggleQuickParams"
      >
        <template v-for="(part, index) in composerSummaryParts" :key="part.key">
          <span v-if="index > 0" class="text-white/22">|</span>
          <Clock v-if="part.icon === 'clock'" class="h-3.5 w-3.5 shrink-0 text-white/42" />
          <Film v-else-if="part.icon === 'film'" class="h-3.5 w-3.5 shrink-0 text-white/42" />
          <span class="truncate">{{ part.label }}</span>
        </template>
        <span v-if="composerSummaryParts.length === 0" class="text-white/45">参数</span>
        <ChevronDown class="ml-auto h-3.5 w-3.5 shrink-0 text-white/35" />
      </button>

      <button
        v-if="showAdvancedButton"
        ref="advancedButtonRef"
        type="button"
        class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white/[0.06] text-white/62 ring-1 ring-white/8 transition hover:bg-white/[0.1] hover:text-white"
        :class="advancedOpen ? 'bg-white/[0.12] text-white ring-white/16' : ''"
        aria-label="高级配置"
        title="高级配置"
        @click.stop="toggleAdvancedOpen"
      >
        <SlidersHorizontal class="h-4 w-4" />
      </button>
    </div>

    <div v-if="!isComposerLayout" class="space-y-3">
      <section v-for="section in fieldSections" :key="section.key" class="space-y-2">
        <div
          v-if="!section.advanced && section.key !== '__default__'"
          class="flex items-center justify-between"
        >
          <h3 class="text-xs font-medium text-white/62">{{ section.label }}</h3>
        </div>

        <div
          v-if="section.advanced"
          class="flex flex-wrap items-center justify-between gap-2"
        >
          <button
            type="button"
            class="inline-flex h-8 items-center gap-2 rounded-full border border-white/10 bg-white/[0.04] px-3 text-xs font-medium text-white/62 transition hover:border-purple-400/30 hover:bg-purple-500/10 hover:text-white"
            @click="toggleAdvancedOpen"
          >
            <span class="text-white/45">⚙</span>
            高级配置
            <span class="text-white/35">{{ advancedOpen ? "收起" : "展开" }}</span>
          </button>

          <div v-if="customModeField" class="flex min-w-0 items-center gap-2">
            <span class="shrink-0 text-[11px] font-medium text-white/45">{{ customModeField.fieldName || "创作模式" }}</span>
            <div class="inline-flex rounded-full border border-white/10 bg-white/[0.04] p-0.5">
              <button
                v-for="option in customModeOptions"
                :key="optionValue(option)"
                type="button"
                class="h-7 rounded-full px-3 text-xs font-medium transition"
                :class="
                  customModeValue === optionValue(option).toLowerCase()
                    ? 'bg-purple-500/20 text-purple-200 shadow-[0_0_0_1px_rgb(168_85_247_/_0.25)]'
                    : 'text-white/42 hover:bg-white/[0.06] hover:text-white/78'
                "
                @click="setCustomModeValue(optionValue(option))"
              >
                {{ optionLabel(option) }}
              </button>
            </div>
          </div>
        </div>

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
            :disabled="isSoundLockedField(field) && optionValue(option) !== 'off'"
            :class="
              strField(field.fieldKey) === optionValue(option)
                ? 'border-purple-500/30 bg-purple-500/10 text-purple-300'
                : isSoundLockedField(field) && optionValue(option) !== 'off'
                  ? 'cursor-not-allowed border-transparent bg-white/[0.03] text-white/25'
                  : 'border-transparent bg-white/[0.04] text-white/40 hover:bg-white/10 hover:text-white/80'
            "
            @click="setField(field.fieldKey, optionValue(option))"
          >
            {{ optionLabel(option) }}
          </button>
        </div>

        <div
          v-else-if="isSelectOptionField(field)"
          data-capability-select
          class="relative"
        >
          <button
            type="button"
            class="flex h-9 w-full items-center justify-between gap-2 rounded-xl border border-white/10 bg-white/[0.05] px-3 text-xs text-white/78 outline-none transition hover:border-white/18 focus:border-purple-400/40"
            @click.stop="toggleSelectDropdown(field.fieldKey)"
          >
            <span class="truncate">{{ selectedOptionLabel(field) }}</span>
            <ChevronDown
              class="h-3.5 w-3.5 shrink-0 text-white/40 transition"
              :class="openSelectKey === field.fieldKey ? 'rotate-180' : ''"
            />
          </button>
          <div
            v-if="openSelectKey === field.fieldKey"
            class="absolute left-0 right-0 top-[calc(100%+6px)] z-30 overflow-hidden rounded-xl border border-white/10 bg-[#111217]/98 p-1 shadow-[0_16px_40px_rgb(0_0_0_/_0.45)] backdrop-blur"
          >
            <button
              v-for="option in fieldOptions(field)"
              :key="optionValue(option)"
              type="button"
              class="flex w-full items-center rounded-lg px-3 py-2 text-left text-xs transition"
              :disabled="isSoundLockedField(field) && optionValue(option) !== 'off'"
              :class="
                strField(field.fieldKey) === optionValue(option)
                  ? 'bg-purple-500/20 text-purple-200'
                  : isSoundLockedField(field) && optionValue(option) !== 'off'
                    ? 'cursor-not-allowed text-white/25'
                    : 'text-white/72 hover:bg-white/[0.06] hover:text-white'
              "
              @click="selectDropdownOption(field.fieldKey, optionValue(option))"
            >
              {{ optionLabel(option) }}
            </button>
          </div>
        </div>

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
          <span class="text-[10px] text-muted-foreground">{{ state.fields[field.fieldKey] ?? sliderConfig(field).min }}{{ parseFieldMeta(field).unit || "" }}</span>
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
              <img v-if="field.fieldType !== 'multi_video'" :src="normalizeMediaUrl(url)" alt="" class="h-full w-full object-cover" />
              <video v-else :src="normalizeMediaUrl(url)" class="h-full w-full object-cover" muted playsinline preload="metadata" />
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
            已选 {{ multiImageValues(field).length }}/{{ multiImageLimit(field) }} {{ mediaListUnitLabel(field) }}
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

        <SubjectElementListField
          v-else-if="isSubjectElementListField(field)"
          :field="field"
          :model-value="state.fields[field.fieldKey]"
          compact
          @update:model-value="setField(field.fieldKey, $event)"
        />

        <div v-else-if="isOmniVideoListField(field)" class="space-y-2">
          <KlingOmniVideoListField
            :field="field"
            :model-value="state.fields[field.fieldKey]"
            compact
            @update:model-value="setOmniVideoListField(field.fieldKey, $event)"
          />
          <p v-if="hasBaseOmniVideo(field)" class="text-[11px] text-amber-300/85">
            已选择 base 参考视频：输出会按参考视频时长，时长滑杆不生效。
          </p>
        </div>

        <textarea
          v-else-if="field.fieldType === 'textarea'"
          :value="strField(field.fieldKey)"
          :placeholder="field.placeholder || field.fieldName"
          :maxlength="resolveMaxLength(field, state.fields)"
          rows="3"
          class="min-h-20 w-full resize-none rounded-lg border border-border/60 bg-background px-2 py-1.5 text-xs"
          @input="setField(field.fieldKey, ($event.target as HTMLTextAreaElement).value)"
        />

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

    <div class="space-y-1.5">
      <label v-if="hasAspectRatioControl" class="block text-[11px] font-medium text-muted-foreground">比例</label>
      <div
        v-if="hasAspectRatioControl"
        class="grid min-h-14 overflow-hidden rounded-2xl bg-white/[0.02] p-1"
        :style="{ gridTemplateColumns: `repeat(${aspectRatioOptions.length}, minmax(0, 1fr))` }"
        title="图片比例"
      >
        <button
          v-for="option in aspectRatioOptions"
          :key="option.value"
          type="button"
          class="flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-lg border border-transparent text-xs font-medium transition"
          :class="
            normalizeAspectRatio(state.imageRatio) === option.value
              ? 'border-purple-500/30 bg-purple-500/10 text-purple-300'
              : 'text-white/40 hover:bg-white/10 hover:text-white/80'
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

    <Teleport v-if="isComposerLayout" to="body">
      <div
        v-if="quickParamsOpen"
        data-capability-composer-popover
        class="overflow-hidden rounded-2xl border border-white/10 bg-[#17181d]/98 p-4 shadow-[0_24px_80px_rgb(0_0_0_/_0.55)] backdrop-blur-xl"
        :style="quickPopoverStyle"
        @click.stop
      >
        <div class="mb-3 flex items-center justify-between gap-3">
          <h4 class="text-sm font-semibold text-white">生成参数</h4>
          <button type="button" class="text-white/40 transition hover:text-white" @click="closeComposerPopovers">
            <X class="h-4 w-4" />
          </button>
        </div>
        <div class="max-h-[min(52vh,420px)] space-y-4 overflow-y-auto pr-1">
          <div v-if="hasAspectRatioControl" class="space-y-2">
            <p class="text-xs font-medium text-white/45">比例</p>
            <div
              class="grid gap-1 overflow-hidden rounded-xl bg-white/[0.03] p-1"
              :style="{ gridTemplateColumns: `repeat(${Math.min(aspectRatioOptions.length, 5)}, minmax(0, 1fr))` }"
            >
              <button
                v-for="option in aspectRatioOptions"
                :key="option.value"
                type="button"
                class="flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-lg border border-transparent px-1 py-2 text-[11px] font-medium transition"
                :class="
                  normalizeAspectRatio(state.imageRatio) === option.value
                    ? 'border-purple-500/30 bg-purple-500/10 text-purple-200'
                    : 'text-white/42 hover:bg-white/[0.06] hover:text-white/78'
                "
                @click="state.imageRatio = option.value"
              >
                <span class="truncate">{{ aspectRatioLabel(option.value) }}</span>
              </button>
            </div>
          </div>

          <div v-for="field in quickComposerFields" :key="field.fieldKey" class="space-y-2">
            <p class="text-xs font-medium text-white/45">{{ field.fieldName }}</p>
            <div v-if="isSegmentedOptionField(field)" class="flex flex-wrap gap-2">
              <button
                v-for="option in fieldOptions(field)"
                :key="optionValue(option)"
                type="button"
                class="min-h-8 rounded-full border px-3 py-1 text-xs font-medium transition"
                :disabled="isSoundLockedField(field) && optionValue(option) !== 'off'"
                :class="
                  strField(field.fieldKey) === optionValue(option)
                    ? 'border-purple-500/30 bg-purple-500/10 text-purple-200'
                    : 'border-transparent bg-white/[0.04] text-white/42 hover:bg-white/[0.06] hover:text-white/78'
                "
                @click="setField(field.fieldKey, optionValue(option))"
              >
                {{ optionLabel(option) }}
              </button>
            </div>
            <div v-else-if="isSelectOptionField(field)" data-capability-select class="relative">
              <button
                type="button"
                class="flex h-9 w-full items-center justify-between gap-2 rounded-xl border border-white/10 bg-white/[0.05] px-3 text-xs text-white/78"
                @click.stop="toggleSelectDropdown(field.fieldKey)"
              >
                <span class="truncate">{{ selectedOptionLabel(field) }}</span>
                <ChevronDown class="h-3.5 w-3.5 shrink-0 text-white/40" />
              </button>
              <div
                v-if="openSelectKey === field.fieldKey"
                class="absolute left-0 right-0 top-[calc(100%+6px)] z-30 overflow-hidden rounded-xl border border-white/10 bg-[#111217]/98 p-1 shadow-2xl"
              >
                <button
                  v-for="option in fieldOptions(field)"
                  :key="optionValue(option)"
                  type="button"
                  class="flex w-full items-center rounded-lg px-3 py-2 text-left text-xs text-white/72 hover:bg-white/[0.06]"
                  @click="selectDropdownOption(field.fieldKey, optionValue(option))"
                >
                  {{ optionLabel(option) }}
                </button>
              </div>
            </div>
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
              <span class="text-[11px] text-white/40">
                {{ state.fields[field.fieldKey] ?? sliderConfig(field).min }}{{ parseFieldMeta(field).unit || "" }}
              </span>
            </div>
            <label
              v-else-if="field.fieldType === 'checkbox'"
              class="inline-flex h-8 items-center gap-2 rounded-lg border border-white/10 bg-white/[0.04] px-3 text-xs text-white/72"
            >
              <input
                type="checkbox"
                :checked="Boolean(state.fields[field.fieldKey])"
                class="rounded border-white/20"
                @change="setField(field.fieldKey, ($event.target as HTMLInputElement).checked)"
              />
              {{ field.placeholder || "启用" }}
            </label>
            <input
              v-else-if="field.fieldType === 'number'"
              type="number"
              :value="strField(field.fieldKey)"
              class="h-9 w-full rounded-xl border border-white/10 bg-white/[0.05] px-3 text-xs text-white/78"
              @input="onNumberInput(field.fieldKey, $event)"
            />
          </div>
        </div>
      </div>

      <div
        v-if="advancedOpen"
        data-capability-composer-popover
        class="overflow-hidden rounded-2xl border border-white/10 bg-[#17181d]/98 p-4 shadow-[0_24px_80px_rgb(0_0_0_/_0.55)] backdrop-blur-xl"
        :style="advancedPopoverStyle"
        @click.stop
      >
        <div class="mb-3 flex items-center justify-between gap-3">
          <h4 class="text-sm font-semibold text-white">高级配置</h4>
          <button type="button" class="text-white/40 transition hover:text-white" @click="closeComposerPopovers">
            <X class="h-4 w-4" />
          </button>
        </div>
        <div v-if="customModeField" class="mb-4 flex flex-wrap items-center gap-2">
          <span class="text-xs font-medium text-white/45">{{ customModeField.fieldName || "创作模式" }}</span>
          <div class="inline-flex rounded-full border border-white/10 bg-white/[0.04] p-0.5">
            <button
              v-for="option in customModeOptions"
              :key="optionValue(option)"
              type="button"
              class="h-7 rounded-full px-3 text-xs font-medium transition"
              :class="
                customModeValue === optionValue(option).toLowerCase()
                  ? 'bg-purple-500/20 text-purple-200'
                  : 'text-white/42 hover:bg-white/[0.06] hover:text-white/78'
              "
              @click="setCustomModeValue(optionValue(option))"
            >
              {{ optionLabel(option) }}
            </button>
          </div>
        </div>
        <div class="max-h-[min(58vh,480px)] space-y-4 overflow-y-auto pr-1">
          <div v-for="field in composerAdvancedFields" :key="field.fieldKey" class="space-y-2">
            <p class="text-xs font-medium text-white/45">
              {{ field.fieldName }}<span v-if="field.required" class="text-red-300"> *</span>
            </p>
            <div v-if="isSegmentedOptionField(field)" class="flex flex-wrap gap-2">
              <button
                v-for="option in fieldOptions(field)"
                :key="optionValue(option)"
                type="button"
                class="min-h-8 rounded-full border px-3 py-1 text-xs font-medium transition"
                :class="
                  strField(field.fieldKey) === optionValue(option)
                    ? 'border-purple-500/30 bg-purple-500/10 text-purple-200'
                    : 'border-transparent bg-white/[0.04] text-white/42 hover:bg-white/[0.06] hover:text-white/78'
                "
                @click="setField(field.fieldKey, optionValue(option))"
              >
                {{ optionLabel(option) }}
              </button>
            </div>
            <div v-else-if="isSelectOptionField(field)" data-capability-select class="relative">
              <button
                type="button"
                class="flex h-9 w-full items-center justify-between gap-2 rounded-xl border border-white/10 bg-white/[0.05] px-3 text-xs text-white/78"
                @click.stop="toggleSelectDropdown(field.fieldKey)"
              >
                <span class="truncate">{{ selectedOptionLabel(field) }}</span>
                <ChevronDown class="h-3.5 w-3.5 shrink-0 text-white/40" />
              </button>
              <div
                v-if="openSelectKey === field.fieldKey"
                class="absolute left-0 right-0 top-[calc(100%+6px)] z-30 overflow-hidden rounded-xl border border-white/10 bg-[#111217]/98 p-1 shadow-2xl"
              >
                <button
                  v-for="option in fieldOptions(field)"
                  :key="optionValue(option)"
                  type="button"
                  class="flex w-full items-center rounded-lg px-3 py-2 text-left text-xs text-white/72 hover:bg-white/[0.06]"
                  @click="selectDropdownOption(field.fieldKey, optionValue(option))"
                >
                  {{ optionLabel(option) }}
                </button>
              </div>
            </div>
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
            </div>
            <textarea
              v-else-if="field.fieldType === 'textarea'"
              :value="strField(field.fieldKey)"
              :placeholder="field.placeholder || field.fieldName"
              rows="3"
              class="min-h-20 w-full resize-none rounded-xl border border-white/10 bg-white/[0.05] px-3 py-2 text-xs text-white/78"
              @input="setField(field.fieldKey, ($event.target as HTMLTextAreaElement).value)"
            />
            <input
              v-else
              type="text"
              :value="strField(field.fieldKey)"
              :placeholder="shortPlaceholder(field)"
              class="h-9 w-full rounded-xl border border-white/10 bg-white/[0.05] px-3 text-xs text-white/78"
              @input="setField(field.fieldKey, ($event.target as HTMLInputElement).value)"
            />
          </div>
          <p v-if="composerAdvancedFields.length === 0 && !customModeField" class="text-sm text-white/40">暂无高级配置项</p>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="referencePickerOpen"
        data-capability-overlay
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

          <div v-if="referencePickerTab === 'upload'" ref="referenceUploadScrollRootRef" class="min-h-[420px] overflow-y-auto p-5">
            <label
              class="flex min-h-40 cursor-pointer flex-col items-center justify-center gap-4 rounded-2xl border border-dashed border-white/18 bg-white/[0.035] text-white/72 transition hover:border-primary/60 hover:bg-white/[0.055]"
              @dragover.prevent
              @drop.prevent="uploadHistoryField && handleUploadHistoryFile(($event as DragEvent).dataTransfer?.files || null)"
            >
              <UploadCloud class="h-8 w-8 text-white/70" />
              <span class="text-base font-semibold">{{ uploadHistoryUploading ? "上传中..." : referenceUploadHint }}</span>
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
              <div v-if="uploadHistoryLoading && uploadHistoryItems.length === 0" class="flex h-40 flex-col items-center justify-center rounded-2xl border border-white/8 bg-white/[0.03] text-center text-sm text-white/42">
                <Loader2 class="mb-3 h-7 w-7 animate-spin text-white/30" />
                <p>正在加载上传历史...</p>
              </div>
              <div v-else-if="uploadHistoryItems.length === 0" class="flex h-40 flex-col items-center justify-center rounded-2xl border border-white/8 bg-white/[0.03] text-center text-sm text-white/42">
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
                        v-if="materialShowsImagePreview(item)"
                        :src="normalizeMediaUrl(item.previewUrl || item.url)"
                        alt=""
                        class="h-full w-full object-cover"
                      />
                      <video
                        v-else-if="item.kind === 'video' && item.previewUrl"
                        :src="normalizeMediaUrl(item.previewUrl)"
                        class="h-full w-full object-cover"
                        muted
                        playsinline
                        preload="metadata"
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
              <div v-if="uploadHistoryLoadingMore" class="flex items-center justify-center gap-2 py-4 text-sm text-white/45">
                <Loader2 class="h-4 w-4 animate-spin" />
                加载更多...
              </div>
              <div v-else-if="!uploadHistoryHasMore && uploadHistoryItems.length > 0" class="py-4 text-center text-xs text-white/30">
                已加载全部
              </div>
              <div ref="referenceUploadSentinelRef" class="h-1" />
            </section>
          </div>

          <div v-else ref="referenceMaterialScrollRootRef" class="min-h-[420px] overflow-y-auto p-5">
            <div class="mb-4 flex items-center justify-between">
              <div class="flex items-center gap-2 text-sm font-semibold text-white/70">
                <BookOpen class="h-4 w-4" />
                已生成素材
              </div>
              <button type="button" class="rounded-full border border-white/10 px-3 py-1 text-xs text-white/45 transition hover:border-white/20 hover:text-white" @click="loadGeneratedMaterialAssets()">
                刷新
              </button>
            </div>
            <div v-if="materialLoading && materialAssets.length === 0" class="flex h-56 items-center justify-center gap-2 text-sm text-white/45">
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
                    v-if="materialShowsImagePreview(asset)"
                    :src="normalizeMediaUrl(asset.previewUrl!)"
                    alt=""
                    class="h-full w-full object-cover"
                  />
                  <video
                    v-else-if="asset.kind === 'video' && asset.previewUrl"
                    :src="normalizeMediaUrl(asset.previewUrl)"
                    class="h-full w-full object-cover"
                    muted
                    playsinline
                    preload="metadata"
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
            <div v-if="materialLoadingMore" class="flex items-center justify-center gap-2 py-4 text-sm text-white/45">
              <Loader2 class="h-4 w-4 animate-spin" />
              加载更多...
            </div>
            <div v-else-if="!materialHasMore && materialAssets.length > 0" class="py-4 text-center text-xs text-white/30">
              已加载全部
            </div>
            <div ref="referenceMaterialSentinelRef" class="h-1" />
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
        data-capability-overlay
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

          <div ref="standaloneUploadScrollRootRef" class="min-h-[260px] overflow-y-auto p-6">
            <div v-if="uploadHistoryLoading && uploadHistoryItems.length === 0" class="flex h-56 flex-col items-center justify-center text-center text-sm text-white/45">
              <Loader2 class="mb-3 h-8 w-8 animate-spin text-white/25" />
              <p>正在加载上传历史...</p>
            </div>
            <div v-else-if="uploadHistoryItems.length === 0" class="flex h-56 flex-col items-center justify-center text-center text-sm text-white/45">
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
                      v-if="materialShowsImagePreview(item)"
                      :src="normalizeMediaUrl(item.previewUrl || item.url)"
                      alt=""
                      class="h-full w-full object-cover"
                    />
                    <video
                      v-else-if="item.kind === 'video' && item.previewUrl"
                      :src="normalizeMediaUrl(item.previewUrl)"
                      class="h-full w-full object-cover"
                      muted
                      playsinline
                      preload="metadata"
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
            <div v-if="uploadHistoryLoadingMore" class="flex items-center justify-center gap-2 py-4 text-sm text-white/45">
              <Loader2 class="h-4 w-4 animate-spin" />
              加载更多...
            </div>
            <div v-else-if="!uploadHistoryHasMore && uploadHistoryItems.length > 0" class="py-4 text-center text-xs text-white/30">
              已加载全部
            </div>
            <div ref="standaloneUploadSentinelRef" class="h-1" />
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
        data-capability-overlay
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

          <div ref="standaloneMaterialScrollRootRef" class="min-h-[260px] overflow-y-auto p-6">
            <div v-if="materialLoading && materialAssets.length === 0" class="flex h-56 items-center justify-center gap-2 text-sm text-white/45">
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
                    v-if="materialShowsImagePreview(asset)"
                    :src="normalizeMediaUrl(asset.previewUrl!)"
                    alt=""
                    class="h-full w-full object-cover"
                  />
                  <video
                    v-else-if="asset.kind === 'video' && asset.previewUrl"
                    :src="normalizeMediaUrl(asset.previewUrl)"
                    class="h-full w-full object-cover"
                    muted
                    playsinline
                    preload="metadata"
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
            <div v-if="materialLoadingMore" class="flex items-center justify-center gap-2 py-4 text-sm text-white/45">
              <Loader2 class="h-4 w-4 animate-spin" />
              加载更多...
            </div>
            <div v-else-if="!materialHasMore && materialAssets.length > 0" class="py-4 text-center text-xs text-white/30">
              已加载全部
            </div>
            <div ref="standaloneMaterialSentinelRef" class="h-1" />
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
