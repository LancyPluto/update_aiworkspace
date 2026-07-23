import type { CommunityPost, PublicUserProfile } from "@/api/types"
import { defaultUserDisplayName, safeDisplayName } from "@/utils/displayName"

type RawCommunityPost = CommunityPost & {
  author_public_code?: string | null
  owned_by_current_user?: boolean
  author_nickname?: string | null
  author_avatar_url?: string | null
  prompt_preview?: string | null
}

const authorProfileCache = new Map<string, PublicUserProfile>()

export function rememberCommunityAuthorProfile(profile: PublicUserProfile) {
  if (profile?.publicCode) authorProfileCache.set(profile.publicCode, profile)
}

export function getCachedCommunityAuthorProfile(publicCode?: string | null) {
  return publicCode ? authorProfileCache.get(publicCode) : undefined
}

export function normalizeCommunityPost(post: RawCommunityPost): CommunityPost {
  const liked = post.liked ?? (post as { is_liked?: boolean }).is_liked
  const favorited = post.favorited ?? (post as { is_favorited?: boolean }).is_favorited
  return {
    ...post,
    authorPublicCode: post.authorPublicCode ?? post.author_public_code ?? null,
    ownedByCurrentUser: post.ownedByCurrentUser ?? post.owned_by_current_user ?? false,
    authorNickname:
      safeDisplayName(
        post.authorNickname ?? post.author_nickname,
        post.authorPublicCode ?? post.author_public_code,
      ) || null,
    authorAvatarUrl: post.authorAvatarUrl ?? post.author_avatar_url ?? null,
    promptPreview: post.promptPreview ?? post.prompt_preview ?? null,
    liked: liked === true,
    favorited: favorited === true,
  }
}

export function normalizeCommunityPosts(posts: RawCommunityPost[]): CommunityPost[] {
  return posts.map(normalizeCommunityPost)
}

export function resolveCommunityAuthorName(post: Pick<CommunityPost, "authorNickname" | "authorPublicCode">) {
  const cached = getCachedCommunityAuthorProfile(post.authorPublicCode)
  const nickname =
    safeDisplayName(post.authorNickname, post.authorPublicCode) ||
    safeDisplayName(cached?.nickname, post.authorPublicCode)
  if (nickname) return nickname
  return defaultUserDisplayName(post.authorPublicCode)
}

export function resolveCommunityAuthorAvatar(post: Pick<CommunityPost, "authorAvatarUrl" | "authorPublicCode">) {
  return post.authorAvatarUrl ?? getCachedCommunityAuthorProfile(post.authorPublicCode)?.avatarUrl ?? null
}

export function resolveCommunityPrompt(post: Pick<CommunityPost, "promptPreview" | "promptVisible" | "prompt">) {
  if (!post.promptVisible) return ""
  const preview = post.promptPreview?.trim()
  if (preview) return preview
  if (post.prompt?.trim()) return post.prompt.trim()
  return ""
}

export function mergeCommunityPostAuthor(
  post: CommunityPost,
  profile: PublicUserProfile | null | undefined,
): CommunityPost {
  if (!profile) return post
  rememberCommunityAuthorProfile(profile)
  const existingAuthorName = safeDisplayName(post.authorNickname, post.authorPublicCode)
  if (existingAuthorName) return { ...post, authorNickname: existingAuthorName }
  return {
    ...post,
    authorNickname:
      safeDisplayName(profile.nickname, post.authorPublicCode) ||
      defaultUserDisplayName(post.authorPublicCode),
    authorAvatarUrl: post.authorAvatarUrl ?? profile.avatarUrl ?? null,
  }
}

export function collectMissingAuthorPublicCodes(posts: CommunityPost[]) {
  return [
    ...new Set(
      posts
        .filter((post) => !post.authorNickname?.trim() && post.authorPublicCode)
        .map((post) => post.authorPublicCode as string),
    ),
  ]
}
