import type { ToolDetail, ToolField, ToolFrontendStyle } from "@/api/types"

const IMAGE_FIELD_KEYS = ["sourceImageUrl", "imageUrl", "referenceImageUrl", "inputImageUrl"]

function upper(value?: string | null): string {
  return (value || "").trim().toUpperCase()
}

function clean(value?: unknown): string {
  return value === undefined || value === null ? "" : String(value).trim()
}

function hasComparison(style?: ToolFrontendStyle | null): boolean {
  return Boolean(clean(style?.comparisonOriginalUrl) && clean(style?.comparisonEffectUrl))
}

export function isImageTemplateTool(tool?: Partial<ToolDetail> | null): boolean {
  if (!tool) return false
  const input = upper(tool.inputModality)
  const output = upper(tool.outputModality)
  const type = upper(tool.toolType)
  const style = tool.frontendStyle
  const displayMode = style?.mediaDisplayMode
  const isImageToImage = output === "IMAGE" && (input === "IMAGE" || input === "MULTIMODAL" || type === "IMAGE_TO_IMAGE")
  return isImageToImage && (displayMode === "comparison" || displayMode === "effect" || hasComparison(style))
}

export function primaryImageField(tool: Pick<ToolDetail, "fields"> | Partial<ToolDetail>): ToolField | null {
  const fields = [...(tool.fields || [])].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  return (
    fields.find((field) => IMAGE_FIELD_KEYS.includes(field.fieldKey)) ||
    fields.find((field) => field.fieldType === "image" || field.fieldType === "image_upload") ||
    null
  )
}

export function compactOptionFields(tool: Pick<ToolDetail, "fields"> | Partial<ToolDetail>): ToolField[] {
  const imageField = primaryImageField(tool)
  return [...(tool.fields || [])]
    .filter((field) => field.fieldKey !== imageField?.fieldKey)
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
}

export function defaultFieldValue(field: ToolField): unknown {
  if (field.defaultValue !== undefined && field.defaultValue !== null && field.defaultValue !== "") {
    return field.defaultValue
  }
  if ((field.fieldType === "select" || field.fieldType === "radio") && Array.isArray(field.options) && field.options.length) {
    const option = field.options[0]
    return typeof option === "string" ? option : option.value
  }
  if (field.fieldType === "checkbox") return false
  if (field.fieldType === "slider") return 50
  return ""
}

export function initialImageTemplateOptions(tool: Pick<ToolDetail, "fields"> | Partial<ToolDetail>): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  for (const field of compactOptionFields(tool)) {
    values[field.fieldKey] = defaultFieldValue(field)
  }
  return values
}

export function buildImageTemplateTaskParams(
  tool: Pick<ToolDetail, "fields"> | Partial<ToolDetail>,
  uploadedImageUrl: string,
  optionValues: Record<string, unknown>,
): Record<string, unknown> {
  const imageField = primaryImageField(tool)
  const imageKey = imageField?.fieldKey || "sourceImageUrl"
  const params: Record<string, unknown> = {
    [imageKey]: uploadedImageUrl,
  }

  for (const field of compactOptionFields(tool)) {
    const value = optionValues[field.fieldKey]
    const fallback = defaultFieldValue(field)
    const resolved = value === undefined || value === null || value === "" ? fallback : value
    if (resolved !== undefined && resolved !== null && resolved !== "") {
      params[field.fieldKey] = resolved
    }
  }

  if (!("sourceImageUrl" in params)) {
    params.sourceImageUrl = uploadedImageUrl
  }
  return params
}
