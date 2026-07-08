import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"

export const CREATE_PENDING_ASSET_KEY = "create_pending_asset"

export function storeCreatePendingAsset(asset: AssetPreviewItem) {
  window.sessionStorage.setItem(CREATE_PENDING_ASSET_KEY, JSON.stringify(asset))
}

export function consumeCreatePendingAsset(): AssetPreviewItem | null {
  try {
    const raw = window.sessionStorage.getItem(CREATE_PENDING_ASSET_KEY)
    if (!raw) return null
    window.sessionStorage.removeItem(CREATE_PENDING_ASSET_KEY)
    const parsed = JSON.parse(raw) as AssetPreviewItem
    return parsed?.url || parsed?.rawText || parsed?.prompt ? parsed : null
  } catch {
    return null
  }
}

export function createReplayUrl(
  tool: Pick<AssetPreviewRecommendation, "toolCode" | "outputModality"> | string | null | undefined,
  options: { modality?: string | null; sourcePost?: number | string | null } = {},
) {
  const query = new URLSearchParams()
  const toolCode = typeof tool === "string" ? tool : tool?.toolCode
  const modality = options.modality || (typeof tool === "string" ? undefined : tool?.outputModality) || "IMAGE"
  query.set("modality", modality)
  if (toolCode) query.set("tool", toolCode)
  if (options.sourcePost) query.set("sourcePost", String(options.sourcePost))
  return `/create?${query.toString()}`
}

export function openCreateWithAsset(
  asset: AssetPreviewItem,
  tool: Pick<AssetPreviewRecommendation, "toolCode" | "outputModality"> | string | null | undefined,
  options: { modality?: string | null; sourcePost?: number | string | null } = {},
) {
  const sourcePost = options.sourcePost ?? asset.sourcePostId ?? asset.communityPostId
  storeCreatePendingAsset(sourcePost ? { ...asset, sourcePostId: Number(sourcePost) } : asset)
  window.location.href = createReplayUrl(tool, { ...options, sourcePost })
}

export function openCreateWithAssetRecommendation(
  asset: AssetPreviewItem,
  tool: Pick<AssetPreviewRecommendation, "toolCode" | "outputModality"> | string | null | undefined,
  options: { modality?: string | null; sourcePost?: number | string | null } = {},
) {
  openCreateWithAsset(asset, tool, options)
}
