import type {
  ModelRequestSchema,
  ModelRequestSchemaField,
  ModelRequestSchemaRequiresAnyGroup,
  ToolField,
  ToolSupportedModel,
} from "@/api/types"

type FieldOptionValue = string | number | boolean
type FieldOption = { label: string; value: FieldOptionValue }
type ToolOutputContext = {
  outputModality?: string | null
  toolType?: string | null
}

const TOOL_UI_META_KEYS = [
  "core",
  "isCore",
  "uiRole",
  "role",
  "placement",
  "uiTier",
  "uiGroup",
  "uiGroupLabel",
  "uiOrder",
  "layoutHint",
  "helpText",
  "submitPolicy",
  "libraryEnabled",
  "libraryKind",
] as const

const IMAGE_OUTPUT_COUNT_KEYS = new Set([
  "count",
  "n",
  "batchsize",
  "imagecount",
  "imagescount",
  "numimages",
  "numberofimages",
  "outputcount",
])

const MODEL_OWNED_MEDIA_FIELD_TYPES = new Set<ToolField["fieldType"]>([
  "image",
  "image_upload",
  "video_upload",
  "audio_upload",
  "multi_image",
  "multi_video",
  "multi_audio",
  "omni_video_list",
])

function normalizedFieldKey(value: string): string {
  return value.trim().toLowerCase().replace(/[^a-z0-9]/g, "")
}

function isImageOutputCountKey(value: string): boolean {
  return IMAGE_OUTPUT_COUNT_KEYS.has(normalizedFieldKey(value))
}

function isModelOwnedMediaField(field: ToolField): boolean {
  if (MODEL_OWNED_MEDIA_FIELD_TYPES.has(field.fieldType)) return true
  if (field.fieldType !== "file") return false
  const meta = recordValue(field.options)
  const accept = String(meta?.accept || "").toLowerCase()
  return /(?:image|video|audio)\//.test(accept)
}

function hasImageGenerationCapability(model?: ToolSupportedModel | null): boolean {
  return (model?.capabilities || []).some((capability) =>
    String(capability).trim().toUpperCase() === "IMAGE_GENERATION",
  )
}

function isImageToolOutput(context?: ToolOutputContext | null): boolean {
  if (!context) return true
  const outputModality = String(context.outputModality || "").trim().toUpperCase()
  if (outputModality) return outputModality === "IMAGE"
  const toolType = String(context.toolType || "").trim().toUpperCase()
  if (toolType) return toolType === "IMAGE" || toolType.startsWith("IMAGE_")
  return true
}

function isSingleImageOutputField(field: ModelRequestSchemaField): boolean {
  if (!isImageOutputCountKey(field.key)) return false
  const options = normalizeOptions(field.enum)
  if (options.length > 0) {
    return options.every((option) => Number(option.value) === 1)
  }
  return typeof field.max === "number" && field.max <= 1
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function normalizeOptions(value: unknown): FieldOption[] {
  if (!Array.isArray(value)) return []
  const rows: FieldOption[] = []
  for (const item of value) {
    if (typeof item === "string") {
      const normalized = item.trim()
      if (normalized) rows.push({ label: normalized, value: normalized })
      continue
    }
    if (typeof item === "number" && Number.isFinite(item)) {
      rows.push({ label: String(item), value: item })
      continue
    }
    if (typeof item === "boolean") {
      rows.push({ label: String(item), value: item })
      continue
    }
    const row = recordValue(item)
    if (!row) continue
    const rawValue = row.value ?? row.label
    let optionValue: FieldOptionValue | null = null
    if (typeof rawValue === "string" && rawValue.trim()) optionValue = rawValue.trim()
    else if (typeof rawValue === "number" && Number.isFinite(rawValue)) optionValue = rawValue
    else if (typeof rawValue === "boolean") optionValue = rawValue
    if (optionValue === null) continue
    const label = String(row.label ?? optionValue).trim()
    rows.push({ label: label || String(optionValue), value: optionValue })
  }
  return rows
}

function normalizeCondition(value: unknown): Record<string, unknown> | undefined {
  const source = recordValue(value)
  if (!source) return undefined
  const result: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(source)) {
    if ((key === "anyOf" || key === "allOf") && Array.isArray(raw)) {
      const conditions = raw.map(normalizeCondition).filter(Boolean)
      if (conditions.length > 0) result[key] = conditions
      continue
    }
    if (key === "not") {
      const condition = normalizeCondition(raw)
      if (condition) result.not = condition
      continue
    }
    const values = Array.isArray(raw) ? raw : [raw]
    const normalized = values
      .filter((item) => item !== undefined && item !== null)
      .filter((item) => ["string", "number", "boolean"].includes(typeof item))
    if (normalized.length > 0) result[key] = normalized
  }
  return Object.keys(result).length > 0 ? result : undefined
}

