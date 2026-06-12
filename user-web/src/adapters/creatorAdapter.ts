import type { ImageGenerationParameters, ModelOptionGroup, ModelOptionsResponse, ToolDetail, ToolField, ToolSummary } from "@/api/types"

export type CreatorMode = "video" | "image" | "agent" | "digitalHuman" | "audio"

export interface ComposerState {
  mode: CreatorMode
  prompt: string
  generationType?: string
  modelLabel?: string
  toolCode?: string
  modelConfigId?: number | null
  ratio?: string
  durationSeconds?: number
  quality?: string
  outputCount?: number
  uploadedAssetUrl?: string | null
  uploadedAssetUrls?: string[]
}

export interface ComposerModelOption {
  key: string
  label: string
  toolCode?: string
  modelConfigId?: number | null
  modelName?: string | null
  description?: string | null
  iconUrl?: string | null
  badges?: string[]
  capabilities?: string[]
  isDefault?: boolean | null
  vendorCode?: string | null
  vendorLabel?: string | null
  imageParameters?: ImageGenerationParameters | null
  estimatedCreditCost?: number
  /** 工作流类工具：按每次实际调用模型成本 ×1.2 动态计费 */
  variableCreditPricing?: boolean | null
  auto?: boolean
}

export interface ComposerModelGroup {
  key: string
  label: string
  iconUrl?: string | null
  models: ComposerModelOption[]
}

export interface ComposerFormatValueOption<T extends string | number = string | number> {
  label: string
  value: T
}

export interface ComposerFormatOptions {
  duration: ComposerFormatValueOption<number>[]
  quality: ComposerFormatValueOption<string>[]
  ratio: ComposerFormatValueOption<string>[]
  count: ComposerFormatValueOption<number>[]
  labels: {
    duration: string
    quality: string
    ratio: string
    count: string
  }
  defaults: {
    duration?: number
    quality?: string
    ratio?: string
    count?: number
  }
}

export interface ResolvedCreatorTask {
  toolCode: string
  params: Record<string, unknown>
  missingRequiredFields: string[]
  handledFieldKeys: string[]
}

function normalize(value?: string | null): string {
  return (value || "").trim().toLowerCase()
}

function normalizeFieldText(value?: string | null): string {
  return (value || "").replace(/([a-z0-9])([A-Z])/g, "$1 $2").trim().toLowerCase()
}

function searchableToolText(tool: Partial<ToolSummary>): string {
  return [
    tool.inputModality,
    tool.outputModality,
    tool.categoryName,
    tool.toolType,
    tool.toolName,
    tool.description,
    tool.modelName,
    tool.modelConfigName,
  ]
    .map((value) => normalize(value))
    .filter(Boolean)
    .join(" ")
}

function modalityText(value?: string | null): string {
  return normalize(value).replace(/[^a-z0-9]+/g, " ")
}

function includesAny(value: string, keywords: string[]): boolean {
  return keywords.some((keyword) => value.includes(keyword))
}

function isOnlineTool(tool: Pick<ToolSummary, "status">): boolean {
  return normalize(tool.status) === "online"
}

function isVideoTool(tool: Partial<ToolSummary>): boolean {
  const output = modalityText(tool.outputModality)
  const handler = normalize(tool.executionHandler)
  if (output.includes("video") || handler.includes("video_generation")) return true
  if (output && !output.includes("video")) return false
  return includesAny(searchableToolText(tool), ["video", "movie", "clip", "视频", "影片", "短片", "动效", "动画"])
}

function isImageTool(tool: Partial<ToolSummary>): boolean {
  const output = modalityText(tool.outputModality)
  const handler = normalize(tool.executionHandler)
  if (output.includes("image") || handler.includes("image_generation")) return true
  if (output && !output.includes("image")) return false
  return includesAny(searchableToolText(tool), ["image", "img", "picture", "photo", "图片", "图像", "照片", "海报", "封面"])
}

function isAgentTool(tool: Partial<ToolSummary>): boolean {
  const text = searchableToolText(tool)
  return normalize(tool.toolType).includes("agent") || includesAny(text, ["agent", "智能体"])
}

