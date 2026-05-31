import type { CommunityPost, PublicUserProfile } from "@/api/types"

type RawCommunityPost = CommunityPost & {
  author_nickname?: string | null
  author_avatar_url?: string | null
  prompt_preview?: string | null
}

const authorProfileCache = new Map<number, PublicUserProfile>()

export function rememberCommunityAuthorProfile(profile: PublicUserProfile) {
  if (profile?.id) authorProfileCache.set(profile.id, profile)
}

export function getCachedCommunityAuthorProfile(userId: number) {
  return authorProfileCache.get(userId)
}

export function normalizeCommunityPost(post: RawCommunityPost): CommunityPost {
  return {
    ...post,
    authorNickname: post.authorNickname ?? post.author_nickname ?? null,
    authorAvatarUrl: post.authorAvatarUrl ?? post.author_avatar_url ?? null,
    promptPreview: post.promptPreview ?? post.prompt_preview ?? null,
  }
}

export function normalizeCommunityPosts(posts: RawCommunityPost[]): CommunityPost[] {
  return posts.map(normalizeCommunityPost)
}

export function resolveCommunityAuthorName(post: Pick<CommunityPost, "authorNickname" | "userId">) {
  const cached = authorProfileCache.get(post.userId)
  const nickname = post.authorNickname?.trim() || cached?.nickname?.trim() || cached?.username?.trim()
  if (nickname) return nickname
  if (post.userId) return `用户 ${post.userId}`
  return ""
}

export function resolveCommunityAuthorAvatar(post: Pick<CommunityPost, "authorAvatarUrl" | "userId">) {
  return post.authorAvatarUrl ?? authorProfileCache.get(post.userId)?.avatarUrl ?? null
}

export function resolveCommunityPrompt(post: Pick<CommunityPost, "promptPreview" | "promptVisible" | "prompt">) {
  const preview = post.promptPreview?.trim()
  if (preview) return preview
  if (post.promptVisible && post.prompt?.trim()) return post.prompt.trim()
  return ""
}

export function mergeCommunityPostAuthor(
  post: CommunityPost,
  profile: PublicUserProfile | null | undefined,
): CommunityPost {
  if (!profile) return post
  rememberCommunityAuthorProfile(profile)
  if (post.authorNickname?.trim()) return post
  return {
    ...post,
    authorNickname: profile.nickname?.trim() || profile.username?.trim() || `用户 ${post.userId}`,
    authorAvatarUrl: post.authorAvatarUrl ?? profile.avatarUrl ?? null,
  }
}

export function collectMissingAuthorUserIds(posts: CommunityPost[]) {
  return [
    ...new Set(
      posts
        .filter((post) => !post.authorNickname?.trim() && post.userId)
        .map((post) => post.userId),
    ),
  ]
}