function pickToolUiMeta(field?: ToolField): Record<string, unknown> {
  const source = recordValue(field?.options)
  if (!source) return {}
  const result: Record<string, unknown> = {}
  for (const key of TOOL_UI_META_KEYS) {
    if (source[key] !== undefined) result[key] = source[key]
  }
  return result
}

function schemaFieldType(field: ModelRequestSchemaField, options: FieldOption[]): ToolField["fieldType"] {
  const key = field.key.trim()
  if (key === "generationMode") {
    return options.length <= 4 ? "radio" : "select"
  }

  const itemType = String(field.itemType || "").trim().toLowerCase()
  if (field.type === "array") {
    if (itemType === "image") return "multi_image"
    if (itemType === "video") return "multi_video"
    if (itemType === "audio") return "multi_audio"
    return "textarea"
  }
  if (field.control === "upload") {
    if (itemType === "image") return "image_upload"
    if (itemType === "video") return "video_upload"
    if (itemType === "audio") return "audio_upload"
    return "file"
  }
  if (field.control === "segmented" && options.length > 0) return "radio"
  if (field.control === "select" || options.length > 0) return "select"
  if (field.type === "boolean") return "checkbox"
  if (field.type === "number" || field.type === "integer") {
    return field.control === "slider" ? "slider" : "number"
  }
  if (field.control === "textarea") return "textarea"
  return "text"
}

function effectiveFieldType(
  field: ModelRequestSchemaField,
  modelFieldType: ToolField["fieldType"],
  toolField?: ToolField,
): ToolField["fieldType"] {
  if (!toolField) return modelFieldType
  const itemType = String(field.itemType || "").trim().toLowerCase()
  const hasEnum = Array.isArray(field.enum) && field.enum.length > 0
  if (
    field.key.trim() === "generationMode"
    || field.type === "array"
    || field.control === "upload"
    || field.type === "boolean"
    || field.type === "number"
    || field.type === "integer"
    || hasEnum
    || ["image", "video", "audio"].includes(itemType)
  ) {
    return modelFieldType
  }
  if (
    (modelFieldType === "text" || modelFieldType === "textarea")
    && (toolField.fieldType === "text" || toolField.fieldType === "textarea")
  ) {
    return toolField.fieldType
  }
  return modelFieldType
}

