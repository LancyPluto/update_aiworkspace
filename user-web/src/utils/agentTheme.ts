export type AgentAmbientThemeId =
  | "midnight-violet"
  | "california-sun"
  | "geek-neon"
  | "ocean-code"
  | "aurora-search"
  | "studio-creative"
  | "monochrome"
  | "ember-warm"

export interface AgentAmbientTheme {
  id: AgentAmbientThemeId
  label: string
  accent: string
  accentLight: string
  accentDark: string
  accentSoft: string
  accentGlow: string
  mesh1: string
  mesh2: string
  mesh3: string
  composerTint: string
  bubbleUserTint: string
  bubbleAssistantTint: string
  swatch: string
}

export const AGENT_AMBIENT_THEME_KEY = "ai_tool_market_agent_ambient_theme"

export const AGENT_AMBIENT_THEMES: AgentAmbientTheme[] = [
  {
    id: "midnight-violet",
    label: "凌晨三点",
    accent: "rgb(176 92 255)",
    accentLight: "rgb(205 132 255)",
    accentDark: "rgb(115 72 255)",
    accentSoft: "rgb(176 92 255 / 0.14)",
    accentGlow: "rgb(176 92 255 / 0.22)",
    mesh1: "rgb(176 92 255 / 0.095)",
    mesh2: "rgb(34 211 238 / 0.045)",
    mesh3: "rgb(255 255 255 / 0.018)",
    composerTint: "rgb(176 92 255 / 0.10)",
    bubbleUserTint: "rgb(176 92 255 / 0.16)",
    bubbleAssistantTint: "rgb(176 92 255 / 0.085)",
    swatch: "linear-gradient(135deg, rgb(88 28 135), rgb(176 92 255))",
  },
  {
    id: "california-sun",
    label: "加州阳光",
    accent: "rgb(251 146 60)",
    accentLight: "rgb(253 186 116)",
    accentDark: "rgb(234 88 12)",
    accentSoft: "rgb(251 146 60 / 0.14)",
    accentGlow: "rgb(251 146 60 / 0.22)",
    mesh1: "rgb(251 146 60 / 0.10)",
    mesh2: "rgb(250 204 21 / 0.06)",
    mesh3: "rgb(255 255 255 / 0.016)",
    composerTint: "rgb(251 146 60 / 0.10)",
    bubbleUserTint: "rgb(251 146 60 / 0.16)",
    bubbleAssistantTint: "rgb(251 146 60 / 0.08)",
    swatch: "linear-gradient(135deg, rgb(234 88 12), rgb(251 191 36))",
  },
  {
    id: "geek-neon",
    label: "极客黑客",
    accent: "rgb(74 222 128)",
    accentLight: "rgb(134 239 172)",
    accentDark: "rgb(22 163 74)",
    accentSoft: "rgb(74 222 128 / 0.14)",
    accentGlow: "rgb(74 222 128 / 0.22)",
    mesh1: "rgb(74 222 128 / 0.08)",
    mesh2: "rgb(34 197 94 / 0.05)",
    mesh3: "rgb(255 255 255 / 0.014)",
    composerTint: "rgb(74 222 128 / 0.10)",
    bubbleUserTint: "rgb(74 222 128 / 0.14)",
    bubbleAssistantTint: "rgb(74 222 128 / 0.07)",
    swatch: "linear-gradient(135deg, rgb(22 101 52), rgb(74 222 128))",
  },
  {
    id: "ocean-code",
    label: "深海编程",
    accent: "rgb(96 165 250)",
    accentLight: "rgb(147 197 253)",
    accentDark: "rgb(37 99 235)",
    accentSoft: "rgb(96 165 250 / 0.14)",
    accentGlow: "rgb(96 165 250 / 0.22)",
    mesh1: "rgb(96 165 250 / 0.09)",
    mesh2: "rgb(34 211 238 / 0.05)",
    mesh3: "rgb(255 255 255 / 0.016)",
    composerTint: "rgb(96 165 250 / 0.10)",
    bubbleUserTint: "rgb(96 165 250 / 0.16)",
    bubbleAssistantTint: "rgb(96 165 250 / 0.08)",
    swatch: "linear-gradient(135deg, rgb(30 64 175), rgb(96 165 250))",
  },
  {
    id: "aurora-search",
    label: "极光搜索",
    accent: "rgb(34 211 238)",
    accentLight: "rgb(103 232 249)",
    accentDark: "rgb(8 145 178)",
    accentSoft: "rgb(34 211 238 / 0.14)",
    accentGlow: "rgb(34 211 238 / 0.22)",
    mesh1: "rgb(34 211 238 / 0.09)",
    mesh2: "rgb(129 140 248 / 0.06)",
    mesh3: "rgb(255 255 255 / 0.016)",
    composerTint: "rgb(34 211 238 / 0.10)",
    bubbleUserTint: "rgb(34 211 238 / 0.14)",
    bubbleAssistantTint: "rgb(34 211 238 / 0.07)",
    swatch: "linear-gradient(135deg, rgb(14 116 144), rgb(34 211 238))",
  },
  {
    id: "studio-creative",
    label: "创意工坊",
    accent: "rgb(217 70 239)",
    accentLight: "rgb(232 121 249)",
    accentDark: "rgb(168 85 247)",
    accentSoft: "rgb(217 70 239 / 0.14)",
    accentGlow: "rgb(217 70 239 / 0.22)",
    mesh1: "rgb(217 70 239 / 0.09)",
    mesh2: "rgb(168 85 247 / 0.06)",
    mesh3: "rgb(255 255 255 / 0.016)",
    composerTint: "rgb(217 70 239 / 0.10)",
    bubbleUserTint: "rgb(217 70 239 / 0.16)",
    bubbleAssistantTint: "rgb(217 70 239 / 0.08)",
    swatch: "linear-gradient(135deg, rgb(126 34 206), rgb(217 70 239))",
  },
  {
    id: "monochrome",
    label: "纯粹黑白",
    accent: "rgb(161 161 170)",
    accentLight: "rgb(212 212 216)",
    accentDark: "rgb(82 82 91)",
    accentSoft: "rgb(161 161 170 / 0.12)",
    accentGlow: "rgb(161 161 170 / 0.18)",
    mesh1: "rgb(255 255 255 / 0.04)",
    mesh2: "rgb(161 161 170 / 0.04)",
    mesh3: "rgb(255 255 255 / 0.012)",
    composerTint: "rgb(255 255 255 / 0.06)",
    bubbleUserTint: "rgb(255 255 255 / 0.08)",
    bubbleAssistantTint: "rgb(255 255 255 / 0.04)",
    swatch: "linear-gradient(135deg, rgb(63 63 70), rgb(161 161 170))",
  },
  {
    id: "ember-warm",
    label: "余烬暖调",
    accent: "rgb(245 158 11)",
    accentLight: "rgb(251 191 36)",
    accentDark: "rgb(217 119 6)",
    accentSoft: "rgb(245 158 11 / 0.14)",
    accentGlow: "rgb(245 158 11 / 0.22)",
    mesh1: "rgb(245 158 11 / 0.08)",
    mesh2: "rgb(239 68 68 / 0.05)",
    mesh3: "rgb(255 255 255 / 0.014)",
    composerTint: "rgb(245 158 11 / 0.10)",
    bubbleUserTint: "rgb(245 158 11 / 0.14)",
    bubbleAssistantTint: "rgb(245 158 11 / 0.07)",
    swatch: "linear-gradient(135deg, rgb(154 52 18), rgb(245 158 11))",
  },
]

