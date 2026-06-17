import type { ToolField } from "@/api/types"
import { parseFieldMeta } from "@/utils/fieldUiMeta"

export type KlingOmniReferType = "base" | "feature"
export type KlingOmniKeepOriginalSound = "yes" | "no"

export interface KlingOmniVideoReference {
  video_url: string
  refer_type?: KlingOmniReferType
  keep_original_sound?: KlingOmniKeepOriginalSound
}

export interface KlingOmniVideoEditorItem {
  id: string
  videoUrl: string
  referType: KlingOmniReferType
  keepOriginalSound: KlingOmniKeepOriginalSound
}

export const KLING_OMNI_REFER_TYPE_OPTIONS: Array<{ label: string; value: KlingOmniReferType }> = [
  { label: "待编辑视频（base）", value: "base" },
  { label: "特征参考视频（feature）", value: "feature" },
]

export const KLING_OMNI_KEEP_SOUND_OPTIONS: Array<{ label: string; value: KlingOmniKeepOriginalSound }> = [
  { label: "不保留原声", value: "no" },
  { label: "保留原声", value: "yes" },
]

export function klingOmniVideoMax(field: Pick<ToolField, "options" | "optionsJson">): number {
  const maxCount = Number(parseFieldMeta(field).maxCount)
  return Number.isFinite(maxCount) && maxCount > 0 ? maxCount : 4
}

export function klingOmniVideoMin(field: Pick<ToolField, "options" | "optionsJson">): number {
  return parseFieldMeta(field).minCount ?? 0
}

export function klingOmniVideoAccept(field: Pick<ToolField, "options" | "optionsJson">): string {
  return parseFieldMeta(field).accept || "video/*"
}

export function createEmptyKlingOmniVideoItem(): KlingOmniVideoEditorItem {
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    videoUrl: "",
    referType: "base",
    keepOriginalSound: "no",
  }
}

function parseRawArray(value: unknown): unknown[] {
  if (Array.isArray(value)) return value
  if (typeof value === "string" && value.trim()) {
    try {
      const parsed = JSON.parse(value) as unknown
      if (Array.isArray(parsed)) return parsed
    } catch {
      return value
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean)
    }
  }
  return []
}

function normalizeReferType(value: unknown): KlingOmniReferType {
  return value === "feature" ? "feature" : "base"
}

function normalizeKeepOriginalSound(value: unknown): KlingOmniKeepOriginalSound {
  return value === "yes" ? "yes" : "no"
}

export function parseKlingOmniVideoEditorItems(value: unknown, limit: number): KlingOmniVideoEditorItem[] {
  return parseRawArray(value)
    .slice(0, limit)
    .map((item) => {
      if (item && typeof item === "object" && !Array.isArray(item)) {
        const row = item as Record<string, unknown>
        const videoUrl = String(row.video_url ?? row.videoUrl ?? row.video ?? row.url ?? "").trim()
        return {
          id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          videoUrl,
          referType: normalizeReferType(row.refer_type ?? row.referType),
          keepOriginalSound: normalizeKeepOriginalSound(row.keep_original_sound ?? row.keepOriginalSound),
        }
      }
      return {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        videoUrl: String(item ?? "").trim(),
        referType: "base",
        keepOriginalSound: "no",
      }
    })
}

export function serializeKlingOmniVideoItems(items: KlingOmniVideoEditorItem[]): KlingOmniVideoReference[] {
  return items
    .map((item) => {
      const videoUrl = item.videoUrl.trim()
      if (!videoUrl) return null
      return {
        video_url: videoUrl,
        refer_type: item.referType === "feature" ? "feature" : "base",
        keep_original_sound: item.keepOriginalSound === "yes" ? "yes" : "no",
      }
    })
    .filter((item): item is KlingOmniVideoReference => item !== null)
}

export function hasBaseKlingOmniVideo(items: KlingOmniVideoEditorItem[]): boolean {
  return items.some((item) => item.videoUrl.trim() && item.referType === "base")
}

export function validateKlingOmniVideoItems(
  items: KlingOmniVideoEditorItem[],
  field: Pick<ToolField, "fieldName" | "options" | "optionsJson" | "required">,
): { valid: boolean; message?: string } {
  const minCount = klingOmniVideoMin(field)
  const maxCount = klingOmniVideoMax(field)
  const serialized = serializeKlingOmniVideoItems(items)
  for (const item of items) {
    if (!item.videoUrl.trim()) {
      return { valid: false, message: `${field.fieldName} 中存在未上传的参考视频` }
    }
  }
  if (serialized.length < minCount) {
    return { valid: false, message: `${field.fieldName} 至少需要 ${minCount} 个参考视频` }
  }
  if (serialized.length > maxCount) {
    return { valid: false, message: `${field.fieldName} 最多选择 ${maxCount} 个参考视频` }
  }
  if (field.required && serialized.length === 0) {
    return { valid: false, message: `请填写：${field.fieldName}` }
  }
  return { valid: true }
}
