import type { ModelRequestSchemaRequiresAnyGroup, ToolField } from "@/api/types"

export type FieldUiTier = "simple" | "advanced" | "all"

export interface FieldSliderMeta {
  min: number
  max: number
  step: number
}

export interface FieldCondition {
  anyOf?: FieldCondition[]
  allOf?: FieldCondition[]
  not?: FieldCondition
  [key: string]: unknown
}

export interface FieldUiMeta {
  core?: boolean
  uiRole?: string
  placement?: string
  uiTier?: FieldUiTier
  uiGroup?: string
  uiGroupLabel?: string
  uiOrder?: number
  layoutHint?: string
  helpText?: string
  uiHidden?: boolean
  submitPolicy?: "submit" | "ui_only"
  visibleWhen?: FieldCondition
  requiredWhen?: FieldCondition
  slider?: FieldSliderMeta
  maxLength?: number
  maxLengthByModel?: Record<string, number>
  minValue?: number
  maxValue?: number
  step?: number
  defaultValue?: string | number | boolean
  minCount?: number
  maxCount?: number
  maxItems?: number
  requiresAnyFields?: string[]
  accept?: string
  maxSizeMb?: number
  requiresPublicUrl?: boolean
  minDuration?: number
  durationByOrientation?: Record<string, number>
  forceCharacterOrientation?: string
  allowedModes?: string[]
  libraryEnabled?: boolean
  libraryKind?: string
  unit?: string
}

export type FieldOptionValue = string | number | boolean

export interface FieldUiOption {
  label: string
  value: FieldOptionValue
}

function normalizeOptionValue(value: unknown): FieldOptionValue | null {
  if (typeof value === "string") {
    const normalized = value.trim()
    return normalized || null
  }
  if (typeof value === "number" && Number.isFinite(value)) return value
  if (typeof value === "boolean") return value
  return null
}

function normalizeOptionRow(item: unknown): FieldUiOption | null {
  const primitive = normalizeOptionValue(item)
  if (primitive !== null) return { label: String(primitive), value: primitive }
  if (item && typeof item === "object") {
    const row = item as { label?: unknown; value?: unknown }
    const value = normalizeOptionValue(row.value ?? row.label)
    if (value === null) return null
    const label = String(row.label ?? value).trim()
    return {
      label: label || String(value),
      value,
    }
  }
  return null
}

function parseFieldCondition(value: unknown): FieldCondition | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined
  const source = value as Record<string, unknown>
  const condition: FieldCondition = {}
  for (const [key, raw] of Object.entries(source)) {
    if ((key === "anyOf" || key === "allOf") && Array.isArray(raw)) {
      const children = raw.map(parseFieldCondition).filter(Boolean) as FieldCondition[]
      if (children.length > 0) condition[key] = children
      continue
    }
    if (key === "not") {
      const child = parseFieldCondition(raw)
      if (child) condition.not = child
      continue
    }
    const values = (Array.isArray(raw) ? raw : [raw])
      .filter((item) => item !== undefined && item !== null)
      .map((item) => String(item))
    if (values.length > 0) condition[key] = values
  }
  return Object.keys(condition).length > 0 ? condition : undefined
}

export function parseFieldMeta(field: Pick<ToolField, "options">): FieldUiMeta {
  if (field.options && typeof field.options === "object" && !Array.isArray(field.options)) {
    return parseMetaObject(field.options as Record<string, unknown>)
  }
  return {}
}