function isDigitalHumanTool(tool: Partial<ToolSummary>): boolean {
  return includesAny(searchableToolText(tool), ["digital human", "digital_human", "avatar", "presenter", "数字人", "口播", "主播"])
}

function isAudioTool(tool: Partial<ToolSummary>): boolean {
  return includesAny(searchableToolText(tool), ["audio", "voice", "speech", "tts", "music", "音频", "语音", "声音", "配音", "音乐"])
}

function classifyCreatorToolMode(tool: Partial<ToolSummary>): CreatorMode {
  if (isDigitalHumanTool(tool)) return "digitalHuman"
  if (isAudioTool(tool)) return "audio"
  if (isImageTool(tool)) return "image"
  if (!isVideoTool(tool) && isAgentTool(tool)) return "agent"
  return "video"
}

function toolMatchesMode(tool: Partial<ToolSummary>, mode: CreatorMode): boolean {
  if (mode === "agent") return isAgentTool(tool)
  return classifyCreatorToolMode(tool) === mode
}

function fieldText(field: ToolField): string {
  return normalizeFieldText(`${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`)
}

function hasFieldValue(value: unknown): boolean {
  if (value === undefined || value === null) return false
  if (typeof value === "string") return value.trim().length > 0
  return true
}

function findField(fields: ToolField[], keywords: string[]): ToolField | undefined {
  return fields.find((field) => {
    const text = fieldText(field)
    return keywords.some((keyword) => matchesFieldKeyword(text, keyword))
  })
}

function matchesFieldKeyword(text: string, keyword: string): boolean {
  const key = normalizeFieldText(keyword)
  if (!key) return false
  if (/^[a-z0-9_]+$/.test(key)) {
    const tokens = new Set(text.split(/[^a-z0-9]+|_/).filter(Boolean))
    const keyTokens = key.split(/[^a-z0-9]+|_/).filter(Boolean)
    if (keyTokens.length > 1) return keyTokens.every((token) => tokens.has(token))
    return tokens.has(key)
  }
  return text.includes(key)
}

function setIfField(
  params: Record<string, unknown>,
  handled: Set<string>,
  field: ToolField | undefined,
  value: unknown,
  markHandledWhenMapped = false,
) {
  if (!field) return
  if (markHandledWhenMapped) handled.add(field.fieldKey)
  if (!hasFieldValue(value)) return
  params[field.fieldKey] = value
  handled.add(field.fieldKey)
}

function uploadFields(fields: ToolField[]): ToolField[] {
  return fields.filter((field) => {
    if (field.fieldType === "image" || field.fieldType === "file") return true
    if (field.fieldType === "image_upload" || field.fieldType === "video_upload" || field.fieldType === "audio_upload") return true
    const text = fieldText(field)
    return ["image", "file", "asset", "参考", "上传"].some((keyword) => matchesFieldKeyword(text, keyword))
  })
}

function compactLabel(value?: string | null): string {
  return (value || "").replace(/\s+/g, " ").trim()
}

const OPENAI_STANDARD_IMAGE_SIZES = ["1024x1024", "1536x1024", "1024x1536"]
const GPT_IMAGE_2_4K_IMAGE_SIZES = [
  "2560x2560",
  "2880x2880",
  "3072x1728",
  "3840x2160",
  "1728x3072",
  "2160x3840",
  "2560x1920",
  "3072x2304",
  "1920x2560",
  "2304x3072",
  "2880x1920",
  "3072x2048",
  "3456x2304",
  "1920x2880",
  "2048x3072",
  "2304x3456",
  "3072x1536",
  "3840x1920",
  "1536x3072",
  "1920x3840",
  "3840x1280",
  "1280x3840",
]
const GENERIC_IMAGE_SIZES = ["1024x1024", "1280x720", "720x1280", "1024x768", "768x1024", "1152x768", "768x1152"]
const DEFAULT_IMAGE_COUNTS = [1, 2, 3, 4]

function imageSizeDisplayLabel(size: string): string {
  const [width, height] = size.split("x").map((value) => Number(value))
  if (!width || !height) return size
  if (width === height) return `方图 ${size}`
  return width > height ? `横图 ${size}` : `竖图 ${size}`
}

