export type AppTheme = "light" | "dark"

export const APP_THEME_STORAGE_KEY = "ai_tool_market_theme"

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

export function storeAppTheme(theme: AppTheme) {
  if (typeof window === "undefined") return
  localStorage.setItem(APP_THEME_STORAGE_KEY, theme)
}