function parseMetaObject(obj: Record<string, unknown>): FieldUiMeta {
  const meta: FieldUiMeta = {}
  if (obj.core === true || obj.isCore === true) meta.core = true
  if (typeof obj.uiRole === "string" && obj.uiRole.trim()) meta.uiRole = obj.uiRole.trim()
  if (typeof obj.role === "string" && obj.role.trim()) meta.uiRole = obj.role.trim()
  if (typeof obj.placement === "string" && obj.placement.trim()) meta.placement = obj.placement.trim()
  if (obj.uiTier === "simple" || obj.uiTier === "advanced" || obj.uiTier === "all") meta.uiTier = obj.uiTier
  if (typeof obj.uiGroup === "string" && obj.uiGroup.trim()) meta.uiGroup = obj.uiGroup.trim()
  if (typeof obj.uiGroupLabel === "string" && obj.uiGroupLabel.trim()) meta.uiGroupLabel = obj.uiGroupLabel.trim()
  if (typeof obj.uiOrder === "number") meta.uiOrder = obj.uiOrder
  if (typeof obj.layoutHint === "string" && obj.layoutHint.trim()) meta.layoutHint = obj.layoutHint.trim()
  if (typeof obj.helpText === "string" && obj.helpText.trim()) meta.helpText = obj.helpText.trim()
  if (typeof obj.uiHidden === "boolean") meta.uiHidden = obj.uiHidden
  if (obj.submitPolicy === "ui_only") meta.submitPolicy = "ui_only"
  else if (obj.submitPolicy === "submit") meta.submitPolicy = "submit"
  const visibleWhen = parseFieldCondition(obj.visibleWhen)
  if (visibleWhen) meta.visibleWhen = visibleWhen
  const requiredWhen = parseFieldCondition(obj.requiredWhen)
  if (requiredWhen) meta.requiredWhen = requiredWhen
  if (obj.slider && typeof obj.slider === "object") {
    const slider = obj.slider as Record<string, unknown>
    meta.slider = {
      min: Number(slider.min ?? 0),
      max: Number(slider.max ?? 1),
      step: Number(slider.step ?? 0.01),
    }
  }
  if (typeof obj.maxLength === "number") meta.maxLength = obj.maxLength
  if (typeof obj.minValue === "number") meta.minValue = obj.minValue
  if (typeof obj.maxValue === "number") meta.maxValue = obj.maxValue
  if (typeof obj.step === "number") meta.step = obj.step
  if (obj.maxLengthByModel && typeof obj.maxLengthByModel === "object") {
    meta.maxLengthByModel = Object.fromEntries(
      Object.entries(obj.maxLengthByModel as Record<string, unknown>).map(([key, value]) => [key, Number(value)]),
    )
  }
  if (obj.defaultValue !== undefined) meta.defaultValue = obj.defaultValue as string | number | boolean
  if (typeof obj.minCount === "number") meta.minCount = Math.max(0, obj.minCount)
  if (typeof obj.maxCount === "number") meta.maxCount = Math.max(1, obj.maxCount)
  if (typeof obj.maxItems === "number") meta.maxItems = Math.max(1, obj.maxItems)
  if (Array.isArray(obj.requiresAnyFields)) {
    const requiresAnyFields = obj.requiresAnyFields.map((item) => String(item).trim()).filter(Boolean)
    if (requiresAnyFields.length > 0) meta.requiresAnyFields = requiresAnyFields
  }
  if (typeof obj.accept === "string" && obj.accept.trim()) meta.accept = obj.accept.trim()
  if (typeof obj.maxSizeMb === "number") meta.maxSizeMb = obj.maxSizeMb
  if (typeof obj.requiresPublicUrl === "boolean") meta.requiresPublicUrl = obj.requiresPublicUrl
  if (typeof obj.minDuration === "number") meta.minDuration = obj.minDuration
  if (obj.durationByOrientation && typeof obj.durationByOrientation === "object" && !Array.isArray(obj.durationByOrientation)) {
    meta.durationByOrientation = Object.fromEntries(
      Object.entries(obj.durationByOrientation as Record<string, unknown>).map(([key, value]) => [key, Number(value)]),
    )
  }
  if (typeof obj.forceCharacterOrientation === "string" && obj.forceCharacterOrientation.trim()) {
    meta.forceCharacterOrientation = obj.forceCharacterOrientation.trim()
  }
  if (Array.isArray(obj.allowedModes)) {
    meta.allowedModes = obj.allowedModes.map((item) => String(item).trim()).filter(Boolean)
  }
  if (typeof obj.libraryEnabled === "boolean") meta.libraryEnabled = obj.libraryEnabled
  if (typeof obj.libraryKind === "string" && obj.libraryKind.trim()) meta.libraryKind = obj.libraryKind.trim()
  if (typeof obj.unit === "string" && obj.unit.trim()) meta.unit = obj.unit.trim()
  return meta
}

