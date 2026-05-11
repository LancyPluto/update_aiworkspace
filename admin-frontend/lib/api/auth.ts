import { http, setStoredUser, setToken, clearSession } from './http'
import type { AdminUser, LoginResponse } from './types'

export async function adminLogin(account: string, password: string) {
  const data = await http.post<LoginResponse>('/api/admin/v1/auth/login', { account, password })
  if (data.accessToken) {
    setToken(data.accessToken)
  }
  setStoredUser(data.user)
  return data
}

export async function fetchAdminMe() {
  return http.get<AdminUser>('/api/admin/v1/auth/me')
}

export async function adminLogout() {
  try {
    await http.post<void>('/api/admin/v1/auth/logout')
  } finally {
    clearSession()
  }
}
