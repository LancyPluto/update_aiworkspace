import type { ToolField, ToolFieldPayload } from "@/lib/api/types"

export type FieldTypeValue =
  | "text"
  | "textarea"
  | "select"
  | "number"
  | "radio"
  | "checkbox"
  | "slider"
  | "image"
  | "file"

export const FIELD_TYPE_OPTIONS: Array<{ value: FieldTypeValue; label: string; hint: string }> = [
  { value: "text", label: "单行文本", hint: "短文本，如产品名" },
  { value: "textarea", label: "多行文本", hint: "长描述、脚本等" },
  { value: "select", label: "下拉选择", hint: "用户从预设选项中选一项" },
  { value: "radio", label: "单选按钮", hint: "适合 3–6 个互斥选项，如画面比例" },
  { value: "number", label: "数字", hint: "数量、时长秒数等" },
  { value: "checkbox", label: "勾选", hint: "是/否开关" },
  { value: "slider", label: "滑块", hint: "0–100 强度类参数" },
  { value: "image", label: "图片 URL", hint: "参考图链接" },
  { value: "file", label: "文件 URL", hint: "音频等文件链接" },
]

export type OptionPresetKey =
  | "aspect_ratio_image"
  | "aspect_ratio_video"
  | "image_style"
  | "copy_tone"
  | "duration_video"
  | "avatar_style"
  | "scene_digital_human"

export const OPTION_PRESETS: Record<
  OptionPresetKey,
  { label: string; options: FieldOptionRow[] }
> = {
  aspect_ratio_image: {
    label: "图片比例（文生图）",
    options: ["1:1", "4:3", "3:4", "16:9", "9:16"].map((v) => ({ label: v, value: v })),
  },
  aspect_ratio_video: {
    label: "视频比例",
    options: ["16:9", "9:16", "1:1"].map((v) => ({ label: v, value: v })),
  },
  image_style: {
    label: "画面风格",
    options: [
      { label: "无", value: "__none__" },
      { label: "写实", value: "写实", promptPrefix: "realistic photography style, natural lighting, detailed textures" },
      { label: "电商", value: "电商", promptPrefix: "commercial product photography, clean background, premium ecommerce poster style" },
      { label: "插画", value: "插画", promptPrefix: "editorial illustration style, clean composition, rich colors" },
      { label: "动漫", value: "动漫", promptPrefix: "anime style, cel shading, expressive character design, vibrant colors" },
      { label: "极简", value: "极简", promptPrefix: "minimalist style, clean lines, ample negative space, restrained palette" },
      { label: "国潮", value: "国潮", promptPrefix: "modern Chinese guochao style, oriental motifs, bold decorative composition" },
      { label: "自定义", value: "__custom__" },
    ],
  },
  copy_tone: {
    label: "文案语气",
    options: ["专业", "亲切", "种草", "高级", "幽默"].map((v) => ({ label: v, value: v })),
  },
  duration_video: {
    label: "视频时长（秒）",
    options: ["5", "10", "15", "30", "60"].map((v) => ({ label: `${v} 秒`, value: v })),
  },
  avatar_style: {
    label: "数字人形象",
    options: ["职业主播", "科技感主持人", "亲和力导购", "知识博主"].map((v) => ({ label: v, value: v })),
  },
  scene_digital_human: {
    label: "数字人场景",
    options: ["直播间", "产品展示台", "办公室", "纯色演播室"].map((v) => ({ label: v, value: v })),
  },
}

export type FieldOptionRow = { label: string; value: string; promptPrefix?: string }

export interface EditableField {
  fieldKey: string
  fieldName: string
  fieldType: FieldTypeValue
  placeholder: string
  required: boolean
  sortOrder: number
  options: FieldOptionRow[]
}

export function supportsOptions(fieldType: string): boolean {
  return fieldType === "select" || fieldType === "radio"
}

export function parseOptionsJson(raw?: string | null): FieldOptionRow[] {
  if (!raw || !raw.trim()) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.map((item) => {
      if (typeof item === "string") return { label: item, value: item }
      if (item && typeof item === "object") {
        const row = item as { label?: string; value?: string; promptPrefix?: string }
        const value = String(row.value ?? row.label ?? "").trim()
        const label = String(row.label ?? row.value ?? "").trim()
        return {
          label: label || value,
          value: value || label,
          promptPrefix: row.promptPrefix ? String(row.promptPrefix).trim() : undefined,
        }
      }
      return { label: "", value: "" }
    }).filter((row) => row.value)
  } catch {
    return []
  }
}

