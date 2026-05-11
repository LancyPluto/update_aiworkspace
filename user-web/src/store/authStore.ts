import { defineStore } from "pinia"
import { ref, computed } from "vue"
import type { LoginRequest, UserProfile } from "@/api/types"
import { login as apiLogin, logout as apiLogout, getCurrentUser } from "@/api"
import { setSessionBearerJwt } from "@/api/sessionBearer"

/** 迁移：曾写入 localStorage 的 JWT 键名，登出/初始化时一并清理 */
const LEGACY_TOKEN_KEY = "ai_tool_market_token"
const LEGACY_AUTH_TOKEN_KEY = "authToken"

function clearStoredTokens() {
  try {
    localStorage.removeItem(LEGACY_TOKEN_KEY)
    localStorage.removeItem(LEGACY_AUTH_TOKEN_KEY)
  } catch {
    // ignore
  }
}

export const useAuthStore = defineStore("auth", () => {
  const user = ref<UserProfile | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => !!user.value)
  const isAdmin = computed(() => user.value?.userType === "ADMIN")

  async function login(body: LoginRequest) {
    loading.value = true
    try {
      setSessionBearerJwt(null)
      const loginResult = await apiLogin(body)
      setSessionBearerJwt(loginResult.accessToken ?? null)
      const profile = await fetchCurrentUser()
      if (!profile) {
        throw new Error("登录成功但获取用户信息失败")
      }
    } finally {
      loading.value = false
    }
  }

  async function fetchCurrentUser() {
    try {
      const u = await getCurrentUser()
      user.value = u
      return u
    } catch {
      clearAuth()
      return null
    }
  }


  async function logout() {
    try {
      await apiLogout()
    } catch {
      // 忽略退出失败
    }
    clearAuth()
  }

  function clearAuth() {
    user.value = null
    clearStoredTokens()
    setSessionBearerJwt(null)
  }

  /** 通过 HttpOnly Cookie 恢复登录态 */
  async function init() {
    clearStoredTokens()
    await fetchCurrentUser()
  }

  return {
    user,
    loading,
    isLoggedIn,
    isAdmin,
    login,
    logout,
    fetchCurrentUser,
    init,
  }
})
