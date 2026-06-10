import type { ModelVendorAccountDiscoverModelsResult } from "./api/types"

function countFrom(result: ModelVendorAccountDiscoverModelsResult, keys: string[]) {
  const source = result as Record<string, unknown>
  for (const key of keys) {
    const value = source[key]
    if (typeof value === "number" && Number.isFinite(value)) {
      return Math.max(0, Math.trunc(value))
    }
  }
  return null
}

export function formatModelDiscoveryResult(result: ModelVendorAccountDiscoverModelsResult | null | undefined) {
  if (!result) return "同步完成"

  const imported = countFrom(result, ["importedCount", "imported", "createdCount", "created", "newCount"])
  const updated = countFrom(result, ["updatedCount", "updated"])
  const skipped = countFrom(result, ["skippedCount", "skipped"])
  const discovered = countFrom(result, ["discoveredCount", "discovered", "totalCount", "modelCount"])
  const parts: string[] = []

  if (imported != null) parts.push(`导入 ${imported} 个`)
  if (updated != null) parts.push(`更新 ${updated} 个`)
  if (skipped != null) parts.push(`跳过 ${skipped} 个`)
  if (parts.length === 0 && discovered != null) parts.push(`发现 ${discovered} 个模型`)

  const message = typeof result.message === "string" ? result.message.trim() : ""
  if (message) {
    return parts.length > 0 ? `${parts.join("，")}；${message}` : message
  }
  return parts.length > 0 ? parts.join("，") : "同步完成"
}