export const DEFAULT_AGENT_THEME_ID: AgentAmbientThemeId = "midnight-violet"

export function getStoredAgentTheme(): AgentAmbientThemeId {
  if (typeof window === "undefined") return DEFAULT_AGENT_THEME_ID
  const stored = localStorage.getItem(AGENT_AMBIENT_THEME_KEY)
  return AGENT_AMBIENT_THEMES.some((t) => t.id === stored)
    ? (stored as AgentAmbientThemeId)
    : DEFAULT_AGENT_THEME_ID
}

export function storeAgentTheme(id: AgentAmbientThemeId) {
  if (typeof window === "undefined") return
  localStorage.setItem(AGENT_AMBIENT_THEME_KEY, id)
}

export function getAgentTheme(id?: AgentAmbientThemeId): AgentAmbientTheme {
  const themeId = id ?? getStoredAgentTheme()
  return AGENT_AMBIENT_THEMES.find((t) => t.id === themeId) ?? AGENT_AMBIENT_THEMES[0]
}

export function applyAgentThemeToElement(el: HTMLElement, id?: AgentAmbientThemeId) {
  const theme = getAgentTheme(id)
  const root = document.documentElement
  el.dataset.agentTheme = theme.id
  root.style.setProperty("--agent-accent", theme.accent)
  root.style.setProperty("--agent-accent-light", theme.accentLight)
  root.style.setProperty("--agent-accent-dark", theme.accentDark)
  root.style.setProperty("--agent-accent-soft", theme.accentSoft)
  root.style.setProperty("--agent-accent-glow", theme.accentGlow)
  root.style.setProperty("--agent-bg-mesh-1", theme.mesh1)
  root.style.setProperty("--agent-bg-mesh-2", theme.mesh2)
  root.style.setProperty("--agent-bg-mesh-3", theme.mesh3)
  root.style.setProperty("--agent-composer-tint", theme.composerTint)
  root.style.setProperty("--agent-bubble-user-tint", theme.bubbleUserTint)
  root.style.setProperty("--agent-bubble-assistant-tint", theme.bubbleAssistantTint)
  el.style.setProperty("--agent-accent", theme.accent)
  el.style.setProperty("--agent-accent-light", theme.accentLight)
  el.style.setProperty("--agent-accent-dark", theme.accentDark)
  el.style.setProperty("--agent-accent-soft", theme.accentSoft)
  el.style.setProperty("--agent-accent-glow", theme.accentGlow)
  el.style.setProperty("--agent-bg-mesh-1", theme.mesh1)
  el.style.setProperty("--agent-bg-mesh-2", theme.mesh2)
  el.style.setProperty("--agent-bg-mesh-3", theme.mesh3)
  el.style.setProperty("--agent-composer-tint", theme.composerTint)
  el.style.setProperty("--agent-bubble-user-tint", theme.bubbleUserTint)
  el.style.setProperty("--agent-bubble-assistant-tint", theme.bubbleAssistantTint)
  const sendGradient = `linear-gradient(135deg, ${theme.accentLight}, ${theme.accent} 48%, ${theme.accentDark})`
  const composerBg = `radial-gradient(circle at 14% 0%, ${theme.composerTint}, transparent 34%), radial-gradient(circle at 88% 100%, ${theme.mesh2}, transparent 28%), linear-gradient(180deg, rgb(255 255 255 / 0.07), rgb(255 255 255 / 0.026)), rgb(28 28 33 / 0.68)`
  root.style.setProperty("--agent-send-gradient", sendGradient)
  root.style.setProperty("--agent-composer-bg", composerBg)
  el.style.setProperty("--agent-send-gradient", sendGradient)
  el.style.setProperty("--agent-composer-bg", composerBg)
}

/** 将已存储的氛围主题应用到 Agent 页根节点 */
export function applyStoredAgentTheme(rootSelector = ".agent-page") {
  if (typeof document === "undefined") return
  const el = document.querySelector(rootSelector) as HTMLElement | null
  if (el) applyAgentThemeToElement(el)
}
