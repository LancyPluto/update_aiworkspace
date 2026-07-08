<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue"
import {
  Bot,
  ChevronDown,
  Clapperboard,
  Clock3,
  FileUp,
  ImageIcon,
  Mic2,
  Monitor,
  Music2,
  Plus,
  Play,
  RectangleHorizontal,
  RectangleVertical,
  Settings2,
  Sparkles,
  Square,
  AtSign,
  Video,
  X,
  ZoomIn,
} from "lucide-vue-next"
import { uploadChatFile } from "@/api/aiToolApi"
import { fetchModelOptions } from "@/api/modelOptionsApi"
import type { ToolDetail, ToolSummary } from "@/api/types"
import { useTaskEstimate, type UseTaskEstimateInput } from "@/composables/useTaskEstimate"
import { formatLiveCreditEstimate } from "@/utils/toolCreditLabel"
import {
  buildComposerFormatOptions,
  buildComposerModelGroupsFromResponse,
  buildComposerModelGroupsFromTools,
  buildComposerModelOptions,
  type ComposerFormatValueOption,
  type ComposerModelGroup,
  type ComposerModelOption,
  type CreatorMode,
  type ComposerState,
} from "@/adapters/creatorAdapter"

const props = withDefaults(defineProps<{
  title?: string
  compact?: boolean
  /** 首页等场景：输入区高度与 /create 底栏一致 */
  dockSized?: boolean
  initialMode?: CreatorMode
  showModeTabs?: boolean
  disabled?: boolean
  submitting?: boolean
  costLabel?: string
  modeOptions?: Array<{ key: CreatorMode; label: string }>
  toolId?: string | null
  tools?: ToolSummary[]
  toolDetail?: ToolDetail | null
  toolsLoading?: boolean
  initialPrompt?: string
  initialRatio?: string | null
  initialDurationSeconds?: number | null
  initialQuality?: string | null
  initialOutputCount?: number | null
  initialUploadedAssetUrl?: string | null
  initialUploadedAssetName?: string
  initialModelConfigId?: number | null
  resetKey?: number
}>(), {
  title: "思维不停，创作不止",
  compact: false,
  dockSized: false,
  initialMode: "video",
  showModeTabs: true,
  disabled: false,
  submitting: false,
  costLabel: "生成",
  modeOptions: () => [],
  tools: () => [],
  toolDetail: null,
  toolsLoading: false,
  initialPrompt: "",
  initialRatio: null,
  initialDurationSeconds: null,
  initialQuality: null,
  initialOutputCount: null,
  initialUploadedAssetUrl: null,
  initialUploadedAssetName: "",
  initialModelConfigId: null,
  resetKey: 0,
})

const emit = defineEmits<{
  modeChange: [mode: CreatorMode]
  toolSelect: [toolCode: string]
  submit: [state: ComposerState]
}>()

type UploadedComposerAssetKind = "image" | "video" | "audio" | "file"

interface UploadedComposerAsset {
  id: string
  url: string
  name: string
  kind: UploadedComposerAssetKind
}

const defaultModes = [
  { key: "video", label: "视频", icon: Clapperboard },
  { key: "image", label: "图片", icon: ImageIcon },
  { key: "agent", label: "智能体", icon: Bot },
] as const
const modeIconMap = {
  video: Clapperboard,
  image: ImageIcon,
  agent: Bot,
  digitalHuman: Bot,
  audio: Mic2,
} as const
const modes = computed(() => {
  const options = props.modeOptions.length ? props.modeOptions : defaultModes
  return options.map((item) => ({ ...item, icon: modeIconMap[item.key] || Bot }))
})

const mode = ref<CreatorMode>(props.initialMode)
const activeModeIndex = computed(() => {
  const index = modes.value.findIndex((item) => item.key === mode.value)
  return index >= 0 ? index : 0
})
const modeTabRootRef = ref<HTMLElement | null>(null)
const modeTabWidth = ref(0)
const modeTabStyle = computed(() => ({
  "--workspace-mode-count": modes.value.length,
  "--workspace-mode-index": activeModeIndex.value,
}))
const modeTabIndicatorStyle = computed(() => ({
  transform: `translate3d(${activeModeIndex.value * modeTabWidth.value}px, 0, 0)`,
}))
const prompt = ref(props.initialPrompt)
const generationType = ref("文本/图像生成视频")
const selectedModelKey = ref("auto")
const selectedModelGroupKey = ref("")
const modelOptionGroups = ref<ComposerModelGroup[]>([])
const modelOptionsLoading = ref(false)
const modelOptionsError = ref("")
let modelOptionsAbortController: AbortController | null = null
const ratio = ref(props.initialRatio || "16:9")
const imageRatio = ref(props.initialRatio || "1:1")
const imageCount = ref(props.initialOutputCount || 1)
const duration = ref(props.initialDurationSeconds || 5)
const quality = ref(props.initialQuality || "720p")
const openMenu = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploadedAssetUrl = ref<string | null>(props.initialUploadedAssetUrl)
const uploadedAssets = ref<UploadedComposerAsset[]>(
  props.initialUploadedAssetUrl
    ? [{
        id: `initial:${props.initialUploadedAssetUrl}`,
        url: props.initialUploadedAssetUrl,
        name: props.initialUploadedAssetName || "参考素材",
        kind: inferAssetKind(props.initialUploadedAssetUrl, props.initialUploadedAssetName),
      }]
    : [],
)
const uploadError = ref("")
const uploading = ref(false)
const dragActive = ref(false)
const previewAsset = ref<UploadedComposerAsset | null>(null)
const mentionMenuOpen = ref(false)

const generationOptions = computed(() => {
  if (mode.value === "image") return ["文本/图像生成图片", "文生图像", "图生图像", "图片编辑"]
  if (mode.value === "digitalHuman") return ["数字人口播", "数字人视频", "形象讲解", "商品口播"]
  if (mode.value === "audio") return ["文本转语音", "AI 配音", "音频生成", "声音克隆"]
  if (mode.value === "agent") return ["自动", "复刻视频广告", "故事视频", "UGC 视频广告"]
  return ["文本/图像生成视频", "文生视频", "图生视频", "参考生成视频", "视频转视频"]
})

