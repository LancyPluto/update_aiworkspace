import { apiRequest } from "./client"
import type { CommunityCollection, CommunityCreator, CommunityPost, PageResult, PublicUserProfile } from "./types"

export interface PublishCommunityPostRequest {
  taskId: number
  title?: string
  description?: string | null
  promptVisible?: boolean
  topic?: string | null
  tags?: string[]
}

export interface UpdateCommunityPostRequest {
  title?: string
  description?: string | null
  promptVisible?: boolean
  topic?: string | null
  tags?: string[]
}

export interface CommunityDiscoverQuery {
  pageNo?: number
  pageSize?: number
  modality?: string
  tag?: string
  topic?: string
  sort?: "LATEST" | "POPULAR" | "FAVORITES" | "SAME_STYLE" | "VIEWS" | string
  featured?: boolean
  keyword?: string
  toolCode?: string
}

export function fetchCommunityPosts(options?: { token?: string | null; query?: CommunityDiscoverQuery }) {
  return apiRequest<PageResult<CommunityPost>>("GET", "/api/v1/community/posts", {
    token: options?.token,
    query: options?.query,
  })
}

export function searchCommunityPosts(options?: { token?: string | null; query?: CommunityDiscoverQuery }) {
  return apiRequest<PageResult<CommunityPost>>("GET", "/api/v1/community/search", {
    token: options?.token,
    query: options?.query,
  })
}

export function fetchCommunityTopicPosts(
  topic: string,
  options?: { token?: string | null; query?: Pick<CommunityDiscoverQuery, "pageNo" | "pageSize" | "modality" | "sort"> },
) {
  return apiRequest<PageResult<CommunityPost>>("GET", `/api/v1/community/topics/${encodeURIComponent(topic)}`, {
    token: options?.token,
    query: options?.query,
  })
}

export function fetchCommunityCreator(userId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityCreator>("GET", `/api/v1/community/creators/${encodeURIComponent(String(userId))}`, {
    token: options?.token,
  })
}

export function trackCommunityEvent(
  body: {
    postId?: number
    eventType: string
    source?: string
    toolCode?: string | null
    taskId?: number
    credits?: number
  },
  options?: { token?: string | null },
) {
  return apiRequest<void>("POST", "/api/v1/community/events", {
    token: options?.token,
    body,
  })
}

export function fetchCommunityCollections(options?: { token?: string | null }) {
  return apiRequest<CommunityCollection[]>("GET", "/api/v1/community/collections", {
    token: options?.token,
  })
}

export function createCommunityCollection(name: string, options?: { token?: string | null }) {
  return apiRequest<CommunityCollection>("POST", "/api/v1/community/collections", {
    token: options?.token,
    body: { name },
  })
}

export function renameCommunityCollection(collectionId: number | string, name: string, options?: { token?: string | null }) {
  return apiRequest<CommunityCollection>("PATCH", `/api/v1/community/collections/${encodeURIComponent(String(collectionId))}`, {
    token: options?.token,
    body: { name },
  })
}

export function deleteCommunityCollection(collectionId: number | string, options?: { token?: string | null }) {
  return apiRequest<void>("DELETE", `/api/v1/community/collections/${encodeURIComponent(String(collectionId))}`, {
    token: options?.token,
  })
}

export function addCommunityCollectionItem(
  collectionId: number | string,
  postId: number | string,
  options?: { token?: string | null },
) {
  return apiRequest<CommunityCollection>("POST", `/api/v1/community/collections/${encodeURIComponent(String(collectionId))}/items`, {
    token: options?.token,
    body: { postId: Number(postId) },
  })
}

export function removeCommunityCollectionItem(
  collectionId: number | string,
  postId: number | string,
  options?: { token?: string | null },
) {
  return apiRequest<void>(
    "DELETE",
    `/api/v1/community/collections/${encodeURIComponent(String(collectionId))}/items/${encodeURIComponent(String(postId))}`,
    {
      token: options?.token,
    },
  )
}

export function fetchPublicUser(userId: number | string, options?: { token?: string | null }) {
  return apiRequest<PublicUserProfile>("GET", `/api/v1/community/users/${encodeURIComponent(String(userId))}`, {
    token: options?.token,
  })
}

export function fetchPublicUserPosts(
  userId: number | string,
  options?: { token?: string | null; query?: { pageNo?: number; pageSize?: number; modality?: string } },
) {
  return apiRequest<PageResult<CommunityPost>>(
    "GET",
    `/api/v1/community/users/${encodeURIComponent(String(userId))}/posts`,
    {
      token: options?.token,
      query: options?.query,
    },
  )
}

export function fetchCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("GET", `/api/v1/community/posts/${encodeURIComponent(String(postId))}`, {
    token: options?.token,
  })
}

export function publishCommunityPost(body: PublishCommunityPostRequest, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("POST", "/api/v1/community/posts", {
    token: options?.token,
    body,
  })
}

export function updateCommunityPost(
  postId: number | string,
  body: UpdateCommunityPostRequest,
  options?: { token?: string | null },
) {
  return apiRequest<CommunityPost>("PATCH", `/api/v1/community/posts/${encodeURIComponent(String(postId))}`, {
    token: options?.token,
    body,
  })
}

export function unpublishCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<void>("DELETE", `/api/v1/community/posts/${encodeURIComponent(String(postId))}`, {
    token: options?.token,
  })
}

export function likeCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("POST", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/like`, {
    token: options?.token,
  })
}

export function unlikeCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("DELETE", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/like`, {
    token: options?.token,
  })
}

export function favoriteCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("POST", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/favorite`, {
    token: options?.token,
  })
}

export function unfavoriteCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("DELETE", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/favorite`, {
    token: options?.token,
  })
}

export function markCommunityPostSameStyle(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("POST", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/same-style`, {
    token: options?.token,
  })
}
