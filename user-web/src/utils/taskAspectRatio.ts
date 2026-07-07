import type { TaskDetail } from "@/api/types"

const ASPECT_RATIO_ALIASES = new Map<string, string>([
  ["square", "1:1"],
  ["横屏", "16:9"],
  ["竖屏", "9:16"],
  ["方图", "1:1"],
  ["宽屏", "16:9"],
  ["landscape", "16:9"],
  ["portrait", "9:16"],
])

const RATIO_PATTERN = /\d+(?:\.\d+)?\s*[:：/／]\s*\d+(?:\.\d+)?/
const SIZE_PATTERN = /\d{2,5}\s*[x×*＊]\s*\d{2,5}/i

function normalizeRatioText(value: string): string {
  const normalized = value
    .trim()
    .replace(/[：／]/g, (char) => (char === "：" ? ":" : "/"))
    .replace(/[＊]/g, "*")
  return ASPECT_RATIO_ALIASES.get(normalized.toLowerCase()) ?? ASPECT_RATIO_ALIASES.get(normalized) ?? normalized
}

export function findAspectRatioText(value: unknown): string {
  if (!value || typeof value !== "object") return ""
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findAspectRatioText(item)
      if (found) return found
    }
    return ""
  }
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    const normalizedKey = key.toLowerCase()
    // 兼容 { aspectRatio: { label: "9:16", value: "9:16" } } 这类结构：即便 key 不是 ratio，也直接识别出形如 9:16 的值
    if (typeof raw === "string" && RATIO_PATTERN.test(raw)) {
      return normalizeRatioText(raw)
    }
    if (
      typeof raw === "string" &&
      (normalizedKey.includes("aspect") || normalizedKey.includes("ratio") || normalizedKey.includes("比例"))
    ) {
      return normalizeRatioText(raw)
    }
    const nested = findAspectRatioText(raw)
    if (nested) return nested
  }
  return ""
}

export function findSizeText(value: unknown): string {
  if (!value || typeof value !== "object") return ""
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findSizeText(item)
      if (found) return found
    }
    return ""
  }
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    const normalizedKey = key.toLowerCase()
    if (typeof raw === "string" && SIZE_PATTERN.test(raw)) {
      return raw
    }
    if (typeof raw === "string" && (normalizedKey.includes("size") || normalizedKey.includes("resolution"))) {
      return raw
    }
    const nested = findSizeText(raw)
    if (nested) return nested
  }
  return ""
}

export function parseAspectRatio(value: string): number {
  const match = normalizeRatioText(value).match(/(\d+(?:\.\d+)?)\s*[:/]\s*(\d+(?:\.\d+)?)/)
  if (!match) return 0
  const width = Number(match[1])
  const height = Number(match[2])
  return width > 0 && height > 0 ? width / height : 0
}

export function parseSizeRatio(value: string): number {
  const match = value.match(/(\d{2,5})\s*[x×*＊]\s*(\d{2,5})/i)
  if (!match) return 0
  const width = Number(match[1])
  const height = Number(match[2])
  return width > 0 && height > 0 ? width / height : 0
}

export function inferTaskAspectRatio(task: Pick<TaskDetail, "params" | "outputModality" | "toolType">): number {
  const params = task.params || {}
  const ratio = parseAspectRatio(findAspectRatioText(params))
  if (ratio > 0) return clampAspectRatio(ratio)
  const sizeRatio = parseSizeRatio(findSizeText(params))
  if (sizeRatio > 0) return clampAspectRatio(sizeRatio)

  const modality = `${task.outputModality || ""} ${task.toolType || ""}`.toLowerCase()
  if (modality.includes("video") || modality.includes("视频")) return 16 / 9
  if (modality.includes("audio") || modality.includes("音频")) return 16 / 9
  if (modality.includes("image") || modality.includes("图")) return 1
  return 4 / 3
}

export function clampAspectRatio(ratio: number): number {
  if (!Number.isFinite(ratio) || ratio <= 0) return 1
  return Math.min(Math.max(ratio, 0.45), 2.35)
}
