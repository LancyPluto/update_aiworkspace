import { defineStore } from "pinia"
import { ref, computed } from "vue"
import type { LoginRequest, UserProfile } from "@/api/types"
import { login as apiLogin, logout as apiLogout, getCurrentUser } from "@/api"

const TOKEN_KEY = "ai_tool_market_token"

export const useAuthStore = defineStore("auth", () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const user = ref<UserProfile | null>(null)
  const loading = ref(false)
  /** 首次 init()（含 /me）是否已跑完；无 token 时也会在一次 init 后置 true */
  const bootstrapComplete = ref(false)

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.userType === "ADMIN")

  async function login(body: LoginRequest) {
    loading.value = true
    try {
      const res = await apiLogin(body)
      const t = res.token ?? res.accessToken
      if (!t) throw new Error("登录响应缺少 token")
      token.value = t
      localStorage.setItem(TOKEN_KEY, t)
      // 获取用户信息
      await fetchCurrentUser()
      return res
    } finally {
      loading.value = false
    }
  }

  async function fetchCurrentUser() {
    if (!token.value) return null
    try {
      const u = await getCurrentUser({ token: token.value })
      user.value = u
      return u
    } catch {
      // Token 可能已过期
      clearAuth()
      return null
    }
  }

  async function logout() {
    if (token.value) {
      try {
        await apiLogout({ token: token.value })
      } catch {
        // 忽略退出失败
      }
    }
    clearAuth()
  }

  function clearAuth() {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  /** 初始化时尝试从 localStorage 恢复登录态 */
  async function init() {
    try {
      if (token.value) {
        await fetchCurrentUser()
      }
    } finally {
      bootstrapComplete.value = true
    }
  }

  return {
    token,
    user,
    loading,
    bootstrapComplete,
    isLoggedIn,
    isAdmin,
    login,
    logout,
    fetchCurrentUser,
    init,
  }
})
