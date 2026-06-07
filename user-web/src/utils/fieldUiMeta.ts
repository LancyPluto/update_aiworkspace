import type { ToolField } from "@/api/types"

export type FieldUiTier = "simple" | "advanced" | "all"

export interface FieldSliderMeta {
  min: number
  max: number
  step: number
}

export interface FieldUiMeta {
  core?: boolean
  uiTier?: FieldUiTier
  uiGroup?: string
  uiGroupLabel?: string
  uiOrder?: number
  visibleWhen?: Record<string, string[]>
  slider?: FieldSliderMeta
  maxLength?: number
  maxLengthByModel?: Record<string, number>
  defaultValue?: string | number | boolean
}

function normalizeOptionRow(item: unknown): { label: string; value: string; promptPrefix?: string } | null {
  if (typeof item === "string") {
    const value = item.trim()
    return value ? { label: value, value } : null
  }
  if (item && typeof item === "object") {
    const row = item as { label?: string; value?: string; promptPrefix?: string }
    const value = String(row.value ?? row.label ?? "").trim()
    const label = String(row.label ?? row.value ?? "").trim()
    if (!value && !label) return null
    return {
      label: label || value,
      value: value || label,
      promptPrefix: row.promptPrefix ? String(row.promptPrefix).trim() : undefined,
    }
  }
  return null
}

export function parseFieldMeta(field: Pick<ToolField, "options" | "optionsJson">): FieldUiMeta {
  if (field.options && typeof field.options === "object" && !Array.isArray(field.options)) {
    return parseMetaObject(field.options as Record<string, unknown>)
  }
  if (!field.optionsJson?.trim()) return {}
  try {
    const parsed = JSON.parse(field.optionsJson) as unknown
    if (Array.isArray(parsed)) return {}
    if (!parsed || typeof parsed !== "object") return {}
    return parseMetaObject(parsed as Record<string, unknown>)
  } catch {
    return {}
  }
}

function parseMetaObject(obj: Record<string, unknown>): FieldUiMeta {
  const meta: FieldUiMeta = {}
  if (obj.core === true || obj.isCore === true) meta.core = true
  if (obj.uiTier === "simple" || obj.uiTier === "advanced" || obj.uiTier === "all") meta.uiTier = obj.uiTier
  if (typeof obj.uiGroup === "string" && obj.uiGroup.trim()) meta.uiGroup = obj.uiGroup.trim()
  if (typeof obj.uiGroupLabel === "string" && obj.uiGroupLabel.trim()) meta.uiGroupLabel = obj.uiGroupLabel.trim()
  if (typeof obj.uiOrder === "number") meta.uiOrder = obj.uiOrder
  if (obj.visibleWhen && typeof obj.visibleWhen === "object" && !Array.isArray(obj.visibleWhen)) {
    const visibleWhen: Record<string, string[]> = {}
    for (const [key, value] of Object.entries(obj.visibleWhen as Record<string, unknown>)) {
      if (Array.isArray(value)) visibleWhen[key] = value.map((item) => String(item))
      else if (value !== undefined && value !== null) visibleWhen[key] = [String(value)]
    }
    if (Object.keys(visibleWhen).length > 0) meta.visibleWhen = visibleWhen
  }
  if (obj.slider && typeof obj.slider === "object") {
    const slider = obj.slider as Record<string, unknown>
    meta.slider = {
      min: Number(slider.min ?? 0),
      max: Number(slider.max ?? 1),
      step: Number(slider.step ?? 0.01),
    }
  }
  if (typeof obj.maxLength === "number") meta.maxLength = obj.maxLength
  if (obj.maxLengthByModel && typeof obj.maxLengthByModel === "object") {
    meta.maxLengthByModel = Object.fromEntries(
      Object.entries(obj.maxLengthByModel as Record<string, unknown>).map(([key, value]) => [key, Number(value)]),
    )
  }
  if (obj.defaultValue !== undefined) meta.defaultValue = obj.defaultValue as string | number | boolean
  return meta
}

export function fieldOptionsFromMeta(field: ToolField): Array<string | { label: string; value: string; promptPrefix?: string }> {
  if (Array.isArray(field.options) && field.options.length > 0) return field.options
  if (!field.optionsJson?.trim()) return []
  try {
    const parsed = JSON.parse(field.optionsJson) as unknown
    const rows = Array.isArray(parsed)
      ? parsed
      : parsed && typeof parsed === "object" && Array.isArray((parsed as { options?: unknown }).options)
        ? (parsed as { options: unknown[] }).options
        : []
    return rows
      .map(normalizeOptionRow)
      .filter(Boolean) as Array<{ label: string; value: string; promptPrefix?: string }>
  } catch {
    return []
  }
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

export function isFieldVisible(field: ToolField, values: Record<string, unknown>): boolean {
  const meta = parseFieldMeta(field)
  if (!meta.visibleWhen) return true
  for (const [depKey, allowed] of Object.entries(meta.visibleWhen)) {
    const current = normalizeFieldValue(values[depKey])
    if (depKey === "instrumental") {
      const boolAllowed = allowed.map((item) => String(item).toLowerCase())
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

export function filterFieldsForUi(
  fields: ToolField[],
  values: Record<string, unknown>,
  options?: { excludeCore?: boolean; coreFieldKey?: string | null },
): ToolField[] {
  const advanced = isCustomModeAdvanced(values)
  return fields.filter((field) => {
    if (options?.excludeCore && (field.fieldKey === options.coreFieldKey || parseFieldMeta(field).core)) return false
    const tier = parseFieldMeta(field).uiTier || "all"
    if (tier === "advanced" && !advanced) return false
    if (tier === "simple" && advanced) return false
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
  const groups = new Map<string, { label: string; fields: ToolField[] }>()
  for (const field of fields) {
    const meta = parseFieldMeta(field)
    const key = meta.uiGroup || "__default__"
    const label = meta.uiGroupLabel || (key === "__default__" ? "参数" : key)
    const bucket = groups.get(key)
    if (bucket) bucket.fields.push(field)
    else groups.set(key, { label, fields: [field] })
  }
  return [...groups.entries()].map(([key, group]) => ({ key, label: group.label, fields: group.fields }))
}

export function defaultFieldValue(field: ToolField): unknown {
  const meta = parseFieldMeta(field)
  if (meta.defaultValue !== undefined) return meta.defaultValue
  const options = fieldOptionsFromMeta(field)
  if ((field.fieldType === "select" || field.fieldType === "radio") && options.length) {
    const first = options[0]
    return typeof first === "string" ? first : first.value
  }
  if (field.fieldType === "checkbox") return false
  if (field.fieldType === "multi_image") return []
  if (field.fieldType === "slider") {
    const slider = meta.slider
    if (slider) return slider.min + (slider.max - slider.min) / 2
    return 0.65
  }
  return ""
}

export function isCoreField(field: Pick<ToolField, "options" | "optionsJson">): boolean {
  return parseFieldMeta(field).core === true
}