function buildImageParameters(sizes: string[], counts = DEFAULT_IMAGE_COUNTS): ImageGenerationParameters {
  return {
    sizes: sizes.map((size) => ({ label: imageSizeDisplayLabel(size), value: size })),
    defaultSize: sizes[0] ?? null,
    counts,
    defaultCount: counts[0] ?? null,
  }
}

function inferImageParametersFromText(text: string): ImageGenerationParameters | null {
  const value = normalize(text)
  if (!value) return null
  if (value.includes("gpt-image-2-4k")) {
    return buildImageParameters(GPT_IMAGE_2_4K_IMAGE_SIZES)
  }
  if (includesAny(value, ["gpt-image", "openai_images_gateway", "ofox_openai_images", "openai"])) {
    return buildImageParameters(OPENAI_STANDARD_IMAGE_SIZES)
  }
  if (includesAny(value, ["agnes-image", "agnes_images"])) {
    return buildImageParameters(OPENAI_STANDARD_IMAGE_SIZES)
  }
  if (includesAny(value, ["doubao", "seedream", "volcengine_images", "volcengine"])) {
    return buildImageParameters(OPENAI_STANDARD_IMAGE_SIZES, [1])
  }
  if (includesAny(value, ["kling", "可灵"])) {
    if (
      (includesAny(value, ["可灵生图", "kling-v2-1"]) || value.includes("image_generation")) &&
      !includesAny(value, ["图生视频", "image-to-video", "i2v", "motion", "omni", "video o1"])
    ) {
      return buildImageParameters(GENERIC_IMAGE_SIZES)
    }
    return null
  }
  if (includesAny(value, ["siliconflow", "z-image", "tongyi-mai", "image-turbo", "local-media-mock", "local mock"])) {
    return buildImageParameters(GENERIC_IMAGE_SIZES)
  }
  return null
}

function inferImageParametersForModel(
  model: Partial<ModelOptionGroup["models"][number]>,
  group: Partial<ModelOptionGroup>,
): ImageGenerationParameters | null {
  return inferImageParametersFromText([
    group.vendorCode,
    group.vendorName,
    group.provider,
    group.providerName,
    model.provider,
    model.providerName,
    model.vendorCode,
    model.vendorName,
    model.displayName,
    model.name,
    model.modelConfigName,
    model.modelName,
    model.configCode,
    model.toolCode,
    model.toolName,
  ].map((value) => compactLabel(value)).filter(Boolean).join(" "))
}

function isRealModelBoundTool(tool: Partial<ToolSummary>): boolean {
  if (!tool.modelConfigId && !compactLabel(tool.modelConfigName) && !compactLabel(tool.modelName)) return false
  const text = searchableToolText(tool)
  return !includesAny(`${text} ${normalize(tool.toolCode)}`, ["local-media-mock", "local mock", "local_mock"])
}

function optionLabel(option: NonNullable<ToolField["options"]>[number]): string {
  if (typeof option === "string") return option
  return option.label || option.value
}

function optionValue(option: NonNullable<ToolField["options"]>[number]): string {
  if (typeof option === "string") return option
  return option.value || option.label
}

function optionsFromJson(raw?: string | null): NonNullable<ToolField["options"]> {
  if (!raw || !raw.trim()) return []
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .map((item) => {
        if (typeof item === "string" || typeof item === "number") return String(item)
        if (item && typeof item === "object") {
          const label = compactLabel(String(item.label ?? item.name ?? item.value ?? ""))
          const value = compactLabel(String(item.value ?? item.label ?? item.name ?? ""))
          if (label || value) return { label: label || value, value: value || label }
        }
        return null
      })
      .filter((item): item is NonNullable<ToolField["options"]>[number] => Boolean(item))
  } catch {
    return []
  }
}

function fieldOptions(field: ToolField | undefined): NonNullable<ToolField["options"]> {
  if (!field) return []
  if (field.options?.length) return field.options
  return optionsFromJson(field.optionsJson)
}

function numberFromOption(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value
  const match = String(value ?? "").match(/\d+(?:\.\d+)?/)
  if (!match) return null
  const numeric = Number(match[0])
  return Number.isFinite(numeric) ? numeric : null
}

