import { http, unwrap } from './http'
import type { AdminUser, LoginResponse } from '@/types'

export function login(account: string, password: string) {
  return unwrap<LoginResponse>(
    http.post('/api/admin/v1/auth/login', {
      account,
      password
    })
  )
}

export function logout() {
  return unwrap<void>(http.post('/api/admin/v1/auth/logout'))
}

export function fetchMe() {
  return unwrap<AdminUser>(http.get('/api/admin/v1/auth/me'))
}
