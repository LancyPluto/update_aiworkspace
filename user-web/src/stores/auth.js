import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '../api/auth'

const TOKEN_KEY = 'auth_token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref(null)

  const isLoggedIn = computed(() => Boolean(token.value))

  function setToken(t) {
    token.value = t
    if (t) localStorage.setItem(TOKEN_KEY, t)
    else localStorage.removeItem(TOKEN_KEY)
  }

  function clearToken() {
    setToken('')
    user.value = null
  }

  async function login(payload) {
    const data = await authApi.login(payload)
    const t = pickToken(data)
    if (!t) throw new Error('登录响应缺少 token')
    setToken(t)
    await fetchMe()
    return data
  }

  async function register(payload) {
    return authApi.register(payload)
  }

  async function fetchMe() {
    if (!token.value) return null
    try {
      const data = await authApi.me()
      user.value = unwrapUser(data)
      return user.value
    } catch {
      clearToken()
      return null
    }
  }

  function logout() {
    clearToken()
  }

  return {
    token,
    user,
    isLoggedIn,
    setToken,
    clearToken,
    login,
    register,
    fetchMe,
    logout,
  }
})

function pickToken(data) {
  if (!data || typeof data !== 'object') return ''
  return (
    data.token ||
    data.accessToken ||
    data.access_token ||
    data.data?.token ||
    data.data?.accessToken ||
    ''
  )
}

function unwrapUser(data) {
  if (!data) return null
  if (data.data && typeof data.data === 'object') return data.data
  return data
}
