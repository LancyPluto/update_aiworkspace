interface PublicUserIdentity {
  id: number
  publicCode?: string | null
  nickname?: string | null
  username?: string | null
}

function isPhoneLike(value: string) {
  const normalized = value.trim().replace(/[\s-]/g, "").replace(/^(?:\+86|86)/, "")
  return /^1\d{10}$/.test(normalized)
}

function isMismatchedGeneratedName(value: string, publicCode?: string | null) {
  return /^用户\d+$/.test(value)
    && /^[1-9]\d{4}$/.test(publicCode || "")
    && value !== `用户${publicCode}`
}

export function adminUserDisplayName(user: PublicUserIdentity) {
  const fallback = user.publicCode ? `用户${user.publicCode}` : "用户"
  const nickname = user.nickname?.trim()
  if (nickname
    && !isPhoneLike(nickname)
    && nickname !== `用户${user.id}`
    && !isMismatchedGeneratedName(nickname, user.publicCode)) return nickname

  const username = user.username?.trim()
  if (username && !isPhoneLike(username) && !isMismatchedGeneratedName(username, user.publicCode)) return username
  return fallback
}