export function optionsFromToolField(field: ToolField): FieldOptionRow[] {
  if (Array.isArray(field.options) && field.options.length > 0) {
    return field.options.map((item) => {
      if (typeof item === "string") return { label: item, value: item }
      const row = item as { label?: string; value?: string; promptPrefix?: string }
      const value = String(row.value ?? row.label ?? "").trim()
      const label = String(row.label ?? row.value ?? "").trim()
      return {
        label: label || value,
        value: value || label,
        promptPrefix: row.promptPrefix ? String(row.promptPrefix).trim() : undefined,
      }
    })
  }
  return parseOptionsJson(field.optionsJson)
}

export function buildOptionsJson(options: FieldOptionRow[]): string | undefined {
  const cleaned = options
    .map((row) => ({
      label: row.label.trim() || row.value.trim(),
      value: row.value.trim() || row.label.trim(),
      promptPrefix: row.promptPrefix?.trim() || undefined,
    }))
    .filter((row) => row.value)
  if (cleaned.length === 0) return undefined
  return JSON.stringify(cleaned)
}

export function editableFromToolField(field: ToolField, index: number): EditableField {
  const fieldType = (field.fieldType || "text").toLowerCase() as FieldTypeValue
  return {
    fieldKey: field.fieldKey,
    fieldName: field.fieldName,
    fieldType,
    placeholder: field.placeholder || "",
    required: field.required !== false,
    sortOrder: field.sortOrder ?? index + 1,
    options: supportsOptions(fieldType) ? optionsFromToolField(field) : [],
  }
}

export function editableFromPayload(field: Partial<ToolFieldPayload>, index: number): EditableField {
  const fieldType = (field.fieldType || "text").toLowerCase() as FieldTypeValue
  return {
    fieldKey: String(field.fieldKey || "").trim(),
    fieldName: String(field.fieldName || "").trim(),
    fieldType,
    placeholder: field.placeholder ? String(field.placeholder) : "",
    required: field.required !== false,
    sortOrder: Number(field.sortOrder ?? index + 1),
    options: supportsOptions(fieldType) ? parseOptionsJson(field.optionsJson) : [],
  }
}

export function toFieldPayload(field: EditableField, index: number): ToolFieldPayload {
  if (!field.fieldKey.trim() || !field.fieldName.trim()) {
    throw new Error(`第 ${index + 1} 个字段需填写字段键与显示名`)
  }
  const optionsJson = supportsOptions(field.fieldType) ? buildOptionsJson(field.options) : undefined
  return {
    fieldKey: field.fieldKey.trim(),
    fieldName: field.fieldName.trim(),
    fieldType: field.fieldType,
    placeholder: field.placeholder.trim() || undefined,
    optionsJson,
    required: field.required,
    sortOrder: field.sortOrder || index + 1,
  }
}

export function parseFieldsJson(json: string): EditableField[] {
  const parsed = JSON.parse(json) as Partial<ToolFieldPayload>[]
  if (!Array.isArray(parsed)) throw new Error("字段配置必须是 JSON 数组")
  return parsed.map((item, index) => editableFromPayload(item, index))
}

export function serializeFields(fields: EditableField[]): string {
  return JSON.stringify(fields.map((field, index) => toFieldPayload(field, index)), null, 2)
}

export function createEmptyField(sortOrder: number): EditableField {
  return {
    fieldKey: "",
    fieldName: "",
    fieldType: "text",
    placeholder: "",
    required: false,
    sortOrder,
    options: [],
  }
}

export function applyOptionPreset(field: EditableField, preset: OptionPresetKey): EditableField {
  return {
    ...field,
    options: OPTION_PRESETS[preset].options.map((row) => ({ ...row })),
  }
}

export function detectPreset(field: EditableField): OptionPresetKey | "" {
  if (!supportsOptions(field.fieldType) || field.options.length === 0) return ""
  const serialized = buildOptionsJson(field.options)
  for (const [key, preset] of Object.entries(OPTION_PRESETS) as Array<[OptionPresetKey, (typeof OPTION_PRESETS)[OptionPresetKey]]>) {
    if (serialized === buildOptionsJson(preset.options)) return key
  }
  return ""
}
