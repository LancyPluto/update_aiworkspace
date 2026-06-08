import {
  addCommunityCollectionItem,
  fetchCommunityCollections,
  removeCommunityCollectionItem,
} from "@/api/communityApi"

export const COMMUNITY_POST_UNPUBLISHED_EVENT = "community:post-unpublished"

export type CommunityPostUnpublishedDetail = {
  postId?: number
  taskId?: number
}

let cachedDefaultCollectionId: number | null = null

export function resetDefaultCommunityCollectionCache() {
  cachedDefaultCollectionId = null
}

export function emitCommunityPostUnpublished(detail: CommunityPostUnpublishedDetail) {
  if (typeof window === "undefined") return
  window.dispatchEvent(new CustomEvent(COMMUNITY_POST_UNPUBLISHED_EVENT, { detail }))
}

async function resolveDefaultCollectionId(token: string | null | undefined): Promise<number | null> {
  if (!token) return null
  if (cachedDefaultCollectionId) return cachedDefaultCollectionId
  try {
    const result = await fetchCommunityCollections({ token })
    if (!result.supported || !result.collections.length) return null
    const target = result.collections.find((item) => item.defaultCollection) || result.collections[0]
    cachedDefaultCollectionId = target?.id ?? null
    return cachedDefaultCollectionId
  } catch {
    return null
  }
}

/** 将社区作品收藏状态同步到默认灵感收藏夹（与列表页星标行为一致）。 */
export async function syncFavoriteToInspirationCollection(
  postId: number,
  favorited: boolean,
  options?: { token?: string | null },
) {
  const collectionId = await resolveDefaultCollectionId(options?.token)
  if (!collectionId) return
  if (favorited) {
    await addCommunityCollectionItem(collectionId, postId, options)
  } else {
    await removeCommunityCollectionItem(collectionId, postId, options)
  }
}

export async function preloadDefaultCommunityCollection(token: string | null | undefined) {
  await resolveDefaultCollectionId(token)
}