function normalizeComparable(value: unknown): string {
  return String(value ?? "").trim().toLowerCase()
}

function defaultString(field: ToolField | undefined, options: ComposerFormatValueOption<string>[]): string | undefined {
  if (!field || options.length === 0) return options[0]?.value
  const raw = normalizeComparable(field.defaultValue)
  if (raw) {
    const matched = options.find((option) =>
      normalizeComparable(option.value) === raw || normalizeComparable(option.label) === raw,
    )
    return matched?.value ?? String(field.defaultValue)
  }
  return options[0]?.value
}

function defaultNumber(field: ToolField | undefined, options: ComposerFormatValueOption<number>[]): number | undefined {
  if (!field || options.length === 0) return options[0]?.value
  const defaultValue = field.defaultValue
  if (defaultValue !== undefined && defaultValue !== null && String(defaultValue).trim()) {
    const raw = normalizeComparable(defaultValue)
    const matched = options.find((option) =>
      normalizeComparable(option.value) === raw || normalizeComparable(option.label) === raw,
    )
    return matched?.value ?? numberFromOption(defaultValue) ?? options[0]?.value
  }
  return options[0]?.value
}

export function selectDefaultTool(tools: ToolSummary[], mode: CreatorMode): ToolSummary | null {
  const candidates = tools.filter(isOnlineTool)
  const matched = candidates.find((tool) => toolMatchesMode(tool, mode) && isRealModelBoundTool(tool))
    ?? candidates.find((tool) => toolMatchesMode(tool, mode))
  return matched ?? null
}

export function creatorModeForTool(tool: Partial<ToolSummary> | null | undefined): CreatorMode {
  return tool ? classifyCreatorToolMode(tool) : "video"
}

export function buildComposerModelOptions(tools: ToolSummary[], mode: CreatorMode): ComposerModelOption[] {
  const candidates = tools.filter((tool) => isOnlineTool(tool) && toolMatchesMode(tool, mode) && isRealModelBoundTool(tool))
  const automatic: ComposerModelOption = {
    key: "auto",
    label: "自动选择",
    toolCode: candidates[0]?.toolCode,
    auto: true,
  }
  const configured = candidates.map((tool) => {
    const label =
      compactLabel(tool.modelConfigName) ||
      compactLabel(tool.modelName) ||
      compactLabel(tool.toolName) ||
      tool.toolCode
    return {
      key: `tool:${tool.toolCode}`,
      label,
      toolCode: tool.toolCode,
      modelConfigId: tool.modelConfigId,
      modelName: tool.modelName,
      description: tool.description ?? null,
      iconUrl: tool.frontendStyle?.modelIconUrl || tool.coverUrl || null,
      estimatedCreditCost: tool.estimatedCreditCost,
      variableCreditPricing: tool.variableCreditPricing,
    }
  })
  return [automatic, ...configured]
}

function modelOptionLabel(model: Partial<ModelOptionGroup["models"][number]>): string {
  return compactLabel(model.displayName)
    || compactLabel(model.name)
    || compactLabel(model.modelConfigName)
    || compactLabel(model.modelName)
    || compactLabel(model.toolName)
    || compactLabel(model.configCode)
    || "未命名模型"
}

function modelOptionKey(model: Partial<ModelOptionGroup["models"][number]>): string {
  const id = model.modelConfigId ?? model.id
  if (id != null) return `model:${id}`
  if (model.configCode) return `model:${model.configCode}`
  return `model:${modelOptionLabel(model)}`
}

function groupLabel(group: Partial<ModelOptionGroup>, fallback: string): string {
  return compactLabel(group.vendorName)
    || compactLabel(group.providerName)
    || compactLabel(group.vendorCode)
    || compactLabel(group.provider)
    || fallback
}

