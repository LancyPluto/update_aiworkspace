import { ApiBusinessError, apiRequest } from "./client"
import { fetchTaskById } from "./taskApi"
import type { CommunityCollection, CommunityCreator, CommunityPost, CommunityTopic, PageResult, PublicUserProfile } from "./types"
import {
  collectMissingAuthorUserIds,
  getCachedCommunityAuthorProfile,
  mergeCommunityPostAuthor,
  normalizeCommunityPost,
  normalizeCommunityPosts,
  rememberCommunityAuthorProfile,
} from "@/utils/communityPostNormalize"

async function enrichCommunityPostsWithAuthors(
  posts: CommunityPost[],
  options?: { token?: string | null },
): Promise<CommunityPost[]> {
  const missingUserIds = collectMissingAuthorUserIds(posts)
  if (!missingUserIds.length) return posts

  await Promise.all(
    missingUserIds.map(async (userId) => {
      try {
        const profile = await fetchPublicUser(userId, options)
        rememberCommunityAuthorProfile(profile)
      } catch {
        // Cards can still render with the post-level fallback author label.
      }
    }),
  )

  return posts.map((post) => mergeCommunityPostAuthor(post, getCachedCommunityAuthorProfile(post.userId)))
}

function normalizeCommunityPage(page: PageResult<CommunityPost>, options?: { token?: string | null }) {
  const normalized = {
    ...page,
    list: normalizeCommunityPosts(page.list as CommunityPost[]),
  }
  return enrichCommunityPostsWithAuthors(normalized.list, options).then((list) => ({
    ...normalized,
    list,
  }))
}

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
  sort?: "LATEST" | "POPULAR" | "FAVORITES" | "SAME_STYLE" | "VIEWS" | "QUALITY" | string
  featured?: boolean
  keyword?: string
  toolCode?: string
}

/** Detects older backend deployments that do not expose newer community endpoints yet. */
export function isCommunityEndpointMissing(error: unknown, pathFragment: string): boolean {
  if (!(error instanceof ApiBusinessError)) return false
  const message = error.message.toLowerCase()
  return error.message.includes(pathFragment) && (message.includes("not found") || error.message.includes("不存在"))
}

function discoverQueryFromSearch(query?: CommunityDiscoverQuery): CommunityDiscoverQuery | undefined {
  if (!query) return undefined
  const tag = query.tag?.trim() || query.keyword?.trim() || undefined
  return {
    pageNo: query.pageNo,
    pageSize: query.pageSize,
    modality: query.modality,
    sort: query.sort,
    featured: query.featured,
    tag,
    topic: query.topic,
  }
}

export function fetchCommunityPosts(options?: { token?: string | null; query?: CommunityDiscoverQuery }) {
  return apiRequest<PageResult<CommunityPost>>("GET", "/api/v1/community/posts", {
    token: options?.token,
    query: options?.query,
  }).then((page) => normalizeCommunityPage(page, options))
}

export function searchCommunityPosts(options?: { token?: string | null; query?: CommunityDiscoverQuery }) {
  return apiRequest<PageResult<CommunityPost>>("GET", "/api/v1/community/search", {
    token: options?.token,
    query: options?.query,
  })
    .then((page) => normalizeCommunityPage(page, options))
    .catch((error) => {
      if (!isCommunityEndpointMissing(error, "community/search")) throw error
      return fetchCommunityPosts({
        token: options?.token,
        query: discoverQueryFromSearch(options?.query),
      })
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

export async function fetchCommunityTopics(options?: { token?: string | null; limit?: number }): Promise<CommunityTopic[]> {
  try {
    return await apiRequest<CommunityTopic[]>("GET", "/api/v1/community/topics", {
      token: options?.token,
      query: { limit: options?.limit },
    })
  } catch (error) {
    if (isCommunityEndpointMissing(error, "community/topics")) return []
    throw error
  }
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

export interface CommunityCollectionsResult {
  collections: CommunityCollection[]
  supported: boolean
}

export async function fetchCommunityCollections(options?: { token?: string | null }): Promise<CommunityCollectionsResult> {
  try {
    const collections = await apiRequest<CommunityCollection[]>("GET", "/api/v1/community/collections", {
      token: options?.token,
      skipAuthRedirect: true,
    })
    return { collections, supported: true }
  } catch (error) {
    if (isCommunityEndpointMissing(error, "community/collections")) {
      return { collections: [], supported: false }
    }
    if (error instanceof ApiBusinessError && error.code === "UNAUTHORIZED") {
      return { collections: [], supported: false }
    }
    throw error
  }
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
  }).then((profile) => {
    rememberCommunityAuthorProfile(profile)
    return profile
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
  ).then((page) => normalizeCommunityPage(page, options))
}

export function fetchCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("GET", `/api/v1/community/posts/${encodeURIComponent(String(postId))}`, {
    token: options?.token,
  })
    .then((post) => normalizeCommunityPost(post as CommunityPost))
    .then((post) => enrichCommunityPostsWithAuthors([post], options).then((list) => list[0] ?? post))
}

export function publishCommunityPost(body: PublishCommunityPostRequest, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("POST", "/api/v1/community/posts", {
    token: options?.token,
    body,
  }).then((post) => normalizeCommunityPost(post as CommunityPost))
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

export async function resolvePublishedCommunityPostId(
  taskId: number,
  options?: { token?: string | null; userId?: number | null; hint?: number | null },
): Promise<number | null> {
  if (options?.hint) return options.hint

  if (options?.token) {
    try {
      const detail = await fetchTaskById(taskId, { token: options.token })
      if (detail.communityPostId) return detail.communityPostId
    } catch {
      // Fall back to the user's public portfolio when task detail is unavailable.
    }
  }

  if (options?.token && options.userId) {
    try {
      const page = await fetchPublicUserPosts(options.userId, {
        token: options.token,
        query: { pageNo: 1, pageSize: 200 },
      })
      const match = page.list.find((post) => post.taskId === taskId)
      if (match?.id) return match.id
    } catch {
      // ignore
    }
  }

  return null
}

export function likeCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("POST", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/like`, {
    token: options?.token,
  }).then((post) => normalizeCommunityPost(post as CommunityPost))
}

export function unlikeCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("DELETE", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/like`, {
    token: options?.token,
  }).then((post) => normalizeCommunityPost(post as CommunityPost))
}

export function favoriteCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("POST", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/favorite`, {
    token: options?.token,
  }).then((post) => normalizeCommunityPost(post as CommunityPost))
}

export function unfavoriteCommunityPost(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("DELETE", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/favorite`, {
    token: options?.token,
  }).then((post) => normalizeCommunityPost(post as CommunityPost))
}

export function markCommunityPostSameStyle(postId: number | string, options?: { token?: string | null }) {
  return apiRequest<CommunityPost>("POST", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/same-style`, {
    token: options?.token,
  })
}

export function reportCommunityPost(
  postId: number | string,
  body?: { reason?: string },
  options?: { token?: string | null },
) {
  return apiRequest<void>("POST", `/api/v1/community/posts/${encodeURIComponent(String(postId))}/report`, {
    token: options?.token,
    body,
  })
}
