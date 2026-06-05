export const COMMUNITY_POST_UNPUBLISHED_EVENT = "community:post-unpublished"

export type CommunityPostUnpublishedDetail = {
  postId?: number
  taskId?: number
}

export function emitCommunityPostUnpublished(detail: CommunityPostUnpublishedDetail) {
  if (typeof window === "undefined") return
  window.dispatchEvent(new CustomEvent(COMMUNITY_POST_UNPUBLISHED_EVENT, { detail }))
}
