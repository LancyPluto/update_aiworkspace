import type { ApiErrorCode, ApiResponse } from "./types"
import { SESSION_TOKEN_STORAGE_KEY } from "@/constants/authStorage"
import { clearSessionBearerJwt, getSessionBearerJwt } from "./sessionBearer"

/**
 * 后端 Origin，不含路径。例如 http://localhost:8080
 * 接口路径本身已含 /api/v1/...（见契约 §8）
 */
export function getApiOrigin(): string {
  const raw = import.meta.env.VITE_API_BASE ?? ""
  return raw.replace(/\/$/, "")
}

export class ApiBusinessError extends Error {
  readonly code: ApiErrorCode
  readonly requestId?: string

  constructor(code: ApiErrorCode, message: string, requestId?: string) {
    super(message)
    this.name = "ApiBusinessError"
    this.code = code
    this.requestId = requestId
  }
}

export interface RequestOptions {
  /** 可选 Bearer（脚本/调试）；浏览器会话使用 Cookie */
  token?: string | null
  query?: Record<string, string | number | boolean | undefined>
  /** 取消进行中的请求（如离开页面、发起新请求前） */
  signal?: AbortSignal
}

function buildUrl(path: string, query?: RequestOptions["query"]): string {
  const origin = getApiOrigin()
  const pathPart = path.startsWith("/") ? path : `/${path}`
  let url: URL
  if (path.startsWith("http")) {
    url = new URL(path)
  } else if (origin) {
    url = new URL(pathPart, origin.endsWith("/") ? origin : `${origin}/`)
  } else if (typeof window !== "undefined") {
    url = new URL(pathPart, window.location.origin)
  } else {
    url = new URL(pathPart, "http://localhost")
  }
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, String(v))
    }
  }
  return url.toString()
}

function redirectToLoginPage(): void {
  if (typeof window === "undefined") return
  const path = window.location.pathname
  if (path === "/login" || path.endsWith("/login")) return
  try {
    localStorage.removeItem(SESSION_TOKEN_STORAGE_KEY)
  } catch {
    // ignore
  }
  clearSessionBearerJwt()
  const full = `${window.location.pathname}${window.location.search}`
  const base = import.meta.env.BASE_URL || "/"
  const normalizedBase = base.endsWith("/") ? base.slice(0, -1) : base
  const loginPath = (normalizedBase ? `${normalizedBase}/login` : "/login").replace(/\/+/g, "/")
  window.location.assign(`${window.location.origin}${loginPath}?redirect=${encodeURIComponent(full)}`)
}

/**
 * 统一解析契约响应壳；code !== SUCCESS 时抛 ApiBusinessError。
 * credentials + Cookie；Authorization 使用 options.token 或登录后 sessionBearer（与 Cookie 中 JWT 一致）。
 */
export async function apiRequest<T>(
  method: string,
  path: string,
  options?: RequestOptions & { body?: unknown },
): Promise<T> {
  const url = buildUrl(path, options?.query)
  const headers: Record<string, string> = {
    Accept: "application/json",
  }

  const rawBody = options?.body
  let bodyInit: BodyInit | undefined
  if (rawBody instanceof FormData) {
    bodyInit = rawBody
  } else if (rawBody !== undefined) {
    headers["Content-Type"] = "application/json"
    bodyInit = JSON.stringify(rawBody)
  }

  const token = options?.token ?? getSessionBearerJwt()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const res = await fetch(url, {
    method,
    headers,
    body: bodyInit,
    credentials: "include",
    signal: options?.signal,
  })

  const rawText = await res.text()
  let json: ApiResponse<T>
  try {
    json = (rawText ? JSON.parse(rawText) : {}) as ApiResponse<T>
  } catch {
    if (res.status === 401) {
      redirectToLoginPage()
      throw new ApiBusinessError("UNAUTHORIZED", "登录已失效，请重新登录", undefined)
    }
    throw new ApiBusinessError("SYSTEM_ERROR", `无效响应 (${res.status})`, undefined)
  }

  if (res.status === 401 || json.code === "UNAUTHORIZED") {
    redirectToLoginPage()
    throw new ApiBusinessError(json.code ?? "UNAUTHORIZED", json.message ?? "登录已失效，请重新登录", json.requestId)
  }

  if (json.code !== "SUCCESS") {
    throw new ApiBusinessError(json.code, json.message ?? json.code, json.requestId)
  }

  return json.data as T
}