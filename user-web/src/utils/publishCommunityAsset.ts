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
  const promptVisible =
    options.payload?.promptVisible ??
    asset.promptVisible ??
    options.defaultPromptVisible ??
    false

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
