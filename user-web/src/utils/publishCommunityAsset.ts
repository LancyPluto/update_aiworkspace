import { publishCommunityPost } from "@/api/communityApi"
import type { CommunityPost } from "@/api/types"
import type { AssetPreviewItem } from "@/types/assetPreview"

export type CommunityPublishPayload = {
  title?: string
  promptVisible?: boolean
}

export async function publishAssetToCommunity(
  asset: AssetPreviewItem,
  options: {
    token: string
    payload?: CommunityPublishPayload
    defaultPromptVisible?: boolean
  },
): Promise<CommunityPost> {
  if (!asset.taskId) {
    throw new Error("缺少任务 ID，无法发布")
  }

  const customTitle = options.payload?.title?.trim()
  // Prefer the user's current "prompt public by default" setting over the asset's
  // historical value so that re-publishing an existing post also respects the
  // latest preference.  If neither is true, fall back to false.
  const promptVisible =
    options.payload?.promptVisible ??
    (options.defaultPromptVisible || asset.promptVisible || false)

  return publishCommunityPost(
    {
      taskId: asset.taskId,
      title: customTitle || undefined,
      description: asset.subtitle || null,
      promptVisible,
    },
    { token: options.token },
  )
}
