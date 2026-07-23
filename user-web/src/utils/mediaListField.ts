import type { ToolField } from "@/api/types"
import { parseFieldMeta } from "@/utils/fieldUiMeta"

export type MediaListFieldType = "multi_image" | "multi_video" | "multi_audio"

export function isMediaListField(field: Pick<ToolField, "fieldType">): boolean {
  return field.fieldType === "multi_image" || field.fieldType === "multi_video" || field.fieldType === "multi_audio"
}

export function mediaListFieldKind(field: Pick<ToolField, "fieldType">): "image" | "video" | "audio" {
  if (field.fieldType === "multi_video") return "video"
  if (field.fieldType === "multi_audio") return "audio"
  return "image"
}

export function mediaListMax(field: Pick<ToolField, "options">): number {
  const kind = mediaListFieldKind(field as ToolField)
  return parseFieldMeta(field).maxCount ?? (kind === "image" ? 8 : 4)
}

export function mediaListMin(field: Pick<ToolField, "options">): number {
  return parseFieldMeta(field).minCount ?? 0
}

export function mediaListAccept(field: Pick<ToolField, "fieldType" | "options">): string {
  const meta = parseFieldMeta(field)
  if (meta.accept) return meta.accept
  const kind = mediaListFieldKind(field)
  if (kind === "video") return "video/*"
  if (kind === "audio") return "audio/*"
  return "image/*"
}

export function mediaListLibraryEnabled(field: Pick<ToolField, "options">): boolean {
  return parseFieldMeta(field).libraryEnabled ?? true
}

export function mediaListLibraryKind(field: Pick<ToolField, "fieldType" | "options">): string {
  const meta = parseFieldMeta(field)
  if (meta.libraryKind) return meta.libraryKind
  return mediaListFieldKind(field)
}

export function mediaListUnitLabel(field: Pick<ToolField, "fieldType">): string {
  const kind = mediaListFieldKind(field)
  if (kind === "video") return "个参考视频"
  if (kind === "audio") return "段参考音频"
  return "张参考图"
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