function toComposerModelOption(
  model: Partial<ModelOptionGroup["models"][number]>,
  group: Partial<ModelOptionGroup>,
): ComposerModelOption {
  const vendorCode = group.vendorCode ?? group.provider ?? model.vendorCode ?? model.provider ?? null
  return {
    key: modelOptionKey(model),
    label: modelOptionLabel(model),
    toolCode: model.toolCode || undefined,
    modelConfigId: model.modelConfigId ?? model.id ?? null,
    modelName: model.modelName ?? null,
    description: model.description ?? null,
    iconUrl: model.modelIconUrl ?? model.iconUrl ?? group.iconUrl ?? null,
    badges: (model.badges || []).filter((badge): badge is string => Boolean(compactLabel(badge))),
    capabilities: (model.capabilities || []).filter((capability): capability is string => Boolean(compactLabel(capability))),
    isDefault: model.isDefault ?? null,
    vendorCode,
    vendorLabel: groupLabel(group, "其他"),
    imageParameters: model.imageParameters ?? inferImageParametersForModel(model, group),
    estimatedCreditCost: typeof model.estimatedCreditCost === "number" ? model.estimatedCreditCost : undefined,
  }
}

export function buildComposerModelGroupsFromResponse(
  response: ModelOptionsResponse | ModelOptionGroup[] | null | undefined,
): ComposerModelGroup[] {
  const groups = Array.isArray(response) ? response : response?.groups
  if (!groups?.length) return []

  return groups
    .map((group, index) => ({
      key: compactLabel(group.vendorCode) || compactLabel(group.provider) || `vendor:${index}`,
      label: groupLabel(group, "其他"),
      iconUrl: group.iconUrl ?? null,
      models: (group.models || []).map((model) => toComposerModelOption(model, group)),
    }))
    .filter((group) => group.models.length > 0)
}

function inferVendorFromText(text: string): { key: string; label: string } {
  const value = normalize(text)
  if (includesAny(value, ["kling", "可灵"])) return { key: "kling", label: "Kling" }
  if (includesAny(value, ["veo", "google"])) return { key: "veo", label: "Veo" }
  if (includesAny(value, ["openai", "gpt", "dall", "chatgpt"])) return { key: "openai", label: "OpenAI" }
  if (includesAny(value, ["siliconflow", "silicon flow", "z-image", "z image", "tongyi"])) return { key: "siliconflow", label: "SiliconFlow" }
  if (includesAny(value, ["wan", "通义", "wan ai"])) return { key: "wan", label: "Wan AI" }
  if (includesAny(value, ["flux"])) return { key: "flux", label: "Flux AI" }
  const firstToken = compactLabel(text).split(/\s+/)[0]
  return { key: normalize(firstToken) || "other", label: firstToken || "其他" }
}

export function buildComposerModelGroupsFromTools(tools: ToolSummary[], mode: CreatorMode): ComposerModelGroup[] {
  const candidates = tools.filter((tool) => isOnlineTool(tool) && toolMatchesMode(tool, mode) && isRealModelBoundTool(tool))
  const grouped = new Map<string, ComposerModelGroup>()

  for (const tool of candidates) {
    const label =
      compactLabel(tool.modelConfigName) ||
      compactLabel(tool.modelName) ||
      compactLabel(tool.toolName) ||
      tool.toolCode
    const vendor = inferVendorFromText(`${tool.modelConfigName || ""} ${tool.modelName || ""} ${tool.toolName || ""} ${tool.toolCode || ""}`)
    if (!grouped.has(vendor.key)) {
      grouped.set(vendor.key, {
        key: vendor.key,
        label: vendor.label,
        models: [],
      })
    }
    grouped.get(vendor.key)!.models.push({
      key: `model:${tool.modelConfigId ?? tool.toolCode}`,
      label,
      toolCode: tool.toolCode,
      modelConfigId: tool.modelConfigId ?? null,
      modelName: tool.modelName ?? null,
      description: tool.description ?? null,
      iconUrl: tool.frontendStyle?.modelIconUrl || tool.coverUrl || null,
      vendorCode: vendor.key,
      vendorLabel: vendor.label,
      imageParameters: inferImageParametersFromText(`${vendor.key} ${vendor.label} ${label} ${tool.modelName || ""} ${tool.modelConfigName || ""} ${tool.toolCode || ""} ${tool.toolName || ""}`),
      estimatedCreditCost: tool.estimatedCreditCost,
      variableCreditPricing: tool.variableCreditPricing,
    })
  }

  return [...grouped.values()].filter((group) => group.models.length > 0)
}

