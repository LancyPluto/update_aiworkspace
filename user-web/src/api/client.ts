import type { ApiErrorCode, ApiResponse } from "./types"
import { SESSION_TOKEN_STORAGE_KEY } from "@/constants/authStorage"
import { clearSessionBearerJwt, getSessionBearerJwt } from "./sessionBearer"

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
  token?: string | null
  query?: Record<string, string | number | boolean | undefined>
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
    sessionStorage.removeItem(SESSION_TOKEN_STORAGE_KEY)
    localStorage.removeItem(SESSION_TOKEN_STORAGE_KEY)
  } catch {
    // ignore storage errors
  }
  clearSessionBearerJwt()
  const full = `${window.location.pathname}${window.location.search}`
  const base = import.meta.env.BASE_URL || "/"
  const normalizedBase = base.endsWith("/") ? base.slice(0, -1) : base
  const loginPath = (normalizedBase ? `${normalizedBase}/login` : "/login").replace(/\/+/g, "/")
  window.location.assign(`${window.location.origin}${loginPath}?redirect=${encodeURIComponent(full)}`)
}

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

  const requestId = json.requestId ?? json.traceId
  if (res.status === 401 || json.code === "UNAUTHORIZED") {
    redirectToLoginPage()
    throw new ApiBusinessError(json.code ?? "UNAUTHORIZED", json.message ?? "登录已失效，请重新登录", requestId)
  }

  if (json.code !== "SUCCESS") {
    throw new ApiBusinessError(json.code, json.message ?? json.code, requestId)
  }

  return json.data as T
}
