import type { ToolField } from "@/api/types"
import { parseFieldMeta } from "@/utils/fieldUiMeta"

export type MediaListFieldType = "multi_image" | "multi_video"

export function isMediaListField(field: Pick<ToolField, "fieldType">): boolean {
  return field.fieldType === "multi_image" || field.fieldType === "multi_video"
}

export function mediaListFieldKind(field: Pick<ToolField, "fieldType">): "image" | "video" {
  return field.fieldType === "multi_video" ? "video" : "image"
}

export function mediaListMax(field: Pick<ToolField, "options" | "optionsJson">): number {
  return parseFieldMeta(field).maxCount ?? (mediaListFieldKind(field as ToolField) === "video" ? 4 : 8)
}

export function mediaListMin(field: Pick<ToolField, "options" | "optionsJson">): number {
  return parseFieldMeta(field).minCount ?? 0
}

export function mediaListAccept(field: Pick<ToolField, "fieldType" | "options" | "optionsJson">): string {
  const meta = parseFieldMeta(field)
  if (meta.accept) return meta.accept
  return mediaListFieldKind(field) === "video" ? "video/*" : "image/*"
}

export function mediaListLibraryEnabled(field: Pick<ToolField, "options" | "optionsJson">): boolean {
  return parseFieldMeta(field).libraryEnabled ?? true
}

export function mediaListLibraryKind(field: Pick<ToolField, "fieldType" | "options" | "optionsJson">): string {
  const meta = parseFieldMeta(field)
  if (meta.libraryKind) return meta.libraryKind
  return mediaListFieldKind(field)
}

export function mediaListUnitLabel(field: Pick<ToolField, "fieldType">): string {
  return mediaListFieldKind(field) === "video" ? "个参考视频" : "张参考图"
}

export function parseMediaListValue(value: unknown, limit: number): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => String(item).trim()).filter(Boolean).slice(0, limit)
  }
  if (typeof value === "string" && value.trim()) {
    try {
      const parsed = JSON.parse(value) as unknown
      if (Array.isArray(parsed)) {
        return parsed
          .map((item) => {
            if (typeof item === "string") return item.trim()
            if (item && typeof item === "object") {
              const row = item as Record<string, unknown>
              for (const key of ["image_url", "video_url", "url", "image", "video"]) {
                const raw = row[key]
                if (typeof raw === "string" && raw.trim()) return raw.trim()
              }
            }
            return ""
          })
          .filter(Boolean)
          .slice(0, limit)
      }
    } catch {
      return value
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean)
        .slice(0, limit)
    }
  }
  return []
}

export function normalizeMediaListValues(values: string[], limit: number): string[] {
  const seen = new Set<string>()
  return values
    .map((item) => item.trim())
    .filter((item) => {
      if (!item || seen.has(item)) return false
      seen.add(item)
      return true
    })
    .slice(0, limit)
}
