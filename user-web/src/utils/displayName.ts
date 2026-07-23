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

export function safeDisplayName(
  value?: string | null,
  publicCode?: number | string | null,
): string {
  const normalized = value?.trim()
  if (!normalized || isPhoneLike(normalized)) return ""
  const normalizedPublicCode = publicCode == null ? "" : String(publicCode).trim()
  if (
    /^用户\d+$/.test(normalized) &&
    /^[1-9]\d{4}$/.test(normalizedPublicCode) &&
    normalized !== `用户${normalizedPublicCode}`
  ) {
    return ""
  }
  return normalized
}

export function defaultUserDisplayName(publicCode?: number | string | null): string {
  const value = publicCode == null ? "" : String(publicCode).trim()
  return /^[1-9]\d{4}$/.test(value) ? `用户${value}` : "用户"
}
