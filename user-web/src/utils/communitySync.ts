import {
  addCommunityCollectionItem,
  favoriteCommunityPost,
  fetchCommunityCollections,
  removeCommunityCollectionItem,
  unfavoriteCommunityPost,
} from "@/api/communityApi"
import type { CommunityPost } from "@/api/types"

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

/** 收藏作品并加入指定收藏夹；collectionId 为空时仅收藏作品。 */
export async function favoritePostToCollection(
  postId: number,
  collectionId: number | null,
  options?: { token?: string | null },
): Promise<CommunityPost> {
  const updated = await favoriteCommunityPost(postId, options)
  if (collectionId != null) {
    await addCommunityCollectionItem(collectionId, postId, options)
  }
  return updated
}

/** 取消收藏，并从用户全部收藏夹移除该作品。 */
export async function unfavoritePostFromAllCollections(
  postId: number,
  options?: { token?: string | null },
): Promise<CommunityPost> {
  const updated = await unfavoriteCommunityPost(postId, options)
  try {
    const result = await fetchCommunityCollections(options)
    if (result.supported) {
      await Promise.all(
        result.collections.map((collection) =>
          removeCommunityCollectionItem(collection.id, postId, options).catch(() => undefined),
        ),
      )
    }
  } catch {
    // 取消收藏主状态已成功；清理收藏夹失败时不阻断
  }
  return updated
}

export async function preloadDefaultCommunityCollection(token: string | null | undefined) {
  await resolveDefaultCollectionId(token)
}