function imageSizeOptionsFromParameters(params?: ImageGenerationParameters | null): ComposerFormatValueOption<string>[] {
  return (params?.sizes || [])
    .map((option) => {
      const value = compactLabel(option?.value)
      const label = compactLabel(option?.label) || value
      return value ? { label, value } : null
    })
    .filter((option): option is ComposerFormatValueOption<string> => Boolean(option))
}

function imageCountOptionsFromParameters(params?: ImageGenerationParameters | null): ComposerFormatValueOption<number>[] {
  return (params?.counts || [])
    .map((value) => Number(value))
    .filter((value) => Number.isInteger(value) && value > 0)
    .map((value) => ({ label: String(value), value }))
}

function imageQualityOptionsFromParameters(params?: ImageGenerationParameters | null): ComposerFormatValueOption<string>[] {
  return (params?.qualities || [])
    .map((option) => {
      const value = compactLabel(option?.value)
      const label = compactLabel(option?.label) || value
      return value ? { label, value } : null
    })
    .filter((option): option is ComposerFormatValueOption<string> => Boolean(option))
}

function defaultImageSize(params: ImageGenerationParameters | null | undefined, options: ComposerFormatValueOption<string>[]): string | undefined {
  const raw = compactLabel(params?.defaultSize)
  if (raw && options.some((option) => option.value === raw)) return raw
  return options[0]?.value
}

function defaultImageCount(params: ImageGenerationParameters | null | undefined, options: ComposerFormatValueOption<number>[]): number | undefined {
  const raw = typeof params?.defaultCount === "number" ? params.defaultCount : undefined
  if (raw && options.some((option) => option.value === raw)) return raw
  return options[0]?.value
}

function defaultImageQuality(params: ImageGenerationParameters | null | undefined, options: ComposerFormatValueOption<string>[]): string | undefined {
  const raw = compactLabel(params?.defaultQuality)
  if (raw && options.some((option) => option.value === raw)) return raw
  return options[0]?.value
}

export function buildComposerFormatOptions(
  fields: ToolField[] = [],
  imageParameters?: ImageGenerationParameters | null,
): ComposerFormatOptions {
  const durationField = findField(fields, ["duration", "time", "seconds", "时长", "秒", "长度"])
  const qualityField = findField(fields, ["quality", "resolution", "mode", "tier", "清晰", "分辨率", "画质", "质量", "档位", "模式"])
  const ratioField = findField(fields, ["ratio", "aspect", "比例", "画幅", "尺寸"])
  const countField = findField(fields, ["count", "num", "quantity", "output count", "image count", "num images", "batch size", "数量", "张数"])

  const duration = fieldOptions(durationField)
    .map((option) => {
      const numeric = numberFromOption(optionValue(option) || optionLabel(option))
      return numeric === null ? null : { label: optionLabel(option), value: numeric }
    })
    .filter((option): option is ComposerFormatValueOption<number> => Boolean(option))

  let quality = fieldOptions(qualityField).map((option) => ({
    label: optionLabel(option),
    value: optionValue(option),
  }))

  let ratio = fieldOptions(ratioField).map((option) => ({
    label: optionLabel(option),
    value: optionValue(option),
  }))

  let count = fieldOptions(countField)
    .map((option) => {
      const numeric = numberFromOption(optionValue(option) || optionLabel(option))
      return numeric === null ? null : { label: optionLabel(option), value: numeric }
    })
    .filter((option): option is ComposerFormatValueOption<number> => Boolean(option))
  if (count.length === 0 && countField && (countField.fieldType === "number" || countField.fieldType === "slider")) {
    count.push(...[1, 2, 3, 4].map((value) => ({ label: String(value), value })))
  }
  const modelImageSizes = imageSizeOptionsFromParameters(imageParameters)
  const modelImageCounts = imageCountOptionsFromParameters(imageParameters)
  const modelImageQualities = imageQualityOptionsFromParameters(imageParameters)
  if (ratio.length === 0 && modelImageSizes.length > 0) {
    ratio = modelImageSizes
  }
  if (count.length === 0 && modelImageCounts.length > 0) {
    count = modelImageCounts
  }
  if (quality.length === 0 && modelImageQualities.length > 0) {
    quality = modelImageQualities
  }

  return {
    duration,
    quality,
    ratio,
    count,
    labels: {
      duration: durationField?.fieldName || "视频长度",
      quality: qualityField?.fieldName || (modelImageQualities.length > 0 ? "质量" : "分辨率"),
      ratio: ratioField?.fieldName || (modelImageSizes.length > 0 ? "图片尺寸" : "长宽比"),
      count: countField?.fieldName || "输出数量",
    },
    defaults: {
      duration: defaultNumber(durationField, duration),
      quality: qualityField ? defaultString(qualityField, quality) : defaultImageQuality(imageParameters, quality),
      ratio: ratioField ? defaultString(ratioField, ratio) : defaultImageSize(imageParameters, ratio),
      count: countField ? defaultNumber(countField, count) : defaultImageCount(imageParameters, count),
    },
  }
}