export function fieldOptionsFromMeta(field: ToolField): FieldUiOption[] {
  const rows = Array.isArray(field.options)
    ? field.options
    : field.options && Array.isArray(field.options.options)
      ? field.options.options
      : []
  return rows
    .map(normalizeOptionRow)
    .filter(Boolean) as FieldUiOption[]
}

export function canonicalFieldOptionValue(field: ToolField, value: unknown): unknown {
  const options = fieldOptionsFromMeta(field)
  if (options.length === 0) return value
  const exact = options.find((option) => Object.is(option.value, value))
  if (exact) return exact.value
  const normalized = String(value ?? "")
  return options.find((option) => String(option.value) === normalized)?.value ?? value
}

function normalizeFieldValue(value: unknown): string {
  if (typeof value === "boolean") return value ? "true" : "false"
  if (value === undefined || value === null) return ""
  return String(value)
}

export function isCustomModeAdvanced(values: Record<string, unknown>): boolean {
  const raw = values.customMode ?? values.custom_mode
  if (typeof raw === "boolean") return raw
  return String(raw ?? "false").trim().toLowerCase() === "true"
}

export function matchesFieldCondition(condition: FieldCondition, values: Record<string, unknown>): boolean {
  if (condition.anyOf && !condition.anyOf.some((item) => matchesFieldCondition(item, values))) return false
  if (condition.allOf && !condition.allOf.every((item) => matchesFieldCondition(item, values))) return false
  if (condition.not && matchesFieldCondition(condition.not, values)) return false
  for (const [depKey, rawAllowed] of Object.entries(condition)) {
    if (depKey === "anyOf" || depKey === "allOf" || depKey === "not") continue
    const allowed = Array.isArray(rawAllowed) ? rawAllowed.map(String) : [String(rawAllowed)]
    const current = normalizeFieldValue(values[depKey])
    const boolAllowed = allowed.map((item) => item.toLowerCase())
    if (boolAllowed.length > 0 && boolAllowed.every((item) => item === "true" || item === "false")) {
      const asBool = current === "true" || current === "1"
      const match = boolAllowed.some((item) => {
        if (item === "true") return asBool
        if (item === "false") return !asBool
        return item === current
      })
      if (!match) return false
      continue
    }
    const normalizedAllowed = allowed.map((item) => normalizeFieldValue(item))
    if (!normalizedAllowed.includes(current)) return false
  }
  return true
}

function hasRequiredGroupValue(value: unknown): boolean {
  if (Array.isArray(value)) return value.length > 0
  if (typeof value === "string") return value.trim().length > 0
  if (value === undefined || value === null) return false
  if (typeof value === "object") return Object.keys(value as Record<string, unknown>).length > 0
  return true
}

export function validateRequiresAnyGroups(
  groups: ModelRequestSchemaRequiresAnyGroup[] | null | undefined,
  values: Record<string, unknown>,
  fields: ToolField[] = [],
): { valid: boolean; message?: string } {
  if (!Array.isArray(groups) || groups.length === 0) return { valid: true }
  const labels = new Map(fields.map((field) => [field.fieldKey, field.fieldName || field.fieldKey]))
  for (const group of groups) {
    if (!Array.isArray(group.fields) || group.fields.length === 0) continue
    if (group.when && !matchesFieldCondition(group.when as FieldCondition, values)) continue
    if (group.fields.some((key) => hasRequiredGroupValue(values[key]))) continue
    const names = group.fields.map((key) => labels.get(key) || key)
    return { valid: false, message: `请至少填写一项：${names.join("、")}` }
  }
  return { valid: true }
}

export function isFieldVisible(field: ToolField, values: Record<string, unknown>): boolean {
  const condition = parseFieldMeta(field).visibleWhen
  return !condition || matchesFieldCondition(condition, values)
}

