import type { AssetPreviewItem } from "@/types/assetPreview"
import type { RouteLocationNormalizedLoaded } from "vue-router"

export interface DashboardAttributionContext {
  sourcePostId?: number
}

export function parseSourcePostId(value: unknown): number | undefined {
  const raw = Array.isArray(value) ? value[0] : value
  if (raw == null || raw === "") return undefined
  const id = Number(raw)
  return Number.isFinite(id) && id > 0 ? id : undefined
}

export function dashboardAttributionFromRoute(route: Pick<RouteLocationNormalizedLoaded, "query">): DashboardAttributionContext {
  return {
    sourcePostId: parseSourcePostId(route.query.sourcePost),
  }
}

export function mergePendingAssetAttribution(
  current: DashboardAttributionContext,
  asset: AssetPreviewItem | null,
): DashboardAttributionContext {
  const sourcePostId = asset?.sourcePostId ?? asset?.communityPostId ?? current.sourcePostId
  return {
    ...current,
    sourcePostId: sourcePostId ? Number(sourcePostId) : undefined,
  }
}
