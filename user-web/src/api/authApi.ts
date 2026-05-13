import { apiRequest } from "./client"
import type { LoginRequest, LoginResponse, RegisterRequest } from "./types"

const P = {
  register: "/api/v1/auth/register",
  login: "/api/v1/auth/login",
  logout: "/api/v1/auth/logout",
} as const

/** POST /api/v1/auth/register */
export async function register(body: RegisterRequest): Promise<LoginResponse> {
  const data = await apiRequest<LoginResponse | null>("POST", P.register, { body })
  if (!data) {
    throw new Error("注册响应无效")
  }
  return data
}

/** POST /api/v1/auth/login（Set-Cookie 会话） */
export async function login(body: LoginRequest, options?: { token?: string | null }): Promise<LoginResponse> {
  const data = await apiRequest<LoginResponse | null>("POST", P.login, {
    body,
    token: options?.token,
  })
  if (!data) {
    throw new Error("登录响应缺少 data")
  }
  return data
}

/** POST /api/v1/auth/logout（Cookie 或 Bearer） */
export async function logout(options?: { token?: string | null }): Promise<void> {
  await apiRequest<unknown>("POST", P.logout, { token: options?.token })
}
