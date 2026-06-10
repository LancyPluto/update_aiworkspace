export type ProfileThemeId =
  | "midnight-violet"
  | "california-sun"
  | "geek-neon"
  | "ocean-code"
  | "aurora-search"
  | "studio-creative"
  | "monochrome"
  | "ember-warm"

interface ProfileTheme {
  id: ProfileThemeId
  accent: string
  accentLight: string
  accentDark: string
  accentSoft: string
  accentGlow: string
  mesh1: string
  mesh2: string
  mesh3: string
}

export const PROFILE_AMBIENT_THEME_KEY = "ai_tool_market_profile_ambient_theme"

const DEFAULT_PROFILE_THEME_ID: ProfileThemeId = "midnight-violet"

export const PROFILE_AMBIENT_THEMES: ProfileTheme[] = [
  {
    id: "midnight-violet",
    accent: "rgb(176 92 255)",
    accentLight: "rgb(205 132 255)",
    accentDark: "rgb(115 72 255)",
    accentSoft: "rgb(176 92 255 / 0.14)",
    accentGlow: "rgb(176 92 255 / 0.22)",
    mesh1: "rgb(176 92 255 / 0.095)",
    mesh2: "rgb(34 211 238 / 0.045)",
    mesh3: "rgb(255 255 255 / 0.018)",
  },
  {
    id: "california-sun",
    accent: "rgb(251 146 60)",
    accentLight: "rgb(253 186 116)",
    accentDark: "rgb(234 88 12)",
    accentSoft: "rgb(251 146 60 / 0.14)",
    accentGlow: "rgb(251 146 60 / 0.22)",
    mesh1: "rgb(251 146 60 / 0.10)",
    mesh2: "rgb(250 204 21 / 0.06)",
    mesh3: "rgb(255 255 255 / 0.016)",
  },
  {
    id: "geek-neon",
    accent: "rgb(74 222 128)",
    accentLight: "rgb(134 239 172)",
    accentDark: "rgb(22 163 74)",
    accentSoft: "rgb(74 222 128 / 0.14)",
    accentGlow: "rgb(74 222 128 / 0.22)",
    mesh1: "rgb(74 222 128 / 0.08)",
    mesh2: "rgb(34 197 94 / 0.05)",
    mesh3: "rgb(255 255 255 / 0.014)",
  },
  {
    id: "ocean-code",
    accent: "rgb(96 165 250)",
    accentLight: "rgb(147 197 253)",
    accentDark: "rgb(37 99 235)",
    accentSoft: "rgb(96 165 250 / 0.14)",
    accentGlow: "rgb(96 165 250 / 0.22)",
    mesh1: "rgb(96 165 250 / 0.09)",
    mesh2: "rgb(34 211 238 / 0.05)",
    mesh3: "rgb(255 255 255 / 0.016)",
  },
  {
    id: "aurora-search",
    accent: "rgb(34 211 238)",
    accentLight: "rgb(103 232 249)",
    accentDark: "rgb(8 145 178)",
    accentSoft: "rgb(34 211 238 / 0.14)",
    accentGlow: "rgb(34 211 238 / 0.22)",
    mesh1: "rgb(34 211 238 / 0.09)",
    mesh2: "rgb(129 140 248 / 0.06)",
    mesh3: "rgb(255 255 255 / 0.016)",
  },
  {
    id: "studio-creative",
    accent: "rgb(217 70 239)",
    accentLight: "rgb(232 121 249)",
    accentDark: "rgb(168 85 247)",
    accentSoft: "rgb(217 70 239 / 0.14)",
    accentGlow: "rgb(217 70 239 / 0.22)",
    mesh1: "rgb(217 70 239 / 0.09)",
    mesh2: "rgb(168 85 247 / 0.06)",
    mesh3: "rgb(255 255 255 / 0.016)",
  },
  {
    id: "monochrome",
    accent: "rgb(161 161 170)",
    accentLight: "rgb(212 212 216)",
    accentDark: "rgb(82 82 91)",
    accentSoft: "rgb(161 161 170 / 0.12)",
    accentGlow: "rgb(161 161 170 / 0.18)",
    mesh1: "rgb(255 255 255 / 0.04)",
    mesh2: "rgb(161 161 170 / 0.04)",
    mesh3: "rgb(255 255 255 / 0.012)",
  },
  {
    id: "ember-warm",
    accent: "rgb(245 158 11)",
    accentLight: "rgb(251 191 36)",
    accentDark: "rgb(217 119 6)",
    accentSoft: "rgb(245 158 11 / 0.14)",
    accentGlow: "rgb(245 158 11 / 0.22)",
    mesh1: "rgb(245 158 11 / 0.08)",
    mesh2: "rgb(239 68 68 / 0.05)",
    mesh3: "rgb(255 255 255 / 0.014)",
  },
]

