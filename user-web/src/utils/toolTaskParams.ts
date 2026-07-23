import type { ToolField } from "@/api/types"
import { canonicalFieldOptionValue, isFieldVisible, parseFieldMeta } from "@/utils/fieldUiMeta"

export type AspectRatioOptionInput = string | { label: string; value: string }
export type AspectRatioOption = { label: string; value: string }

export function buildAspectRatioOptions(
  toolOptions: AspectRatioOptionInput[] = [],
  genericRatios: unknown[] = [],
  normalizeValue: (value: unknown) => string = (value) => String(value ?? "").trim().replace(/\s+/g, ""),
): AspectRatioOption[] {
  const source: AspectRatioOptionInput[] = toolOptions.length > 0
    ? toolOptions
    : genericRatios.map((ratio) => ({ label: String(ratio), value: String(ratio) }))
  const options: AspectRatioOption[] = []
  const seen = new Set<string>()

  for (const option of source) {
    const label = typeof option === "string" ? option : option.label
    const rawValue = typeof option === "string" ? option : option.value
    const value = normalizeValue(rawValue)
    if (!value || seen.has(value)) continue
    seen.add(value)
    options.push({ label: label.trim() || (value === "auto" ? "智能" : value), value })
  }

  return options.length > 0 ? options : [{ label: "智能", value: "auto" }]
}

export function buildAspectRatioTaskParams(
  value: unknown,
  fieldKey?: string | null,
): Record<string, string> {
  const ratio = String(value ?? "").trim()
  if (!ratio) return {}

  const params: Record<string, string> = {
    aspectRatio: ratio,
    imageRatio: ratio,
  }
  const originalKey = fieldKey?.trim()
  if (originalKey) params[originalKey] = ratio
  return params
}

/**
 * 将动态表单原始值转换为后端任务所需的 params。
 * 与 ToolUse/Page.vue 中的逻辑保持一致，供工具弹窗与详情页共用。
 */
export function buildTaskParams(
  fields: ToolField[],
  raw: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const f of fields) {
    if (parseFieldMeta(f).submitPolicy === "ui_only") continue
    if (!isFieldVisible(f, raw)) continue
    const v = raw[f.fieldKey]
    if (f.fieldType === "number") {
      if (v === "" || v === undefined || v === null) {
        if (!f.required) continue
      }
      const n = typeof v === "number" ? v : Number(v)
      if (!Number.isNaN(n)) out[f.fieldKey] = n
    } else if (f.fieldType === "checkbox") {
      out[f.fieldKey] = Boolean(v)
    } else if (f.fieldType === "select" || f.fieldType === "radio" || f.fieldType === "aspect_ratio") {
      const optionValue = canonicalFieldOptionValue(f, v)
      if (optionValue === "__none__") continue
      if (optionValue === "__custom__") {
        const custom = raw[`${f.fieldKey}Custom`]
        if (custom !== undefined && custom !== null && String(custom).trim() !== "") {
          out[f.fieldKey] = String(custom).trim()
        }
      } else if (optionValue !== undefined && optionValue !== null && optionValue !== "") {
        out[f.fieldKey] = optionValue
      }
    } else if (Array.isArray(v)) {
      if (v.length > 0) out[f.fieldKey] = v
    } else if (v !== undefined && v !== null && v !== "") {
      out[f.fieldKey] = typeof v === "string" ? v.trim() : v
    }
  }
  return out
}
