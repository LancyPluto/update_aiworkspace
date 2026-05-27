import { apiRequest } from "./client"
import type { LoginRequest, LoginResponse, RegisterRequest, ResetPasswordRequest, SmsAuthRequest, SmsCodeRequest, SmsCodeResponse } from "./types"

const P = {
  register: "/api/v1/auth/register",
  login: "/api/v1/auth/login",
  smsCode: "/api/v1/auth/sms-code",
  smsRegister: "/api/v1/auth/sms-register",
  smsLogin: "/api/v1/auth/sms-login",
  resetPassword: "/api/v1/auth/reset-password",
  logout: "/api/v1/auth/logout",
} as const

export async function register(body: RegisterRequest): Promise<LoginResponse> {
  const data = await apiRequest<LoginResponse | null>("POST", P.register, { body })
  if (!data) {
    throw new Error("注册响应缺少 data")
  }
  return data
}

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

export async function sendSmsCode(body: SmsCodeRequest): Promise<SmsCodeResponse> {
  const data = await apiRequest<SmsCodeResponse | null>("POST", P.smsCode, { body })
  if (!data) {
    throw new Error("验证码响应缺少 data")
  }
  return data
}

export async function smsRegister(body: SmsAuthRequest): Promise<LoginResponse> {
  const data = await apiRequest<LoginResponse | null>("POST", P.smsRegister, { body })
  if (!data) {
    throw new Error("注册响应缺少 data")
  }
  return data
}

export async function smsLogin(body: SmsAuthRequest): Promise<LoginResponse> {
  const data = await apiRequest<LoginResponse | null>("POST", P.smsLogin, { body })
  if (!data) {
    throw new Error("登录响应缺少 data")
  }
  return data
}

export async function resetPassword(body: ResetPasswordRequest): Promise<void> {
  await apiRequest<unknown>("POST", P.resetPassword, { body })
}

export async function logout(options?: { token?: string | null }): Promise<void> {
  await apiRequest<unknown>("POST", P.logout, { token: options?.token })
}
