export function isPhoneLike(value?: string | null): boolean {
  if (!value) return false
  let normalized = value.trim().replace(/[\s-]/g, "")
  if (normalized.startsWith("+86")) {
    normalized = normalized.slice(3)
  } else if (normalized.startsWith("86") && normalized.length === 13) {
    normalized = normalized.slice(2)
  }
  return /^1\d{10}$/.test(normalized)
}

export function safeDisplayName(value?: string | null): string {
  const normalized = value?.trim()
  if (!normalized || isPhoneLike(normalized)) return ""
  return normalized
}

export function defaultUserDisplayName(userId?: number | string | null): string {
  const value = userId == null ? "" : String(userId).trim()
  return value ? `用户${value}` : "用户"
}
