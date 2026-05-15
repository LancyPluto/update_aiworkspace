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
import { SESSION_TOKEN_STORAGE_KEY } from "@/constants/authStorage"
import { clearSessionBearerJwt, setSessionBearerJwt } from "@/api/sessionBearer"

const TOKEN_KEY = SESSION_TOKEN_STORAGE_KEY

function readSessionToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export const useAuthStore = defineStore("auth", () => {
  const token = ref<string | null>(readSessionToken())
  const user = ref<UserProfile | null>(null)
  const loading = ref(false)
  const bootstrapComplete = ref(false)

  const isLoggedIn = computed(() => !!token.value || !!user.value)
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
    try {
      await apiLogout({ token: token.value })
    } catch {
      // Ignore logout failures and clear local state.
    }
    clearAuth()
  }

  function clearAuth() {
    token.value = null
    user.value = null
    clearSessionBearerJwt()
    try {
      sessionStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(TOKEN_KEY)
    } catch {
      // ignore storage errors
    }
  }

  async function applyLoginResponse(res: LoginResponse, missingTokenMessage: string) {
    const t = res.token ?? res.accessToken
    if (!t) throw new Error(missingTokenMessage)
    token.value = t
    setSessionBearerJwt(t)
    try {
      sessionStorage.setItem(TOKEN_KEY, t)
      localStorage.removeItem(TOKEN_KEY)
    } catch {
      // ignore storage errors
    }
    await fetchCurrentUser()
    return res
  }

  async function init() {
    try {
      await fetchCurrentUser()
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