export function isFieldRequired(field: ToolField, values: Record<string, unknown>): boolean {
  if (field.required || field.userRequired) return true
  const condition = parseFieldMeta(field).requiredWhen
  return Boolean(condition && matchesFieldCondition(condition, values))
}

export function filterFieldsForUi(
  fields: ToolField[],
  values: Record<string, unknown>,
  options?: { excludeCore?: boolean; coreFieldKey?: string | null; advancedModeEnabled?: boolean },
): ToolField[] {
  const advanced = isCustomModeAdvanced(values)
  return fields.filter((field) => {
    if (parseFieldMeta(field).uiHidden === true) return false
    if (options?.excludeCore && (field.fieldKey === options.coreFieldKey || parseFieldMeta(field).core)) return false
    const tier = parseFieldMeta(field).uiTier || "all"
    const advancedModeEnabled = options?.advancedModeEnabled ?? true
    if (advancedModeEnabled) {
      if (tier === "advanced" && !advanced) return false
      if (tier === "simple" && advanced) return false
    }
    return isFieldVisible(field, values)
  })
}

export function resolveMaxLength(field: ToolField, values: Record<string, unknown>): number | undefined {
  const meta = parseFieldMeta(field)
  const model = normalizeFieldValue(values.model)
  if (meta.maxLengthByModel) {
    if (model && meta.maxLengthByModel[model] !== undefined) return meta.maxLengthByModel[model]
    if (meta.maxLengthByModel.default !== undefined) return meta.maxLengthByModel.default
  }
  return meta.maxLength
}

export function groupVisibleFields(fields: ToolField[]): Array<{ key: string; label: string; fields: ToolField[] }> {
  const groups = new Map<string, { label: string; fields: ToolField[]; order: number }>()
  for (const field of fields) {
    const meta = parseFieldMeta(field)
    const key = meta.uiGroup || "__default__"
    const label = meta.uiGroupLabel || (key === "__default__" ? "参数" : key)
    const order = typeof meta.uiOrder === "number" ? meta.uiOrder : field.sortOrder ?? 999
    const bucket = groups.get(key)
    if (bucket) {
      bucket.fields.push(field)
      bucket.order = Math.min(bucket.order, order)
    } else {
      groups.set(key, { label, fields: [field], order })
    }
  }
  return [...groups.entries()]
    .sort((a, b) => a[1].order - b[1].order)
    .map(([key, group]) => ({
      key,
      label: group.label,
      fields: [...group.fields].sort((a, b) => {
        const aMeta = parseFieldMeta(a)
        const bMeta = parseFieldMeta(b)
        const aOrder = typeof aMeta.uiOrder === "number" ? aMeta.uiOrder : a.sortOrder ?? 999
        const bOrder = typeof bMeta.uiOrder === "number" ? bMeta.uiOrder : b.sortOrder ?? 999
        return aOrder - bOrder
      }),
    }))
}

export function defaultFieldValue(field: ToolField): unknown {
  const meta = parseFieldMeta(field)
  if (meta.defaultValue !== undefined) return meta.defaultValue
  const options = fieldOptionsFromMeta(field)
  if ((field.fieldType === "select" || field.fieldType === "radio" || field.fieldType === "aspect_ratio") && options.length) {
    const first = options[0]
    return typeof first === "string" ? first : first.value
  }
  if (field.fieldType === "checkbox") return false
  if (field.fieldType === "multi_image" || field.fieldType === "multi_video" || field.fieldType === "multi_audio" || field.fieldType === "subject_element_list" || field.fieldType === "omni_video_list") return []
  if (field.fieldType === "slider") {
    const slider = meta.slider
    if (slider) return slider.min + (slider.max - slider.min) / 2
    return 0.65
  }
  return ""
}

export function isCoreField(field: Pick<ToolField, "options">): boolean {
  return parseFieldMeta(field).core === true
}

export function resolveVisibleCoreField(
  fields: ToolField[],
  values: Record<string, unknown>,
): ToolField | null {
  return fields.find((field) => isCoreField(field) && isFieldVisible(field, values)) || null
}
