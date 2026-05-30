import { apiRequest } from "./client"
import type { CommunityPost, PageResult, PublicUserProfile } from "./types"

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
}

export function fetchCommunityPosts(options?: { token?: string | null; query?: CommunityDiscoverQuery }) {
  return apiRequest<PageResult<CommunityPost>>("GET", "/api/v1/community/posts", {
    token: options?.token,
    query: options?.query,
  })
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