const legacyModelOptions = computed(() => buildComposerModelOptions(props.tools, mode.value))
const fallbackModelGroups = computed(() => buildComposerModelGroupsFromTools(props.tools, mode.value))
const groupedModelOptions = computed(() => modelOptionGroups.value.length ? modelOptionGroups.value : fallbackModelGroups.value)
const activeModelGroup = computed(() => {
  const groups = groupedModelOptions.value
  return groups.find((group) => group.key === selectedModelGroupKey.value) || groups[0] || null
})
const modelOptions = computed(() => {
  const automatic = legacyModelOptions.value[0] || { key: "auto", label: "自动选择", auto: true }
  return [automatic, ...groupedModelOptions.value.flatMap((group) => group.models)]
})
const configuredModelOptions = computed(() => modelOptions.value.filter((option) => !option.auto && (option.toolCode || option.modelConfigId)))
const defaultConfiguredModelOption = computed(() => {
  return (
    configuredModelOptions.value.find((option) => props.initialModelConfigId && option.modelConfigId === props.initialModelConfigId) ||
    configuredModelOptions.value.find((option) => option.toolCode && option.toolCode === props.toolId) ||
    configuredModelOptions.value[0] ||
    null
  )
})
const selectedModelOption = computed(() => {
  return (
    modelOptions.value.find((option) => option.key === selectedModelKey.value) ||
    modelOptions.value.find((option) => props.initialModelConfigId && option.modelConfigId === props.initialModelConfigId) ||
    modelOptions.value.find((option) => option.toolCode && option.toolCode === props.toolId) ||
    modelOptions.value[0]
  )
})
const effectiveSelectedModelOption = computed(() => {
  if (!selectedModelOption.value?.auto) return selectedModelOption.value
  return defaultConfiguredModelOption.value || selectedModelOption.value || null
})
const selectedModelLabel = computed(() => {
  if (props.toolsLoading || modelOptionsLoading.value) return "加载中"
  if (configuredModelOptions.value.length === 0) {
    if (mode.value === "image") return "暂无图片模型"
    if (mode.value === "digitalHuman") return "暂无数字人工具"
    if (mode.value === "audio") return "暂无音频工具"
    return "暂无视频模型"
  }
  return effectiveSelectedModelOption.value?.label || "自动选择"
})
const selectedToolCode = computed(() =>
  effectiveSelectedModelOption.value?.toolCode ||
  selectedModelOption.value?.toolCode ||
  props.toolId ||
  legacyModelOptions.value[0]?.toolCode ||
  undefined,
)
const configuredFormatOptions = computed(() =>
  buildComposerFormatOptions(props.toolDetail?.fields || [], effectiveSelectedModelOption.value?.imageParameters),
)
const durationOptions = computed(() => configuredFormatOptions.value.duration)
const qualityOptions = computed(() => configuredFormatOptions.value.quality)
const ratioOptions = computed(() => configuredFormatOptions.value.ratio)
const imageRatioOptions = computed(() => configuredFormatOptions.value.ratio)
const imageQualityOptions = computed(() => configuredFormatOptions.value.quality)
const imageCountOptions = computed(() => configuredFormatOptions.value.count)
const formatLabels = computed(() => configuredFormatOptions.value.labels)
const hasComposerInput = computed(() => prompt.value.trim().length > 0 || uploadedAssets.value.length > 0)
const hasVideoReference = computed(() =>
  uploadedAssets.value.some((asset) => asset.kind === "video") ||
  /(^|\s)@(video|视频|素材视频)(?=\s|$|[，,。.!！?？])/i.test(prompt.value),
)
const requiresVideoInput = computed(() =>
  (props.toolDetail?.fields || []).some((field) => {
    if (!field.required) return false
    if (field.fieldType === "video_upload") return true
    const text = `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
    return /video|clip|movie|视频|短片|影片/.test(text)
  }),
)
const uploadKinds = computed<UploadedComposerAssetKind[]>(() => {
  const fields = props.toolDetail?.fields || []
  const kinds = new Set<UploadedComposerAssetKind>()
  for (const field of fields) {
    if (field.fieldType === "image" || field.fieldType === "image_upload" || field.fieldType === "multi_image") kinds.add("image")
    if (field.fieldType === "video_upload") kinds.add("video")
    if (field.fieldType === "audio_upload") kinds.add("audio")
    if (field.fieldType === "file") kinds.add("file")
  }
  if (kinds.size > 0) return [...kinds]
  if (mode.value === "image") return ["image"]
  if (mode.value === "audio") return ["audio"]
  if (mode.value === "agent") return ["image", "video", "audio", "file"]
  return ["image", "video"]
})
const uploadHint = computed(() => {
  const labels: Record<UploadedComposerAssetKind, string> = {
    image: "图片",
    video: "视频",
    audio: "音频",
    file: "文件",
  }
  return `支持上传${uploadKinds.value.map((kind) => labels[kind]).join("、")}`
})
const mentionOptions = computed(() => {
  const options: Array<{ kind: "image" | "video"; label: string }> = []
  if (uploadKinds.value.includes("image")) options.push({ kind: "image", label: "@图片" })
  if (uploadKinds.value.includes("video") || requiresVideoInput.value) options.push({ kind: "video", label: "@视频" })
  return options
})
const videoInputHint = computed(() =>
  requiresVideoInput.value && !hasVideoReference.value
    ? "当前模型需要视频输入，请上传视频素材，或在提示词里输入 @视频。"
    : "",
)
const canGenerate = computed(() =>
  hasComposerInput.value &&
  Boolean(selectedToolCode.value) &&
  (!requiresVideoInput.value || hasVideoReference.value) &&
  !props.disabled &&
  !props.submitting &&
  !uploading.value,
)
const visibleUploadedAssets = computed(() => uploadedAssets.value.slice(0, 4))
const hiddenUploadedAssetCount = computed(() => Math.max(0, uploadedAssets.value.length - visibleUploadedAssets.value.length))
/** 已添加第二个及以上素材后，加号框保持放大状态 */
const addCardExpanded = computed(() => uploadedAssets.value.length >= 2)
const uploadDisabled = computed(() => props.disabled || props.submitting || uploading.value)
const durationLabel = computed(() => optionDisplayLabel(durationOptions.value, duration.value, `${duration.value}s`))
const qualityLabel = computed(() => optionDisplayLabel(qualityOptions.value, quality.value, quality.value))
const videoRatioLabel = computed(() => optionDisplayLabel(ratioOptions.value, ratio.value, ratio.value))
const imageRatioLabel = computed(() => optionDisplayLabel(imageRatioOptions.value, imageRatio.value, imageRatio.value))
const imageSizeChipLabel = computed(() => imageSizeAspectLabel(imageRatio.value, imageRatioLabel.value))
const imageSizeSectionLabel = computed(() =>
  imageRatioOptions.value.some((option) => imageSizeDimensionLabel(option.value))
    ? "长宽比"
    : formatLabels.value.ratio,
)
const imageCountLabel = computed(() => optionDisplayLabel(imageCountOptions.value, imageCount.value, String(imageCount.value)))
const promptPlaceholder = computed(() => {
  if (mode.value === "audio") return "输入配音文案、音色要求或音频处理想法"
  if (mode.value === "digitalHuman") return "输入数字人口播脚本、场景和形象要求"
  if (mode.value === "agent") return "分享你的创意想法，或上传素材/链接，剩下的交给 AI 来完成。"
  return "输入灵感，即刻创作！"
})
const agentIdeas = ["复刻视频广告", "故事视频", "动漫视频", "爆款短视频", "UGC 视频广告", "解说视频", "音乐视频", "新闻视频"]

function cleanLabel(value?: string | null): string {
  return (value || "").replace(/\s+/g, " ").trim()
}

function vendorInitial(group: ComposerModelGroup | null | undefined): string {
  const label = cleanLabel(group?.label || group?.key || "AI")
  return label.slice(0, 1).toUpperCase() || "AI"
}

function vendorIconSrc(group: ComposerModelGroup | null | undefined): string {
  if (group?.iconUrl) return group.iconUrl
  const key = cleanLabel(group?.key).toLowerCase()
  return key ? `/assets/vendor-icons/${key}.svg` : ""
}

function modelIconSrc(item: ComposerModelOption): string {
  return item.iconUrl || vendorIconSrc(activeModelGroup.value)
}

function onModelIconError(event: Event) {
  const target = event.currentTarget as HTMLImageElement | null
  if (target) target.style.display = "none"
}

function modelBadges(item: ComposerModelOption): string[] {
  const badges = [...(item.badges || [])]
  if (item.isDefault && !badges.some((badge) => badge.includes("默认"))) badges.unshift("默认")
  if (typeof item.estimatedCreditCost === "number" && item.estimatedCreditCost === 0) badges.push("免费")
  return badges.slice(0, 2)
}

function modelDescription(item: ComposerModelOption): string {
  const description = cleanLabel(item.description)
  if (description) return description
  const sizes = item.imageParameters?.sizes?.length || 0
  const counts = item.imageParameters?.counts || []
  const maxCount = counts.length ? Math.max(...counts) : 0
  if (mode.value === "image") {
    if (sizes && maxCount) return `支持 ${sizes} 种图片尺寸，单次最多 ${maxCount} 张输出`
    if (sizes) return `支持 ${sizes} 种图片尺寸`
    return "适合生成图片素材"
  }
  if (mode.value === "video") return "支持文本或参考素材生成视频"
  if (mode.value === "audio") return "适合生成语音或音频内容"
  if (mode.value === "digitalHuman") return "适合生成数字人内容"
  return "系统配置模型"
}

function modelMetaItems(item: ComposerModelOption): string[] {
  const metas: string[] = []
  const sizes = item.imageParameters?.sizes?.length || 0
  const counts = item.imageParameters?.counts || []
  if (mode.value === "image" && sizes) metas.push(`${sizes}+ 尺寸`)
  if (mode.value === "image" && counts.length) metas.push(`${Math.max(...counts)} 张`)
  if (mode.value === "video") metas.push("视频生成")
  if (item.variableCreditPricing) metas.push("算力不详")
  else if (typeof item.estimatedCreditCost === "number") metas.push(`${item.estimatedCreditCost} 算力`)
  if (!metas.length && item.modelName) metas.push(item.modelName)
  return metas.slice(0, 3)
}

function optionDisplayLabel<T extends string | number>(
  options: ComposerFormatValueOption<T>[],
  value: T,
  fallback: string,
): string {
  return options.find((option) => option.value === value)?.label || fallback
}

function firstValue<T extends string | number>(options: ComposerFormatValueOption<T>[]): T | undefined {
  return options[0]?.value
}

function greatestCommonDivisor(a: number, b: number): number {
  let x = Math.abs(a)
  let y = Math.abs(b)
  while (y > 0) {
    const next = x % y
    x = y
    y = next
  }
  return x || 1
}

function parseImageSize(value: string): { width: number; height: number } | null {
  const match = value.trim().match(/^(\d{2,5})\s*[x×]\s*(\d{2,5})$/i)
  if (!match) return null
  const width = Number(match[1])
  const height = Number(match[2])
  return width > 0 && height > 0 ? { width, height } : null
}

function parseAspectValue(value: string): { width: number; height: number } | null {
  const size = parseImageSize(value)
  if (size) return size
  const match = value.trim().match(/^(\d{1,3})\s*:\s*(\d{1,3})$/)
  if (!match) return null
  const width = Number(match[1])
  const height = Number(match[2])
  return width > 0 && height > 0 ? { width, height } : null
}

function imageSizeAspectLabel(value: string, fallback = value): string {
  const ratio = parseAspectValue(value)
  if (!ratio) return fallback
  const divisor = greatestCommonDivisor(ratio.width, ratio.height)
  return `${ratio.width / divisor}:${ratio.height / divisor}`
}

function imageSizeDimensionLabel(value: string): string {
  const size = parseImageSize(value)
  return size ? `${size.width}x${size.height}` : ""
}

function imageSizeIconKind(value: string): "square" | "landscape" | "portrait" {
  const ratio = parseAspectValue(value)
  if (!ratio || ratio.width === ratio.height) return "square"
  return ratio.width > ratio.height ? "landscape" : "portrait"
}

function syncFormatDefaults() {
  const defaults = configuredFormatOptions.value.defaults
  if (mode.value === "image") {
    const nextRatio = props.initialRatio || defaults.ratio || firstValue(imageRatioOptions.value)
    if (nextRatio && !imageRatioOptions.value.some((option) => option.value === imageRatio.value)) {
      imageRatio.value = nextRatio
    }
    const nextCount = props.initialOutputCount || defaults.count || firstValue(imageCountOptions.value)
    if (nextCount && !imageCountOptions.value.some((option) => option.value === imageCount.value)) {
      imageCount.value = nextCount
    }
    const nextQuality = props.initialQuality || defaults.quality || firstValue(imageQualityOptions.value)
    if (nextQuality && !imageQualityOptions.value.some((option) => option.value === quality.value)) {
      quality.value = nextQuality
    }
    return
  }

  const nextDuration = props.initialDurationSeconds || defaults.duration || firstValue(durationOptions.value)
  if (nextDuration && !durationOptions.value.some((option) => option.value === duration.value)) {
    duration.value = nextDuration
  }
  const nextQuality = props.initialQuality || defaults.quality || firstValue(qualityOptions.value)
  if (nextQuality && !qualityOptions.value.some((option) => option.value === quality.value)) {
    quality.value = nextQuality
  }
  const nextRatio = props.initialRatio || defaults.ratio || firstValue(ratioOptions.value)
  if (nextRatio && !ratioOptions.value.some((option) => option.value === ratio.value)) {
    ratio.value = nextRatio
  }
}

function openFormatMenu() {
  if (mode.value === "image") return
  openMenu.value = openMenu.value === "format" ? null : "format"
}

function openImageFormatMenu() {
  if (mode.value !== "image") return
  openMenu.value = openMenu.value === "imageFormat" ? null : "imageFormat"
}

function applyMode(nextMode: CreatorMode, emitChange: boolean) {
  mode.value = nextMode
  generationType.value = generationOptions.value[0]
  selectedModelKey.value = "auto"
  openMenu.value = null
  if (emitChange) emit("modeChange", nextMode)
}

function selectMode(nextMode: CreatorMode) {
  applyMode(nextMode, true)
}

let modeTabResizeObserver: ResizeObserver | null = null

function updateModeTabWidth() {
  const root = modeTabRootRef.value
  if (!root) {
    modeTabWidth.value = 0
    return
  }
  const count = Math.max(modes.value.length, 1)
  modeTabWidth.value = Math.max(0, (root.clientWidth - 8) / count)
}

function currentState(): ComposerState {
  const selectedQuality = mode.value === "image"
    ? (imageQualityOptions.value.length ? quality.value : undefined)
    : quality.value

  return {
    mode: mode.value,
    prompt: prompt.value,
    generationType: generationType.value,
    modelLabel: selectedModelLabel.value,
    toolCode: selectedToolCode.value,
    modelConfigId: effectiveSelectedModelOption.value?.modelConfigId ?? null,
    ratio: mode.value === "image" ? imageRatio.value : ratio.value,
    durationSeconds: mode.value === "image" ? undefined : duration.value,
    quality: selectedQuality,
    outputCount: mode.value === "image" ? imageCount.value : undefined,
    uploadedAssetUrl: uploadedAssetUrl.value,
    uploadedAssetUrls: uploadedAssets.value.map((asset) => asset.url),
  }
}

async function loadModelOptionsForMode(nextMode: CreatorMode = mode.value) {
  modelOptionsAbortController?.abort()
  const controller = new AbortController()
  modelOptionsAbortController = controller
  modelOptionsLoading.value = true
  modelOptionsError.value = ""
  try {
    const response = await fetchModelOptions(nextMode, { signal: controller.signal })
    if (controller.signal.aborted) return
    modelOptionGroups.value = buildComposerModelGroupsFromResponse(response)
    selectedModelGroupKey.value = modelOptionGroups.value[0]?.key || fallbackModelGroups.value[0]?.key || ""
  } catch (error) {
    if (controller.signal.aborted) return
    modelOptionGroups.value = []
    selectedModelGroupKey.value = fallbackModelGroups.value[0]?.key || ""
    modelOptionsError.value = error instanceof Error ? error.message : "模型列表加载失败"
  } finally {
    if (!controller.signal.aborted) modelOptionsLoading.value = false
  }
}

function selectModel(key: string) {
  selectedModelKey.value = key
  openMenu.value = null
  const option = modelOptions.value.find((item) => item.key === key)
  if (option?.toolCode) emit("toolSelect", option.toolCode)
}

function selectModelGroup(key: string) {
  selectedModelGroupKey.value = key
}

function applyInitialModelConfigId() {
  if (!props.initialModelConfigId) return false
  const matched = modelOptions.value.find((option) => option.modelConfigId === props.initialModelConfigId)
  if (!matched) return false
  selectedModelKey.value = matched.key
  const group = groupedModelOptions.value.find((item) => item.models.some((model) => model.key === matched.key))
  if (group) selectedModelGroupKey.value = group.key
  return true
}

function inferAssetKind(url: string, name = ""): UploadedComposerAssetKind {
  const text = `${url} ${name}`.toLowerCase()
  if (/^data:image\/|image|img|photo|poster|cover|avatar|frame|\.(avif|bmp|gif|heic|jpe?g|png|webp)(?:$|\?)/.test(text)) return "image"
  if (/^data:video\/|video|clip|movie|\.(m4v|mov|mp4|mpeg|webm)(?:$|\?)/.test(text)) return "video"
  if (/^data:audio\/|audio|voice|speech|music|\.(aac|m4a|mp3|wav)(?:$|\?)/.test(text)) return "audio"
  return "file"
}

function inferAssetKindFromFile(file: File): UploadedComposerAssetKind {
  const mime = file.type.toLowerCase()
  if (mime.startsWith("image/")) return "image"
  if (mime.startsWith("video/")) return "video"
  if (mime.startsWith("audio/")) return "audio"
  return inferAssetKind(file.name, file.name)
}

function makeUploadedAsset(url: string, name: string, kind: UploadedComposerAssetKind): UploadedComposerAsset {
  return {
    id: `${Date.now()}:${Math.random().toString(36).slice(2)}`,
    url,
    name,
    kind,
  }
}

function syncPrimaryUploadedAsset() {
  const primary = uploadedAssets.value[0] || null
  uploadedAssetUrl.value = primary?.url || null
}

function setUploadedAssets(nextAssets: UploadedComposerAsset[]) {
  uploadedAssets.value = nextAssets
  syncPrimaryUploadedAsset()
}

function appendUploadedAsset(asset: UploadedComposerAsset) {
  setUploadedAssets([...uploadedAssets.value, asset])
}

function removeUploadedAsset(assetId: string) {
  const removed = uploadedAssets.value.find((asset) => asset.id === assetId)
  setUploadedAssets(uploadedAssets.value.filter((asset) => asset.id !== assetId))
  if (previewAsset.value?.id === removed?.id) previewAsset.value = null
}

function openAssetPreview(asset: UploadedComposerAsset) {
  previewAsset.value = asset
}

function closeAssetPreview() {
  previewAsset.value = null
}

function applyInitialUploadedAsset(url?: string | null, name = "") {
  if (!url) {
    setUploadedAssets([])
    return
  }
  setUploadedAssets([{
    id: `initial:${url}`,
    url,
    name: name || "参考素材",
    kind: inferAssetKind(url, name),
  }])
}

function uploadAccept(): string {
  const accept: string[] = []
  if (uploadKinds.value.includes("image")) accept.push("image/*")
  if (uploadKinds.value.includes("video")) accept.push("video/*")
  if (uploadKinds.value.includes("audio")) accept.push("audio/*", ".mp3", ".wav", ".m4a", ".aac")
  if (uploadKinds.value.includes("file")) accept.push(".pdf", ".txt", ".doc", ".docx")
  return accept.join(",")
}

function hasFileExtension(file: File, pattern: RegExp): boolean {
  return pattern.test(file.name.toLowerCase())
}

function isFileAcceptedForMode(file: File, nextMode: CreatorMode = mode.value): boolean {
  const mime = file.type.toLowerCase()
  const isImage = mime.startsWith("image/") || hasFileExtension(file, /\.(avif|bmp|gif|heic|jpe?g|png|webp)$/i)
  const isVideo = mime.startsWith("video/") || hasFileExtension(file, /\.(m4v|mov|mp4|mpeg|webm)$/i)
  const isAudio = mime.startsWith("audio/") || hasFileExtension(file, /\.(aac|m4a|mp3|wav)$/i)
  const isDocument = hasFileExtension(file, /\.(docx?|pdf|txt)$/i)
  const allowedKinds =
    nextMode === mode.value
      ? uploadKinds.value
      : nextMode === "image"
        ? ["image"]
        : nextMode === "audio"
          ? ["audio"]
          : nextMode === "agent"
            ? ["image", "video", "audio", "file"]
            : ["image", "video"]

  return (
    (allowedKinds.includes("image") && isImage) ||
    (allowedKinds.includes("video") && isVideo) ||
    (allowedKinds.includes("audio") && isAudio) ||
    (allowedKinds.includes("file") && isDocument)
  )
}

function uploadRejectMessage(): string {
  return `当前工具不支持该文件类型，${uploadHint.value}`
}

function openUploadPicker() {
  if (uploadDisabled.value) return
  fileInputRef.value?.click()
}

async function uploadAcceptedFile(file: File) {
  const uploaded = await uploadChatFile(file, { toolId: props.toolId || null })
  if (uploaded?.url) {
    appendUploadedAsset(makeUploadedAsset(uploaded.url, file.name, inferAssetKindFromFile(file)))
    return
  }
  uploadError.value = "上传完成但未返回资源地址"
}

async function uploadFiles(files: File[]) {
  if (files.length === 0) return
  const acceptedFiles = files.filter((file) => isFileAcceptedForMode(file))
  const rejectedCount = files.length - acceptedFiles.length
  if (acceptedFiles.length === 0) {
    uploadError.value = uploadRejectMessage()
    return
  }

  uploadError.value = ""
  uploading.value = true
  try {
    for (const file of acceptedFiles) {
      await uploadAcceptedFile(file)
    }
    if (rejectedCount > 0) uploadError.value = `${rejectedCount} 个文件类型不支持，已添加其余素材`
  } catch (error) {
    uploadError.value = error instanceof Error ? error.message : "上传失败"
  } finally {
    uploading.value = false
  }
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  if (files.length === 0) return
  try {
    await uploadFiles(files)
  } finally {
    input.value = ""
  }
}

function onUploadDragOver(event: DragEvent) {
  if (uploadDisabled.value) {
    if (event.dataTransfer) event.dataTransfer.dropEffect = "none"
    return
  }
  dragActive.value = true
  if (event.dataTransfer) event.dataTransfer.dropEffect = "copy"
}

function onUploadDragLeave(event: DragEvent) {
  const target = event.currentTarget as HTMLElement | null
  const relatedTarget = event.relatedTarget as Node | null
  if (target && relatedTarget && target.contains(relatedTarget)) return
  dragActive.value = false
}

async function onUploadDrop(event: DragEvent) {
  dragActive.value = false
  if (uploadDisabled.value) return
  const files = Array.from(event.dataTransfer?.files || [])
  await uploadFiles(files)
}

function toggleMentionMenu() {
  mentionMenuOpen.value = !mentionMenuOpen.value
}

function insertReferenceMention(kind: "image" | "video") {
  const token = kind === "video" ? "@视频 " : "@图片 "
  if (!prompt.value.includes(token.trim())) {
    prompt.value = `${prompt.value}${prompt.value && !prompt.value.endsWith(" ") ? " " : ""}${token}`
  }
  mentionMenuOpen.value = false
}

function submit() {
  if (!canGenerate.value) return
  if (requiresVideoInput.value && !hasVideoReference.value) {
    uploadError.value = "当前模型需要视频输入，请上传视频素材，或在提示词里用 @视频 引用已有视频。"
    return
  }
  emit("submit", currentState())
}

function resetDraft() {
  prompt.value = ""
  setUploadedAssets([])
  uploadError.value = ""
}

watch(() => props.initialMode, (nextMode) => applyMode(nextMode, false), { immediate: true })
watch(mode, (nextMode) => {
  void loadModelOptionsForMode(nextMode)
}, { immediate: true })
watch(modes, () => {
  void nextTick(updateModeTabWidth)
})
watch(() => props.initialPrompt, (nextPrompt) => {
  prompt.value = nextPrompt
})
watch(() => props.resetKey, () => {
  resetDraft()
})
watch(() => props.toolId, (nextToolId) => {
  if (applyInitialModelConfigId()) return
  if (!nextToolId) {
    selectedModelKey.value = "auto"
    return
  }
  const matched = modelOptions.value.find((option) => option.toolCode === nextToolId)
  selectedModelKey.value = matched?.key || "auto"
}, { immediate: true })
watch(modelOptions, (options) => {
  if (applyInitialModelConfigId()) return
  if (!selectedModelGroupKey.value && groupedModelOptions.value[0]) {
    selectedModelGroupKey.value = groupedModelOptions.value[0].key
  }
  if (options.some((option) => option.key === selectedModelKey.value)) return
  selectedModelKey.value = "auto"
}, { immediate: true })
watch(groupedModelOptions, (groups) => {
  if (groups.some((group) => group.key === selectedModelGroupKey.value)) return
  selectedModelGroupKey.value = groups[0]?.key || ""
}, { immediate: true })
watch(() => props.initialModelConfigId, () => {
  applyInitialModelConfigId()
})
watch(effectiveSelectedModelOption, (option) => {
  if (!option?.toolCode || option.toolCode === props.toolId || selectedModelKey.value === "auto") return
  emit("toolSelect", option.toolCode)
})
watch(configuredFormatOptions, syncFormatDefaults, { immediate: true, deep: true })
watch(() => props.initialRatio, (nextRatio) => {
  if (!nextRatio) return
  ratio.value = nextRatio
  imageRatio.value = nextRatio
})
watch(() => props.initialDurationSeconds, (nextDuration) => {
  if (nextDuration) duration.value = nextDuration
})
watch(() => props.initialQuality, (nextQuality) => {
  if (nextQuality) quality.value = nextQuality
})
watch(() => props.initialOutputCount, (nextCount) => {
  if (nextCount) imageCount.value = nextCount
})
watch(
  () => [props.initialUploadedAssetUrl, props.initialUploadedAssetName] as const,
  ([nextUrl, nextName]) => applyInitialUploadedAsset(nextUrl, nextName),
)

onMounted(() => {
  updateModeTabWidth()
  if (typeof ResizeObserver === "undefined") return
  modeTabResizeObserver = new ResizeObserver(updateModeTabWidth)
  if (modeTabRootRef.value) modeTabResizeObserver.observe(modeTabRootRef.value)
})

onBeforeUnmount(() => {
  modelOptionsAbortController?.abort()
  modeTabResizeObserver?.disconnect()
  modeTabResizeObserver = null
})

// 实时算力预估：参数变化时防抖调用后端权威预估接口，与提交时冻结口径一致。
const estimateInput = computed<UseTaskEstimateInput | null>(() => {
  if (mode.value === "agent") return null
  const toolCode = selectedToolCode.value
  if (!toolCode) return null
  const params: Record<string, unknown> = {}
  if (prompt.value) params.prompt = prompt.value
  const aspectRatio = mode.value === "image" ? imageRatio.value : ratio.value
  if (aspectRatio) params.aspectRatio = aspectRatio
  if (mode.value !== "image" && duration.value != null) params.duration = duration.value
  const selectedQuality = mode.value === "image"
    ? (imageQualityOptions.value.length ? quality.value : undefined)
    : quality.value
  if (selectedQuality) params.quality = selectedQuality
  if (mode.value === "image" && imageCount.value != null) params.count = imageCount.value
  return {
    toolCode,
    params,
    modelConfigId: effectiveSelectedModelOption.value?.modelConfigId ?? null,
  }
})

const { estimate: liveEstimate, loading: estimateLoading } = useTaskEstimate(estimateInput)

const liveCreditView = computed(() =>
  formatLiveCreditEstimate(liveEstimate.value, {
    loading: estimateLoading.value,
  }),
)

const costEstimateLabel = computed(() => {
  if (!estimateInput.value) return ""
  return liveCreditView.value.label
})

const costInsufficient = computed(() => liveCreditView.value.insufficient)
</script>

<template>
  <section class="workspace-composer-wrap" :class="{ compact, 'dock-sized': dockSized }">
    <h1 v-if="!compact" class="workspace-home-title">
      <Sparkles :size="20" fill="currentColor" />
      {{ title }}
      <Sparkles :size="20" fill="currentColor" />
    </h1>

    <div v-if="!compact && showModeTabs" class="workspace-mode-row">
      <div ref="modeTabRootRef" class="workspace-mode-tabs" :style="modeTabStyle">
        <span class="workspace-mode-tabs-indicator" :style="modeTabIndicatorStyle" aria-hidden="true"></span>
        <button
          v-for="item in modes"
          :key="item.key"
          type="button"
          :class="{ active: mode === item.key }"
          :aria-pressed="mode === item.key"
          @click="selectMode(item.key)"
        >
          <component :is="item.icon" :size="28" />
          {{ item.label }}
        </button>
      </div>
    </div>

    <div class="workspace-composer">
        <div
          class="workspace-upload"
          :class="{ dragging: dragActive, 'has-file': uploadedAssets.length > 0, disabled: uploadDisabled }"
          @dragenter.prevent="onUploadDragOver"
          @dragover.prevent="onUploadDragOver"
          @dragleave.prevent="onUploadDragLeave"
          @drop.prevent="onUploadDrop"
        >
          <!-- Always-visible add button -->
          <button
            class="workspace-upload-add-btn"
            :class="{ 'is-empty': uploadedAssets.length === 0 }"
            type="button"
            :disabled="uploadDisabled"
            :title="uploadError || uploadHint"
            aria-label="上传素材"
            @click="openUploadPicker"
          >
            <Plus :size="uploadedAssets.length === 0 ? 28 : 18" />
            <span v-if="uploadedAssets.length === 0" class="workspace-upload-add-label">{{ uploadHint }}</span>
          </button>
          <!-- Asset thumbnails (overlapping, expand on hover) -->
          <div v-if="uploadedAssets.length > 0" class="workspace-upload-stack">
            <div
              v-for="(asset, index) in visibleUploadedAssets"
              :key="asset.id"
              class="workspace-upload-thumb"
              :class="'thumb-' + index"
              role="button"
              tabindex="0"
              :title="asset.name"
              @click="openAssetPreview(asset)"
              @keydown.enter.prevent="openAssetPreview(asset)"
              @keydown.space.prevent="openAssetPreview(asset)"
            >
              <img v-if="asset.kind === 'image'" :src="asset.url" alt="" />
              <video v-else-if="asset.kind === 'video'" :src="asset.url" muted playsinline></video>
              <span v-else class="workspace-upload-file-icon">
                <Music2 v-if="asset.kind === 'audio'" :size="18" />
                <FileUp v-else :size="18" />
              </span>
              <!-- Video play overlay -->
              <span v-if="asset.kind === 'video'" class="workspace-thumb-play" aria-hidden="true">
                <Play :size="14" fill="currentColor" />
              </span>
              <!-- Kind badge -->
              <span class="workspace-thumb-kind" :class="'kind-' + asset.kind">
                <Video v-if="asset.kind === 'video'" :size="10" />
                <ImageIcon v-else-if="asset.kind === 'image'" :size="10" />
                <Music2 v-else-if="asset.kind === 'audio'" :size="10" />
                <FileUp v-else :size="10" />
              </span>
              <!-- Delete button -->
              <button
                class="workspace-thumb-delete"
                type="button"
                title="移除"
                aria-label="移除素材"
                @click.stop="removeUploadedAsset(asset.id)"
              >
                <X :size="10" />
              </button>
            </div>
            <span v-if="hiddenUploadedAssetCount > 0" class="workspace-upload-more">+{{ hiddenUploadedAssetCount }}</span>
          </div>
        </div>
        <input
          ref="fileInputRef"
          class="sr-only"
          type="file"
          multiple
          :accept="uploadAccept()"
          @change="onFilePicked"
        />
        <!-- Video input warning banner -->
        <div v-if="videoInputHint" class="workspace-video-warn">
          <Video :size="14" />
          <span>{{ videoInputHint }}</span>
        </div>
        <!-- Prompt input -->
        <div class="workspace-prompt-shell">
          <textarea v-model="prompt" name="prompt" aria-label="创作提示词" :placeholder="promptPlaceholder"></textarea>
          <div class="workspace-prompt-actions">
            <button
              type="button"
              class="workspace-icon-chip"
              :class="{ active: mentionMenuOpen }"
              aria-label="引用素材"
              @click.stop="toggleMentionMenu"
            >
              <AtSign :size="16" />
            </button>
            <div v-if="mentionMenuOpen" class="workspace-menu-pop workspace-mention-pop">
              <button
                v-for="item in mentionOptions"
                :key="item.kind"
                type="button"
                @click="insertReferenceMention(item.kind)"
              >
                {{ item.label }}
              </button>
            </div>
          </div>
        </div>
        <p class="workspace-upload-hint">{{ uploadHint }}</p>
      <div v-if="uploading || uploadError" class="workspace-upload-status" :class="{ error: uploadError }">
        <span v-if="uploading">素材上传中...</span>
        <span v-else-if="uploadError">{{ uploadError }}</span>
      </div>
      <div class="workspace-composer-controls">
        <template v-if="mode === 'agent'">
          <div class="workspace-select-chip">
            <button class="workspace-chip" type="button" @click="openMenu = openMenu === 'type' ? null : 'type'">
              {{ generationType }}<ChevronDown :size="14" />
            </button>
            <div v-if="openMenu === 'type'" class="workspace-menu-pop">
              <button v-for="item in generationOptions" :key="item" @click="generationType = item; openMenu = null">{{ item }}</button>
            </div>
          </div>
          <button class="workspace-chip" type="button">风格</button>
          <button class="workspace-icon-chip" type="button" aria-label="高级设置"><Settings2 :size="16" /></button>
          <em v-if="costEstimateLabel" class="workspace-cost-estimate" :class="{ 'workspace-cost-insufficient': costInsufficient }" :title="liveCreditView.hint">{{ costEstimateLabel }}</em>
          <button class="workspace-generate" :disabled="!canGenerate" @click="submit">
            <Sparkles :size="16" />{{ submitting ? "提交中" : costLabel }}
          </button>
        </template>
        <template v-else>
          <div class="workspace-select-chip">
            <button class="workspace-chip active" type="button" @click="openMenu = openMenu === 'mode' ? null : 'mode'">
              <Video v-if="mode === 'video'" :size="16" />
              <ImageIcon v-else-if="mode === 'image'" :size="16" />
              <Bot v-else-if="mode === 'digitalHuman'" :size="16" />
              <Mic2 v-else-if="mode === 'audio'" :size="16" />
              <Bot v-else :size="16" />
              {{ modes.find((item) => item.key === mode)?.label || "工具" }}<ChevronDown :size="14" />
            </button>
            <div v-if="openMenu === 'mode'" class="workspace-menu-pop">
              <button v-for="item in modes" :key="item.key" @click="selectMode(item.key); openMenu = null">{{ item.label }}</button>
            </div>
          </div>
          <div class="workspace-select-chip">
            <button class="workspace-chip" type="button" @click="openMenu = openMenu === 'type' ? null : 'type'">{{ generationType }}<ChevronDown :size="14" /></button>
            <div v-if="openMenu === 'type'" class="workspace-menu-pop">
              <button v-for="item in generationOptions" :key="item" @click="generationType = item; openMenu = null">{{ item }}</button>
            </div>
          </div>
          <div class="workspace-select-chip">
            <button class="workspace-chip" type="button" @click="openMenu = openMenu === 'model' ? null : 'model'">{{ selectedModelLabel }}</button>
            <div v-if="openMenu === 'model'" class="workspace-menu-pop workspace-model-picker">
              <div v-if="modelOptionsLoading" class="workspace-model-picker-state">模型加载中...</div>
              <div v-else-if="configuredModelOptions.length === 0" class="workspace-model-picker-state">
                {{ modelOptionsError || "暂无可选模型" }}
              </div>
              <div v-else class="workspace-model-picker-body">
                <div class="workspace-model-vendor-list">
                  <button
                    v-for="group in groupedModelOptions"
                    :key="group.key"
                    type="button"
                    class="workspace-model-vendor-item"
                    :class="{ active: activeModelGroup?.key === group.key }"
                    @click="selectModelGroup(group.key)"
                  >
                    <span class="workspace-model-vendor-icon">
                      <img
                        v-if="vendorIconSrc(group)"
                        :src="vendorIconSrc(group)"
                        :alt="group.label"
                        @error="onModelIconError"
                      />
                      <span v-else>{{ vendorInitial(group) }}</span>
                    </span>
                    <span class="workspace-model-vendor-copy">
                      <strong>{{ group.label }}</strong>
                      <small>{{ group.models.length }} 个模型</small>
                    </span>
                  </button>
                </div>
                <div class="workspace-model-list">
                  <button
                    v-for="item in activeModelGroup?.models || []"
                    :key="item.key"
                    type="button"
                    class="workspace-model-row"
                    :class="{ active: effectiveSelectedModelOption?.key === item.key }"
                    @click="selectModel(item.key)"
                  >
                    <span class="workspace-model-row-icon">
                      <img
                        v-if="modelIconSrc(item)"
                        :src="modelIconSrc(item)"
                        :alt="item.label"
                        @error="onModelIconError"
                      />
                      <span v-else>{{ vendorInitial(activeModelGroup) }}</span>
                    </span>
                    <span class="workspace-model-row-copy">
                      <span class="workspace-model-row-title">
                        <strong>{{ item.label }}</strong>
                        <span
                          v-for="badge in modelBadges(item)"
                          :key="badge"
                          class="workspace-model-badge"
                          :class="badge === '免费' ? 'free' : badge === '默认' ? 'default' : ''"
                        >
                          {{ badge }}
                        </span>
                      </span>
                      <span class="workspace-model-row-description">{{ modelDescription(item) }}</span>
                      <span class="workspace-model-row-meta">
                        <span v-for="meta in modelMetaItems(item)" :key="meta">{{ meta }}</span>
                      </span>
                    </span>
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div v-if="mode !== 'image' && mode !== 'audio' && (durationOptions.length || qualityOptions.length || ratioOptions.length)" class="workspace-select-chip workspace-format-chip-group">
            <button v-if="durationOptions.length" class="workspace-chip workspace-format-value" type="button" @click="openFormatMenu"><Clock3 :size="15" />{{ durationLabel }}</button>
            <button v-if="qualityOptions.length" class="workspace-chip workspace-format-value" type="button" @click="openFormatMenu">{{ qualityLabel }}</button>
            <button v-if="ratioOptions.length" class="workspace-chip workspace-format-value" type="button" @click="openFormatMenu"><RectangleHorizontal :size="15" />{{ videoRatioLabel }}</button>
            <div v-if="openMenu === 'format'" class="workspace-format-pop">
              <div v-if="durationOptions.length" class="workspace-format-section">
                <p>{{ formatLabels.duration }}</p>
                <div class="workspace-format-options two">
                  <button v-for="item in durationOptions" :key="item.value" :class="{ active: duration === item.value }" type="button" @click="duration = item.value">{{ item.label }}</button>
                </div>
              </div>
              <div v-if="qualityOptions.length" class="workspace-format-section">
                <p>{{ formatLabels.quality }}</p>
                <div class="workspace-format-options">
                  <button v-for="item in qualityOptions" :key="item.value" :class="{ active: quality === item.value }" type="button" @click="quality = item.value">{{ item.label }}</button>
                </div>
              </div>
              <div v-if="ratioOptions.length" class="workspace-format-section">
                <p>{{ formatLabels.ratio }}</p>
                <div class="workspace-format-options workspace-ratio-options">
                  <button v-for="item in ratioOptions" :key="item.value" :class="{ active: ratio === item.value }" type="button" @click="ratio = item.value">
                    <Square v-if="item.value === '1:1'" :size="17" />
                    <RectangleHorizontal v-else-if="item.value === '16:9' || item.value === '4:3'" :size="18" />
                    <Monitor v-else :size="17" />
                    {{ item.label }}
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div v-if="mode === 'image' && (imageRatioOptions.length || imageQualityOptions.length || imageCountOptions.length)" class="workspace-select-chip workspace-image-format-chip-group">
            <button v-if="imageRatioOptions.length" class="workspace-chip workspace-image-format-value" type="button" @click="openImageFormatMenu">
              <Square :size="15" />{{ imageSizeChipLabel }}
            </button>
            <button v-if="imageQualityOptions.length" class="workspace-chip workspace-image-format-value" type="button" @click="openImageFormatMenu">
              {{ qualityLabel }}
            </button>
            <button v-if="imageCountOptions.length" class="workspace-chip workspace-image-format-value" type="button" @click="openImageFormatMenu">
              <ImageIcon :size="15" />{{ imageCountLabel }}
            </button>
            <div v-if="openMenu === 'imageFormat'" class="workspace-format-pop workspace-image-format-pop">
              <div v-if="imageRatioOptions.length" class="workspace-format-section">
                <p>{{ imageSizeSectionLabel }}</p>
                <div class="workspace-format-options workspace-image-size-options">
                  <button v-for="item in imageRatioOptions" :key="item.value" :class="{ active: imageRatio === item.value }" type="button" @click="imageRatio = item.value">
                    <Square v-if="imageSizeIconKind(item.value) === 'square'" :size="17" />
                    <RectangleHorizontal v-else-if="imageSizeIconKind(item.value) === 'landscape'" :size="18" />
                    <RectangleVertical v-else :size="18" />
                    <span class="workspace-image-size-aspect">{{ imageSizeAspectLabel(item.value, item.label) }}</span>
                    <small v-if="imageSizeDimensionLabel(item.value)" class="workspace-image-size-dimensions">{{ imageSizeDimensionLabel(item.value) }}</small>
                  </button>
                </div>
              </div>
              <div v-if="imageQualityOptions.length" class="workspace-format-section">
                <p>{{ formatLabels.quality }}</p>
                <div class="workspace-format-options workspace-image-quality-options">
                  <button v-for="item in imageQualityOptions" :key="item.value" :class="{ active: quality === item.value }" type="button" @click="quality = item.value">{{ item.label }}</button>
                </div>
              </div>
              <div v-if="imageCountOptions.length" class="workspace-format-section">
                <p>{{ formatLabels.count }}</p>
                <div class="workspace-format-options workspace-image-count-options">
                  <button v-for="item in imageCountOptions" :key="item.value" :class="{ active: imageCount === item.value }" type="button" @click="imageCount = item.value">{{ item.label }}</button>
                </div>
              </div>
            </div>
          </div>
          <button v-if="mode === 'image'" class="workspace-chip workspace-style-chip" type="button"><span></span>自动</button>
          <button class="workspace-icon-chip" type="button" aria-label="高级设置"><Settings2 :size="16" /></button>
          <em v-if="costEstimateLabel" class="workspace-cost-estimate" :class="{ 'workspace-cost-insufficient': costInsufficient }" :title="liveCreditView.hint">{{ costEstimateLabel }}</em>
          <button class="workspace-generate" :disabled="!canGenerate" @click="submit">
            <Sparkles :size="16" />{{ submitting ? "提交中" : costLabel }}
          </button>
        </template>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="previewAsset" class="workspace-upload-lightbox" @click.self="closeAssetPreview">
        <button class="workspace-upload-lightbox-close" type="button" aria-label="关闭预览" @click="closeAssetPreview">
          <X :size="18" />
        </button>
        <div class="workspace-upload-lightbox-media">
          <img v-if="previewAsset.kind === 'image'" :src="previewAsset.url" :alt="previewAsset.name" />
          <video v-else-if="previewAsset.kind === 'video'" :src="previewAsset.url" controls autoplay playsinline></video>
          <audio v-else-if="previewAsset.kind === 'audio'" :src="previewAsset.url" controls></audio>
          <FileUp v-else :size="42" />
        </div>
      </div>
    </Teleport>

    <div v-if="!compact && mode === 'agent'" class="workspace-home-agent-pills">
      <button v-for="idea in agentIdeas" :key="idea" type="button" @click="prompt = idea">{{ idea }}</button>
    </div>
  </section>
</template>
