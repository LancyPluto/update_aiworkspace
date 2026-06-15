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
  minCount?: number
  maxCount?: number
  accept?: string
  libraryEnabled?: boolean
  libraryKind?: string
}

export interface ParsedFieldOptions {
  options: Array<{ label: string; value: string; promptPrefix?: string }>
  meta: FieldUiMeta
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

export function parseFieldOptionsJson(raw?: string | null): ParsedFieldOptions {
  const empty: ParsedFieldOptions = { options: [], meta: {} }
  if (!raw || !raw.trim()) return empty
  try {
    const parsed = JSON.parse(raw) as unknown
    if (Array.isArray(parsed)) {
      return {
        options: parsed.map(normalizeOptionRow).filter(Boolean) as ParsedFieldOptions["options"],
        meta: {},
      }
    }
    if (!parsed || typeof parsed !== "object") return empty
    const obj = parsed as Record<string, unknown>
    const optionRows = Array.isArray(obj.options) ? obj.options : []
    const meta: FieldUiMeta = {}
    if (obj.core === true || obj.isCore === true) meta.core = true
    if (obj.uiTier === "simple" || obj.uiTier === "advanced" || obj.uiTier === "all") meta.uiTier = obj.uiTier
    if (typeof obj.uiGroup === "string" && obj.uiGroup.trim()) meta.uiGroup = obj.uiGroup.trim()
    if (typeof obj.uiGroupLabel === "string" && obj.uiGroupLabel.trim()) meta.uiGroupLabel = obj.uiGroupLabel.trim()
    if (typeof obj.uiOrder === "number") meta.uiOrder = obj.uiOrder
    if (obj.visibleWhen && typeof obj.visibleWhen === "object" && !Array.isArray(obj.visibleWhen)) {
      const visibleWhen: Record<string, string[]> = {}
      for (const [key, value] of Object.entries(obj.visibleWhen as Record<string, unknown>)) {
        if (Array.isArray(value)) {
          visibleWhen[key] = value.map((item) => String(item))
        } else if (value !== undefined && value !== null) {
          visibleWhen[key] = [String(value)]
        }
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
    if (typeof obj.minCount === "number") meta.minCount = Math.max(0, obj.minCount)
    if (typeof obj.maxCount === "number") meta.maxCount = Math.max(1, obj.maxCount)
    if (typeof obj.accept === "string" && obj.accept.trim()) meta.accept = obj.accept.trim()
    if (typeof obj.libraryEnabled === "boolean") meta.libraryEnabled = obj.libraryEnabled
    if (typeof obj.libraryKind === "string" && obj.libraryKind.trim()) meta.libraryKind = obj.libraryKind.trim()
    return {
      options: optionRows.map(normalizeOptionRow).filter(Boolean) as ParsedFieldOptions["options"],
      meta,
    }
  } catch {
    return empty
  }
}

export function buildFieldOptionsJson(
  options: ParsedFieldOptions["options"],
  meta: FieldUiMeta,
  isCore = false,
): string | undefined {
  const cleaned = options
    .map((row) => ({
      label: row.label.trim() || row.value.trim(),
      value: row.value.trim() || row.label.trim(),
      promptPrefix: row.promptPrefix?.trim() || undefined,
    }))
    .filter((row) => row.value)
  const payload: Record<string, unknown> = { ...meta }
  if (isCore) payload.core = true
  if (cleaned.length > 0) payload.options = cleaned
  if (Object.keys(payload).length === 0) return undefined
  return JSON.stringify(payload)
}

function normalizeFieldValue(value: unknown): string {
  if (typeof value === "boolean") return value ? "true" : "false"
  if (value === undefined || value === null) return ""
  return String(value)
}

export function isCustomModeAdvanced(values: Record<string, unknown>): boolean {
  const raw = values.customMode ?? values.custom_mode
  if (typeof raw === "boolean") return raw
  return String(raw ?? "").trim().toLowerCase() === "true"
}

export function isFieldVisible(fieldKey: string, meta: FieldUiMeta, values: Record<string, unknown>): boolean {
  if (!meta.visibleWhen) return true
  for (const [depKey, allowed] of Object.entries(meta.visibleWhen)) {
    const current = normalizeFieldValue(values[depKey])
    const normalizedAllowed = allowed.map((item) => normalizeFieldValue(item))
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
    if (!normalizedAllowed.includes(current)) return false
  }
  return true
}

export function filterFieldsByTier(
  fields: Array<{ fieldKey: string; meta: FieldUiMeta }>,
  advanced: boolean,
): Array<{ fieldKey: string; meta: FieldUiMeta }> {
  return fields.filter(({ meta }) => {
    const tier = meta.uiTier || "all"
    if (tier === "all") return true
    if (advanced) return tier === "advanced"
    return tier === "simple"
  })
}

export function resolveMaxLength(meta: FieldUiMeta, values: Record<string, unknown>): number | undefined {
  const model = normalizeFieldValue(values.model)
  if (meta.maxLengthByModel) {
    if (model && meta.maxLengthByModel[model] !== undefined) return meta.maxLengthByModel[model]
    if (meta.maxLengthByModel.default !== undefined) return meta.maxLengthByModel.default
  }
  return meta.maxLength
}

export function groupFields<T extends { fieldKey: string; meta: FieldUiMeta }>(
  fields: T[],
): Array<{ key: string; label: string; fields: T[] }> {
  const groups = new Map<string, { label: string; fields: T[] }>()
  for (const field of fields) {
    const key = field.meta.uiGroup || "__default__"
    const label = field.meta.uiGroupLabel || (key === "__default__" ? "参数" : key)
    const existing = groups.get(key)
    if (existing) {
      existing.fields.push(field)
    } else {
      groups.set(key, { label, fields: [field] })
    }
  }
  return [...groups.entries()].map(([key, group]) => ({ key, label: group.label, fields: group.fields }))
}