function schemaMeta(field: ModelRequestSchemaField, options: FieldOption[]): Record<string, unknown> {
  const meta: Record<string, unknown> = {}
  if (options.length > 0) meta.options = options
  if (field.default !== undefined) meta.defaultValue = field.default
  const visibleWhen = normalizeCondition(field.visibleWhen)
  if (visibleWhen) meta.visibleWhen = visibleWhen
  const requiredWhen = normalizeCondition(field.requiredWhen)
  if (requiredWhen) meta.requiredWhen = requiredWhen
  if (typeof field.minItems === "number") meta.minCount = Math.max(0, field.minItems)
  if (typeof field.maxItems === "number") meta.maxCount = Math.max(1, field.maxItems)
  if (Array.isArray(field.requiresAny)) {
    const requiresAnyFields = field.requiresAny.map((item) => String(item).trim()).filter(Boolean)
    if (requiresAnyFields.length > 0) meta.requiresAnyFields = requiresAnyFields
  }
  if (typeof field.min === "number") meta.minValue = field.min
  if (typeof field.max === "number") meta.maxValue = field.max
  if (typeof field.step === "number") meta.step = field.step
  if (typeof field.maxLength === "number") meta.maxLength = Math.max(0, field.maxLength)
  if (field.control === "slider" && typeof field.min === "number" && typeof field.max === "number") {
    meta.slider = {
      min: field.min,
      max: field.max,
      step: typeof field.step === "number" ? field.step : 1,
    }
  }
  if (typeof field.accept === "string" && field.accept.trim()) meta.accept = field.accept.trim()
  if (typeof field.helpText === "string" && field.helpText.trim()) meta.helpText = field.helpText.trim()
  if (["image", "video", "audio"].includes(String(field.itemType || "").trim().toLowerCase())) {
    meta.uiRole = "reference_material"
  }
  if (field.key.trim() === "generationMode" && options.length === 1) meta.uiHidden = true
  return meta
}

export function parseModelRequestSchema(value: ToolSupportedModel["requestSchemaJson"]): ModelRequestSchema | null {
  let source: unknown = value
  if (typeof source === "string") {
    if (!source.trim()) return null
    try {
      source = JSON.parse(source)
    } catch {
      return null
    }
  }
  const schema = recordValue(source)
  if (!schema || String(schema.version ?? "") !== "1" || !Array.isArray(schema.fields)) return null
  const fields = schema.fields.filter((item): item is ModelRequestSchemaField => {
    const row = recordValue(item)
    return Boolean(row && typeof row.key === "string" && row.key.trim() && typeof row.type === "string")
  })
  if (fields.length === 0) return null
  const requiresAnyGroups: ModelRequestSchemaRequiresAnyGroup[] = []
  if (Array.isArray(schema.requiresAnyGroups)) {
    for (const item of schema.requiresAnyGroups) {
      const group = recordValue(item)
      if (!group || !Array.isArray(group.fields)) continue
      const groupFields = [...new Set(group.fields.map((field) => String(field).trim()).filter(Boolean))]
      if (groupFields.length === 0) continue
      const when = normalizeCondition(group.when)
      requiresAnyGroups.push({ fields: groupFields, ...(when ? { when } : {}) })
    }
  }
  return {
    version: "1",
    fields,
    ...(requiresAnyGroups.length > 0 ? { requiresAnyGroups } : {}),
  }
}

export function buildEffectiveToolFields(
  toolFields: ToolField[],
  model?: ToolSupportedModel | null,
  context?: ToolOutputContext | null,
): ToolField[] {
  const imageGenerationModel = hasImageGenerationCapability(model) && isImageToolOutput(context)
  const schema = parseModelRequestSchema(model?.requestSchemaJson)
  if (!schema) {
    return imageGenerationModel
      ? toolFields.filter((field) => !isImageOutputCountKey(field.fieldKey))
      : toolFields
  }
  const toolFieldsByKey = new Map(toolFields.map((field) => [field.fieldKey, field]))
  const schemaFieldKeys = new Set<string>()
  const schemaFields = schema.fields.map((schemaField, index) => {
    const key = schemaField.key.trim()
    schemaFieldKeys.add(key)
    const toolField = toolFieldsByKey.get(key)
    const options = normalizeOptions(schemaField.enum)
    const modelFieldType = schemaFieldType(schemaField, options)
    const fieldType = effectiveFieldType(schemaField, modelFieldType, toolField)
    const meta = {
      ...schemaMeta(schemaField, options),
      ...pickToolUiMeta(toolField),
    }
    if (key === "prompt" && meta.core === undefined && meta.isCore === undefined) meta.core = true
    if (imageGenerationModel && isSingleImageOutputField(schemaField)) {
      meta.uiHidden = true
      if (meta.defaultValue === undefined) meta.defaultValue = 1
    }
    return {
      fieldKey: key,
      fieldName: toolField?.fieldName?.trim() || schemaField.label?.trim() || key,
      fieldType,
      placeholder: toolField?.placeholder ?? schemaField.helpText ?? null,
      options: meta,
      required: schemaField.required === true,
      executionRequired: schemaField.required === true,
      userRequired: schemaField.required === true,
      defaultValue: null,
      sortOrder: toolField?.sortOrder ?? index,
    }
  })
  const merged = [
    ...schemaFields,
    ...toolFields.filter((field) =>
      !schemaFieldKeys.has(field.fieldKey)
      && !isModelOwnedMediaField(field)
      && (!imageGenerationModel || !isImageOutputCountKey(field.fieldKey)),
    ),
  ]
  return merged
    .map((field, index) => ({ field, index }))
    .sort((left, right) => (left.field.sortOrder ?? 999) - (right.field.sortOrder ?? 999) || left.index - right.index)
    .map(({ field }) => field)
}

