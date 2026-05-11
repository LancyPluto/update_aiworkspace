import { apiRequest } from "./client"
import type { UserProfile } from "./types"

const P = {
  me: "/api/v1/users/me",
} as const

/** GET /api/v1/users/me —— 获取当前登录用户信息 */
export async function getCurrentUser(options?: { token?: string | null }): Promise<UserProfile> {
  return apiRequest<UserProfile>("GET", P.me, { token: options?.token })
}
