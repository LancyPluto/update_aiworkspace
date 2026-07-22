import type { ToolDetail, ToolField, ToolFrontendStyle } from "@/api/types"

export type ToolKind = "text" | "image" | "video" | "digitalHuman" | "audio" | "agent" | "other"

const VIDEO_FIELD_KEYS = ["sourceVideoUrl", "videoUrl", "inputVideoUrl", "sourceMediaUrl"]
const IMAGE_FIELD_KEYS = ["sourceImageUrl", "imageUrl", "referenceImageUrl", "inputImageUrl"]

function upper(value?: string | null): string {
  return (value || "").trim().toUpperCase()
}

function clean(value?: unknown): string {
  return value === undefined || value === null ? "" : String(value).trim()
}

function validToolKind(value?: string | null): ToolKind | "" {
  return ["text", "image", "video", "digitalHuman", "audio", "agent", "other"].includes(value || "")
    ? (value as ToolKind)
    : ""
}

function searchableToolText(tool?: Partial<ToolDetail> | null): string {
  return [
    tool?.toolCode,
    tool?.toolName,
    tool?.categoryName,
    tool?.toolType,
    tool?.toolKind,
  ].map((item) => clean(item).toLowerCase()).join(" ")
}

export function resolveToolKind(tool?: Partial<ToolDetail> | null): ToolKind {
  if (!tool) return "other"
  const explicit = validToolKind(tool.toolKind)
  if (explicit) return explicit
  const type = upper(tool.toolType)
  const input = upper(tool.inputModality)
  const output = upper(tool.outputModality)
  const text = searchableToolText(tool)

  if (type === "AGENT") return "agent"
  if (
    type.includes("DIGITAL_HUMAN") ||
    text.includes("digital_human") ||
    text.includes("digital-human") ||
    text.includes("数字人") ||
    text.includes("口播")
  ) {
    return "digitalHuman"
  }
  if (output === "AUDIO" || input === "AUDIO" || type.includes("AUDIO") || type.includes("SPEECH")) return "audio"
  if (output === "VIDEO" || type.includes("VIDEO")) return "video"
  if (output === "IMAGE" || input === "IMAGE" || type.includes("IMAGE")) return "image"
  if (output === "TEXT" || input === "TEXT" || type.includes("TEXT")) return "text"
  return "other"
}

function hasVideoDemo(style?: ToolFrontendStyle | null): boolean {
  return Boolean(clean(style?.beforeVideoUrl) || clean(style?.afterVideoUrl))
}

export function isVideoTemplateTool(tool?: Partial<ToolDetail> | null): boolean {
  if (!tool) return false
  const kind = resolveToolKind(tool)
  if (kind !== "video" && kind !== "digitalHuman") return false
  const style = tool.frontendStyle
  return style?.mediaDisplayMode === "effect" || style?.mediaDisplayMode === "comparison" || hasVideoDemo(style) || upper(tool.outputModality) === "VIDEO"
}

export function primaryMediaField(tool: Pick<ToolDetail, "fields"> | Partial<ToolDetail>, kind: ToolKind = "video"): ToolField | null {
  const fields = [...(tool.fields || [])].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  const preferredKeys = kind === "image" ? IMAGE_FIELD_KEYS : VIDEO_FIELD_KEYS
  return (
    fields.find((field) => preferredKeys.includes(field.fieldKey)) ||
    fields.find((field) =>
      kind === "image"
        ? field.fieldType === "image" || field.fieldType === "image_upload"
        : field.fieldType === "file" || field.fieldType === "video_upload"
    ) ||
    null
  )
}

export function compactMediaOptionFields(tool: Pick<ToolDetail, "fields"> | Partial<ToolDetail>, kind: ToolKind = "video"): ToolField[] {
  const mediaField = primaryMediaField(tool, kind)
  return [...(tool.fields || [])]
    .filter((field) => field.fieldKey !== mediaField?.fieldKey)
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
}

export function defaultMediaFieldValue(field: ToolField): unknown {
  if (field.defaultValue !== undefined && field.defaultValue !== null && field.defaultValue !== "") return field.defaultValue
  if ((field.fieldType === "select" || field.fieldType === "radio") && Array.isArray(field.options) && field.options.length) {
    const option = field.options[0]
    return typeof option === "string" ? option : option.value
  }
  if (field.fieldType === "checkbox") return false
  if (field.fieldType === "slider") return 50
  return ""
}

export function initialMediaTemplateOptions(tool: Pick<ToolDetail, "fields"> | Partial<ToolDetail>, kind: ToolKind = "video"): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  for (const field of compactMediaOptionFields(tool, kind)) {
    values[field.fieldKey] = defaultMediaFieldValue(field)
  }
  return values
}

export function buildMediaTemplateTaskParams(
  tool: Pick<ToolDetail, "fields"> | Partial<ToolDetail>,
  uploadedMediaUrl: string,
  optionValues: Record<string, unknown>,
  kind: ToolKind = "video",
): Record<string, unknown> {
  const mediaField = primaryMediaField(tool, kind)
  const mediaKey = mediaField?.fieldKey || (kind === "image" ? "sourceImageUrl" : "sourceVideoUrl")
  const params: Record<string, unknown> = { [mediaKey]: uploadedMediaUrl }

  for (const field of compactMediaOptionFields(tool, kind)) {
    const value = optionValues[field.fieldKey]
    const fallback = defaultMediaFieldValue(field)
    const resolved = value === undefined || value === null || value === "" ? fallback : value
    if (resolved !== undefined && resolved !== null && resolved !== "") params[field.fieldKey] = resolved
  }

  if (kind === "image" && !("sourceImageUrl" in params)) params.sourceImageUrl = uploadedMediaUrl
  if (kind !== "image" && !("sourceVideoUrl" in params)) params.sourceVideoUrl = uploadedMediaUrl
  return params
}
