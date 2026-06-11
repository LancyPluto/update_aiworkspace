import type { ToolField } from "@/api/types"

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
    const v = raw[f.fieldKey]
    if (f.fieldType === "number") {
      if (v === "" || v === undefined || v === null) {
        if (!f.required) continue
      }
      const n = typeof v === "number" ? v : Number(v)
      if (!Number.isNaN(n)) out[f.fieldKey] = n
    } else if (f.fieldType === "checkbox") {
      out[f.fieldKey] = Boolean(v)
    } else if ((f.fieldType === "select" || f.fieldType === "radio") && v === "__none__") {
      continue
    } else if ((f.fieldType === "select" || f.fieldType === "radio") && v === "__custom__") {
      const custom = raw[`${f.fieldKey}Custom`]
      if (custom !== undefined && custom !== null && String(custom).trim() !== "") {
        out[f.fieldKey] = String(custom).trim()
      }
    } else if (v !== undefined && v !== null && v !== "") {
      out[f.fieldKey] = typeof v === "string" ? v.trim() : v
    }
  }
  return out
}
