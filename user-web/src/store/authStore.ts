import { defineStore } from "pinia"
import { computed, ref } from "vue"
import type { LoginRequest, LoginResponse, RegisterRequest, SmsAuthRequest, UserProfile } from "@/api/types"
import {
  getCurrentUser,
  login as apiLogin,
  logout as apiLogout,
  register as apiRegister,
  smsLogin as apiSmsLogin,
  smsRegister as apiSmsRegister,
} from "@/api"

const TOKEN_KEY = "ai_tool_market_token"

export const useAuthStore = defineStore("auth", () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const user = ref<UserProfile | null>(null)
  const loading = ref(false)
  const bootstrapComplete = ref(false)

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.userType === "ADMIN")

  async function login(body: LoginRequest) {
    loading.value = true
    try {
      const res = await apiLogin(body)
      return await applyLoginResponse(res, "登录响应缺少 token")
    } finally {
      loading.value = false
    }
  }

  async function register(body: RegisterRequest) {
    loading.value = true
    try {
      const res = await apiRegister(body)
      return await applyLoginResponse(res, "注册响应缺少 token")
    } finally {
      loading.value = false
    }
  }

  async function smsRegister(body: SmsAuthRequest) {
    loading.value = true
    try {
      const res = await apiSmsRegister(body)
      return await applyLoginResponse(res, "注册响应缺少 token")
    } finally {
      loading.value = false
    }
  }

  async function smsLogin(body: SmsAuthRequest) {
    loading.value = true
    try {
      const res = await apiSmsLogin(body)
      return await applyLoginResponse(res, "登录响应缺少 token")
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
      clearAuth()
      return null
    }
  }

  async function logout() {
    if (token.value) {
      try {
        await apiLogout({ token: token.value })
      } catch {
        // Ignore logout failures and clear local state.
      }
    }
    clearAuth()
  }

  function clearAuth() {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  async function applyLoginResponse(res: LoginResponse, missingTokenMessage: string) {
    const t = res.token ?? res.accessToken
    if (!t) throw new Error(missingTokenMessage)
    token.value = t
    localStorage.setItem(TOKEN_KEY, t)
    await fetchCurrentUser()
    return res
  }

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
    register,
    smsRegister,
    smsLogin,
    logout,
    fetchCurrentUser,
    init,
  }
})