export function resolveCreatorTask(
  state: ComposerState,
  tool: ToolDetail,
  advancedParams: Record<string, unknown> = {},
): ResolvedCreatorTask {
  const params: Record<string, unknown> = { ...advancedParams }
  const handled = new Set<string>(
    Object.entries(advancedParams)
      .filter(([, value]) => hasFieldValue(value))
      .map(([key]) => key),
  )
  const fields = [...(tool.fields || [])].sort((a, b) => a.sortOrder - b.sortOrder)
  const ratioField = findField(fields, ["ratio", "aspect", "比例", "画幅", "尺寸"])
  const countField = findField(fields, ["count", "num", "quantity", "output count", "image count", "num images", "batch size", "数量", "张数"])

  setIfField(params, handled, findField(fields, ["prompt", "提示词", "描述", "内容", "text"]), state.prompt, true)
  setIfField(params, handled, ratioField, state.ratio, true)
  setIfField(params, handled, findField(fields, ["duration", "时长", "秒"]), state.durationSeconds, state.mode !== "image")
  const qualityField = findField(fields, ["quality", "resolution", "mode", "tier", "清晰", "分辨率", "画质", "质量", "档位", "模式"])
  setIfField(params, handled, qualityField, state.quality, state.mode !== "image")
  setIfField(
    params,
    handled,
    countField,
    state.outputCount,
    state.mode === "image",
  )
  if (state.mode === "image") {
    if (!ratioField && hasFieldValue(state.ratio)) {
      params.imageSize = state.ratio
    }
    if (!countField && hasFieldValue(state.outputCount)) {
      params.count = state.outputCount
    }
    if (!qualityField && hasFieldValue(state.quality)) {
      params.quality = state.quality
    }
  }
  const uploadedAssetUrls = [
    ...(state.uploadedAssetUrl ? [state.uploadedAssetUrl] : []),
    ...(state.uploadedAssetUrls || []),
  ].filter((url, index, urls) => Boolean(url) && urls.indexOf(url) === index)
  const mappedUploadedAssetUrls = new Set<string>()
  for (const url of uploadedAssetUrls) {
    const targetField = uploadFields(fields).find((field) => !handled.has(field.fieldKey))
    if (!targetField) break
    setIfField(params, handled, targetField, url)
    mappedUploadedAssetUrls.add(url)
  }
  if (state.mode === "image") {
    const firstUnmappedImageUrl = uploadedAssetUrls.find((url) => !mappedUploadedAssetUrls.has(url))
    if (firstUnmappedImageUrl && !hasFieldValue(params.sourceImageUrl)) {
      params.sourceImageUrl = firstUnmappedImageUrl
      handled.add("sourceImageUrl")
    }
  }

  const missingRequiredFields = fields
    .filter((field) => field.required)
    .filter((field) => !hasFieldValue(params[field.fieldKey]))
    .map((field) => field.fieldKey)

  return {
    toolCode: tool.toolCode,
    params,
    missingRequiredFields,
    handledFieldKeys: [...handled],
  }
}

export function getAdvancedFields(tool: ToolDetail | null, handledFieldKeys: string[]): ToolField[] {
  if (!tool) return []
  const handled = new Set(handledFieldKeys)
  return (tool.fields || []).filter((field) => !handled.has(field.fieldKey))
}
