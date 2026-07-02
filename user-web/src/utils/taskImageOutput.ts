import type { TaskDetail } from "@/api/types"

export type ImageOutputLayout = "parallel" | "serial"

const IMAGE_COUNT_KEYS = [
  "count",
  "outputCount",
  "imageCount",
  "numImages",
  "batchSize",
  "batch_size",
  "n",
] as const

function isImageTask(task: Pick<TaskDetail, "outputModality" | "toolType">): boolean {
  const modality = `${task.outputModality || ""} ${task.toolType || ""}`.toLowerCase()
  return modality.includes("image") || modality.includes("图")
}

export function resolveTaskImageOutputCount(
  task: Pick<TaskDetail, "params" | "outputModality" | "toolType">,
): number {
  if (!isImageTask(task)) return 1
  const params = task.params || {}
  for (const key of IMAGE_COUNT_KEYS) {
    const value = params[key]
    const numeric = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN
    if (Number.isInteger(numeric) && numeric > 1) return Math.min(numeric, 4)
  }
  return 1
}

export function inferTaskImageOutputLayout(
  task: Pick<TaskDetail, "params" | "outputModality" | "toolType">,
): ImageOutputLayout {
  if (!isImageTask(task)) return "parallel"
  const params = task.params || {}
  const sequential = String(
    params.sequentialImageGeneration ?? params.sequential_image_generation ?? "",
  )
    .trim()
    .toLowerCase()
  if (sequential === "auto" || sequential === "enabled" || sequential === "on" || sequential === "true") {
    return "serial"
  }
  return "parallel"
}

export function inferSerialActiveSlot(percent: number, count: number): number {
  if (count <= 1) return 0
  const safePercent = Math.max(0, Math.min(100, percent))
  return Math.min(count - 1, Math.floor((safePercent / 100) * count))
}

export function inferParallelSlotPercent(basePercent: number, slotIndex: number, totalSlots: number): number {
  if (totalSlots <= 1) return Math.max(0, Math.min(100, Math.round(basePercent)))
  if (basePercent >= 100) return 100

  const spread = Math.max(8, Math.floor(32 / totalSlots))
  const leadBoost = Math.floor(spread * 0.8)
  const staggered = basePercent + leadBoost - slotIndex * spread
  const converged = basePercent >= 85 ? basePercent : staggered
  return Math.max(0, Math.min(99, Math.round(converged)))
}

export interface TaskImageOutputPlan {
  count: number
  layout: ImageOutputLayout
  showMultiPreview: boolean
}

export function buildTaskImageOutputPlan(
  task: Pick<TaskDetail, "params" | "outputModality" | "toolType">,
): TaskImageOutputPlan {
  const count = resolveTaskImageOutputCount(task)
  const layout = inferTaskImageOutputLayout(task)
  return {
    count,
    layout,
    showMultiPreview: count > 1 && layout === "parallel",
  }
}