function fieldMeta(field: ToolField): Record<string, unknown> {
  return recordValue(field.options) || {}
}

function enumValues(field: ToolField): FieldOptionValue[] {
  return normalizeOptions(fieldMeta(field).options).map((option) => option.value)
}

export function generationModeValue(fields: ToolField[], params?: Record<string, unknown> | null): string {
  const field = fields.find((item) => item.fieldKey === "generationMode")
  if (!field) return ""
  const allowed = enumValues(field).map(String)
  const requested = String(params?.generationMode ?? "").trim()
  if (requested && (allowed.length === 0 || allowed.includes(requested))) return requested
  const configuredDefault = fieldMeta(field).defaultValue
  const defaultValue = configuredDefault === undefined || configuredDefault === null
    ? ""
    : String(configuredDefault)
  if (defaultValue && (allowed.length === 0 || allowed.includes(defaultValue))) return defaultValue
  return allowed[0] || ""
}

function compatibleValue(field: ToolField, value: unknown): unknown {
  if (value === undefined || value === null) return undefined
  const allowed = enumValues(field)
  if (allowed.length > 0) {
    const exact = allowed.find((item) => Object.is(item, value))
    const canonical = exact ?? allowed.find((item) => String(item) === String(value))
    if (canonical === undefined) return undefined
    value = canonical
  }
  const meta = fieldMeta(field)
  if (field.fieldType === "multi_image" || field.fieldType === "multi_video" || field.fieldType === "multi_audio") {
    if (!Array.isArray(value)) return undefined
    const max = typeof meta.maxCount === "number" ? Math.max(1, meta.maxCount) : value.length
    return value.map((item) => String(item).trim()).filter(Boolean).slice(0, max)
  }
  if (field.fieldType === "number" || field.fieldType === "slider") {
    const numeric = Number(value)
    if (!Number.isFinite(numeric)) return undefined
    if (typeof meta.minValue === "number" && numeric < meta.minValue) return undefined
    if (typeof meta.maxValue === "number" && numeric > meta.maxValue) return undefined
    return numeric
  }
  if (field.fieldType === "checkbox") return typeof value === "boolean" ? value : undefined
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return value
  return undefined
}

export function prepareModelParams(
  fields: ToolField[],
  source?: Record<string, unknown> | null,
): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  const values = source || {}
  for (const field of fields) {
    const compatible = compatibleValue(field, values[field.fieldKey])
    if (compatible !== undefined) result[field.fieldKey] = compatible
  }
  const mode = generationModeValue(fields, result)
  if (mode) result.generationMode = mode
  return result
}

export function resolveDefaultModelConfigId(
  models: ToolSupportedModel[],
  configuredDefault?: number | null,
): number | null {
  if (configuredDefault != null && models.some((model) => model.modelConfigId === configuredDefault)) {
    return configuredDefault
  }
  return models.find((model) => model.isDefault)?.modelConfigId ?? models[0]?.modelConfigId ?? null
}

export function hasSchemaBackedModel(models: ToolSupportedModel[]): boolean {
  return models.some((model) => parseModelRequestSchema(model.requestSchemaJson) !== null)
}
