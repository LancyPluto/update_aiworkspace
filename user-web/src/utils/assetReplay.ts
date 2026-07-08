import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"

export const DASHBOARD_PENDING_ASSET_KEY = "dashboard_pending_asset"
export const CREATE_PENDING_ASSET_KEY = DASHBOARD_PENDING_ASSET_KEY

export function storeDashboardPendingAsset(asset: AssetPreviewItem) {
  window.sessionStorage.setItem(DASHBOARD_PENDING_ASSET_KEY, JSON.stringify(asset))
}

export function consumeDashboardPendingAsset(): AssetPreviewItem | null {
  try {
    const raw = window.sessionStorage.getItem(DASHBOARD_PENDING_ASSET_KEY)
    if (!raw) return null
    window.sessionStorage.removeItem(DASHBOARD_PENDING_ASSET_KEY)
    const parsed = JSON.parse(raw) as AssetPreviewItem
    return parsed?.url || parsed?.rawText || parsed?.prompt ? parsed : null
  } catch {
    return null
  }
}

export const storeCreatePendingAsset = storeDashboardPendingAsset
export const consumeCreatePendingAsset = consumeDashboardPendingAsset

export function dashboardReplayUrl(
  tool: Pick<AssetPreviewRecommendation, "toolCode" | "outputModality"> | string | null | undefined,
  options: { modality?: string | null; sourcePost?: number | string | null } = {},
) {
  const query = new URLSearchParams()
  const toolCode = typeof tool === "string" ? tool : tool?.toolCode
  const modality = options.modality || (typeof tool === "string" ? undefined : tool?.outputModality) || "IMAGE"
  query.set("modality", modality)
  if (toolCode) query.set("tool", toolCode)
  if (options.sourcePost) query.set("sourcePost", String(options.sourcePost))
  return `/dashboard?${query.toString()}`
}

export const createReplayUrl = dashboardReplayUrl

export function openDashboardWithAsset(
  asset: AssetPreviewItem,
  tool: Pick<AssetPreviewRecommendation, "toolCode" | "outputModality"> | string | null | undefined,
  options: { modality?: string | null; sourcePost?: number | string | null } = {},
) {
  const sourcePost = options.sourcePost ?? asset.sourcePostId ?? asset.communityPostId
  storeDashboardPendingAsset(sourcePost ? { ...asset, sourcePostId: Number(sourcePost) } : asset)
  window.location.href = dashboardReplayUrl(tool, { ...options, sourcePost })
}

export const openCreateWithAsset = openDashboardWithAsset

export function openCreateWithAssetRecommendation(
  asset: AssetPreviewItem,
  tool: Pick<AssetPreviewRecommendation, "toolCode" | "outputModality"> | string | null | undefined,
  options: { modality?: string | null; sourcePost?: number | string | null } = {},
) {
  openDashboardWithAsset(asset, tool, options)
}
