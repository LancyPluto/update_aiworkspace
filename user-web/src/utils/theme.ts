export type AppTheme = "light" | "dark"
export type BrandAccent = "cyan" | "magenta"

export const APP_THEME_STORAGE_KEY = "ai_tool_market_theme"
export const BRAND_ACCENT_STORAGE_KEY = "ai_tool_market_brand_accent"

export const BRAND_ACCENT_OPTIONS: Array<{
  id: BrandAccent
  label: string
  description: string
  swatch: string
}> = [
  {
    id: "cyan",
    label: "青蓝",
    description: "对齐 Logo 的默认品牌色",
    swatch: "linear-gradient(135deg, #22d3ee, #2563eb)",
  },
  {
    id: "magenta",
    label: "品红",
    description: "保留原先的高能创作感",
    swatch: "linear-gradient(135deg, #ff3f79, #8f5cff)",
  },
]

export function getStoredTheme(): AppTheme {
  if (typeof window === "undefined") return "light"
  return localStorage.getItem(APP_THEME_STORAGE_KEY) === "dark" ? "dark" : "light"
}

export function applyAppTheme(theme: AppTheme) {
  if (typeof document === "undefined") return
  const root = document.documentElement
  root.dataset.theme = theme
  root.classList.toggle("dark", theme === "dark")
}

export function getStoredBrandAccent(): BrandAccent {
  if (typeof window === "undefined") return "cyan"
  return localStorage.getItem(BRAND_ACCENT_STORAGE_KEY) === "magenta" ? "magenta" : "cyan"
}

export function applyBrandAccent(accent: BrandAccent) {
  if (typeof document === "undefined") return
  document.documentElement.dataset.accent = accent
}

export function storeBrandAccent(accent: BrandAccent) {
  if (typeof window === "undefined") return
  localStorage.setItem(BRAND_ACCENT_STORAGE_KEY, accent)
}

export function storeAppTheme(theme: AppTheme) {
  if (typeof window === "undefined") return
  localStorage.setItem(APP_THEME_STORAGE_KEY, theme)
}
