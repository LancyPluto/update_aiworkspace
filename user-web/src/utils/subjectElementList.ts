import type { ToolField } from "@/api/types"
import { parseFieldMeta } from "@/utils/fieldUiMeta"

export type SubjectElementMode = "element_id" | "library_ref" | "image_element" | "video_element"

export interface SubjectElementEditorItem {
  id: string
  mode: SubjectElementMode
  elementId: string
  librarySubjectCode?: string
  libraryDisplayName?: string
  upstreamElementId?: string
  frontalImage: string
  referImages: string[]
  referVideo: string
}

export const SUBJECT_ELEMENT_MODE_OPTIONS: Array<{ label: string; value: SubjectElementMode }> = [
  { label: "从主体库选择", value: "library_ref" },
  { label: "已有主体 ID", value: "element_id" },
  { label: "图片主体", value: "image_element" },
  { label: "视频主体", value: "video_element" },
]

export function subjectElementMax(field: Pick<ToolField, "options" | "optionsJson">): number {
  return parseFieldMeta(field).maxCount ?? 7
}

export function subjectElementMin(field: Pick<ToolField, "options" | "optionsJson">): number {
  return parseFieldMeta(field).minCount ?? 0
}

function createEditorItem(mode: SubjectElementMode = "library_ref"): SubjectElementEditorItem {
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    mode,
    elementId: "",
    librarySubjectCode: "",
    libraryDisplayName: "",
    upstreamElementId: "",
    frontalImage: "",
    referImages: [],
    referVideo: "",
  }
}

function parseRawObjects(value: unknown): Record<string, unknown>[] {
  if (Array.isArray(value)) {
    return value.filter((item): item is Record<string, unknown> => !!item && typeof item === "object" && !Array.isArray(item))
  }
  if (typeof value === "string" && value.trim()) {
    try {
      const parsed = JSON.parse(value) as unknown
      if (Array.isArray(parsed)) {
        return parsed.filter((item): item is Record<string, unknown> => !!item && typeof item === "object" && !Array.isArray(item))
      }
    } catch {
      return []
    }
  }
  return []
}

function inferMode(row: Record<string, unknown>): SubjectElementMode {
  if (row.element_id !== undefined && row.element_id !== null && String(row.element_id).trim()) return "element_id"
  if (Array.isArray(row.refer_videos) && row.refer_videos.length > 0) return "video_element"
  if (row.frontal_image || (Array.isArray(row.refer_images) && row.refer_images.length > 0)) return "image_element"
  return "element_id"
}

export function parseSubjectElementEditorItems(value: unknown, limit: number): SubjectElementEditorItem[] {
  return parseRawObjects(value)
    .slice(0, limit)
    .map((row) => {
      const mode = inferMode(row)
      const referImages = Array.isArray(row.refer_images)
        ? row.refer_images.map((item) => String(item).trim()).filter(Boolean).slice(0, 4)
        : []
      const referVideos = Array.isArray(row.refer_videos)
        ? row.refer_videos.map((item) => String(item).trim()).filter(Boolean)
        : []
      return {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        mode,
        elementId: row.element_id !== undefined && row.element_id !== null ? String(row.element_id).trim() : "",
        librarySubjectCode: "",
        libraryDisplayName: "",
        upstreamElementId: "",
        frontalImage: typeof row.frontal_image === "string" ? row.frontal_image.trim() : "",
        referImages,
        referVideo: referVideos[0] || "",
      }
    })
}

export function createEmptySubjectElementItem(): SubjectElementEditorItem {
  return createEditorItem("library_ref")
}

export function serializeSubjectElementItems(items: SubjectElementEditorItem[]): Record<string, unknown>[] {
  return items
    .map((item) => {
      if (item.mode === "library_ref") {
        const elementId = (item.upstreamElementId || item.elementId).trim()
        return elementId ? { element_id: elementId } : null
      }
      if (item.mode === "element_id") {
        const elementId = item.elementId.trim()
        return elementId ? { element_id: elementId } : null
      }
      if (item.mode === "image_element") {
        const frontalImage = item.frontalImage.trim()
        const referImages = item.referImages.map((url) => url.trim()).filter(Boolean).slice(0, 4)
        if (!frontalImage && referImages.length === 0) return null
        const row: Record<string, unknown> = {}
        if (frontalImage) row.frontal_image = frontalImage
        if (referImages.length > 0) row.refer_images = referImages
        return row
      }
      const referVideo = item.referVideo.trim()
      return referVideo ? { refer_videos: [referVideo] } : null
    })
    .filter((item): item is Record<string, unknown> => item !== null)
}

export function validateSubjectElementItems(
  items: SubjectElementEditorItem[],
  field: Pick<ToolField, "fieldName" | "options" | "optionsJson" | "required">,
): { valid: boolean; message?: string } {
  const minCount = subjectElementMin(field)
  const maxCount = subjectElementMax(field)
  const serialized = serializeSubjectElementItems(items)
  if (serialized.length < minCount) {
    return { valid: false, message: `${field.fieldName} 至少需要 ${minCount} 个主体` }
  }
  if (serialized.length > maxCount) {
    return { valid: false, message: `${field.fieldName} 最多 ${maxCount} 个主体` }
  }
  for (const item of items) {
    if (item.mode === "library_ref" && !(item.upstreamElementId || item.elementId).trim()) {
      return { valid: false, message: `${field.fieldName} 中存在未选择的主体库条目` }
    }
    if (item.mode === "element_id" && !item.elementId.trim()) {
      return { valid: false, message: `${field.fieldName} 中存在未填写的主体 ID` }
    }
    if (item.mode === "image_element" && !item.frontalImage.trim() && item.referImages.length === 0) {
      return { valid: false, message: `${field.fieldName} 中的图片主体需至少上传一张参考图` }
    }
    if (item.mode === "video_element" && !item.referVideo.trim()) {
      return { valid: false, message: `${field.fieldName} 中的视频主体需上传参考视频` }
    }
  }
  if (field.required && serialized.length === 0) {
    return { valid: false, message: `请填写：${field.fieldName}` }
  }
  return { valid: true }
}
