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
  updateCurrentUserProfile,
  updateCommunitySettings,
  uploadCurrentUserAvatar,
} from "@/api"
import { SESSION_TOKEN_STORAGE_KEY } from "@/constants/authStorage"
import { clearSessionBearerJwt, setSessionBearerJwt } from "@/api/sessionBearer"

const TOKEN_KEY = SESSION_TOKEN_STORAGE_KEY

export const useAuthStore = defineStore("auth", () => {
  const persisted = localStorage.getItem(TOKEN_KEY)
  const token = ref<string | null>(persisted)
  if (persisted) {
    setSessionBearerJwt(persisted)
  }
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

  async function fetchCurrentUser(options?: { clearOnFailure?: boolean }) {
    if (!token.value) return null
    const clearOnFailure = options?.clearOnFailure !== false
    try {
      const u = await getCurrentUser({ token: token.value })
      user.value = u
      return u
    } catch {
      if (clearOnFailure) clearAuth()
      return null
    }
  }

  function setUserProfile(profile: UserProfile) {
    user.value = profile
  }

  async function updateProfile(body: { nickname?: string; avatarUrl?: string | null }) {
    if (!token.value) throw new Error("请先登录")
    const profile = await updateCurrentUserProfile(body, { token: token.value })
    user.value = profile
    return profile
  }

  async function updateCommunityProfile(body: {
    bio?: string | null
    autoPublishAssets?: boolean
    promptPublicByDefault?: boolean
  }) {
    if (!token.value) throw new Error("请先登录")
    const profile = await updateCommunitySettings(body, { token: token.value })
    user.value = profile
    return profile
  }

  async function uploadAvatar(file: File) {
    if (!token.value) throw new Error("请先登录")
    const response = await uploadCurrentUserAvatar(file, { token: token.value })
    user.value = response.user
    return response
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
    clearSessionBearerJwt()
  }

  async function applyLoginResponse(res: LoginResponse, missingTokenMessage: string) {
    const t = res.token ?? res.accessToken
    if (!t) throw new Error(missingTokenMessage)
    token.value = t
    localStorage.setItem(TOKEN_KEY, t)
    setSessionBearerJwt(t)
    if (res.user) user.value = res.user
    const profile = await fetchCurrentUser({ clearOnFailure: false })
    if (!profile && !user.value) {
      clearAuth()
      throw new Error("登录成功但无法获取用户信息，请确认后端已启动")
    }
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
    setUserProfile,
    updateProfile,
    updateCommunityProfile,
    uploadAvatar,
    init,
  }
})
