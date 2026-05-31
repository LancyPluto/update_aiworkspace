import {
  AGENT_AMBIENT_THEMES,
  DEFAULT_AGENT_THEME_ID,
  getAgentTheme,
  type AgentAmbientThemeId,
} from "@/utils/agentTheme"

export type ProfileThemeId = AgentAmbientThemeId

export const PROFILE_AMBIENT_THEME_KEY = "ai_tool_market_profile_ambient_theme"
export const PROFILE_AMBIENT_THEMES = AGENT_AMBIENT_THEMES

export function getStoredProfileTheme(): ProfileThemeId {
  if (typeof window === "undefined") return DEFAULT_AGENT_THEME_ID
  const stored = localStorage.getItem(PROFILE_AMBIENT_THEME_KEY)
  return AGENT_AMBIENT_THEMES.some((t) => t.id === stored)
    ? (stored as ProfileThemeId)
    : DEFAULT_AGENT_THEME_ID
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
  return AGENT_AMBIENT_THEMES[hash % AGENT_AMBIENT_THEMES.length]?.id ?? DEFAULT_AGENT_THEME_ID
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

export function applyProfileThemeToElement(
  el: HTMLElement,
  themeId?: ProfileThemeId,
) {
  const theme = getAgentTheme(themeId)
  el.dataset.profileTheme = theme.id
  el.style.setProperty("--profile-accent", theme.accent)
  el.style.setProperty("--profile-accent-light", theme.accentLight)
  el.style.setProperty("--profile-accent-dark", theme.accentDark)
  el.style.setProperty("--profile-accent-soft", theme.accentSoft)
  el.style.setProperty("--profile-accent-glow", theme.accentGlow)
  el.style.setProperty("--profile-mesh-1", theme.mesh1)
  el.style.setProperty("--profile-mesh-2", theme.mesh2)
  el.style.setProperty("--profile-mesh-3", theme.mesh3)
  el.style.setProperty("--profile-glass-bg", `rgb(255 255 255 / 0.06)`)
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