export function getStoredProfileTheme(): ProfileThemeId {
  if (typeof window === "undefined") return DEFAULT_PROFILE_THEME_ID
  const stored = localStorage.getItem(PROFILE_AMBIENT_THEME_KEY)
  return PROFILE_AMBIENT_THEMES.some((theme) => theme.id === stored)
    ? (stored as ProfileThemeId)
    : DEFAULT_PROFILE_THEME_ID
}

export function storeProfileTheme(id: ProfileThemeId) {
  if (typeof window === "undefined") return
  localStorage.setItem(PROFILE_AMBIENT_THEME_KEY, id)
}

export function hashUserIdToTheme(userId: string | number): ProfileThemeId {
  const id = String(userId)
  let hash = 0
  for (let i = 0; i < id.length; i++) {
    hash = (hash * 31 + id.charCodeAt(i)) >>> 0
  }
  return PROFILE_AMBIENT_THEMES[hash % PROFILE_AMBIENT_THEMES.length]?.id ?? DEFAULT_PROFILE_THEME_ID
}

export function resolveProfileThemeId(
  userId: string | number,
  viewerId?: string | number | null,
): ProfileThemeId {
  if (viewerId != null && String(viewerId) === String(userId)) {
    return getStoredProfileTheme()
  }
  return hashUserIdToTheme(userId)
}

function getProfileTheme(id?: ProfileThemeId): ProfileTheme {
  const themeId = id ?? getStoredProfileTheme()
  return PROFILE_AMBIENT_THEMES.find((theme) => theme.id === themeId) ?? PROFILE_AMBIENT_THEMES[0]
}

export function applyProfileThemeToElement(
  el: HTMLElement,
  themeId?: ProfileThemeId,
) {
  const theme = getProfileTheme(themeId)
  el.dataset.profileTheme = theme.id
  el.style.setProperty("--profile-accent", theme.accent)
  el.style.setProperty("--profile-accent-light", theme.accentLight)
  el.style.setProperty("--profile-accent-dark", theme.accentDark)
  el.style.setProperty("--profile-accent-soft", theme.accentSoft)
  el.style.setProperty("--profile-accent-glow", theme.accentGlow)
  el.style.setProperty("--profile-mesh-1", theme.mesh1)
  el.style.setProperty("--profile-mesh-2", theme.mesh2)
  el.style.setProperty("--profile-mesh-3", theme.mesh3)
  el.style.setProperty("--profile-glass-bg", "rgb(255 255 255 / 0.06)")
  el.style.setProperty(
    "--profile-card-hover-shadow",
    `0 20px 50px ${theme.accentGlow}, 0 8px 24px rgb(0 0 0 / 0.35)`,
  )
  el.style.setProperty(
    "--profile-send-gradient",
    `linear-gradient(135deg, ${theme.accentLight}, ${theme.accent} 48%, ${theme.accentDark})`,
  )
}

export function applyProfileTheme(
  rootSelector = ".public-profile-page",
  themeId?: ProfileThemeId,
) {
  if (typeof document === "undefined") return
  const el = document.querySelector(rootSelector) as HTMLElement | null
  if (el) applyProfileThemeToElement(el, themeId)
}
