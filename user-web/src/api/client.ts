import type { ApiErrorCode, ApiResponse } from "./types"
import { getSessionBearerJwt } from "./sessionBearer"

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
  })

  let json: ApiResponse<T>
  try {
    json = (await res.json()) as ApiResponse<T>
  } catch {
    throw new ApiBusinessError("SYSTEM_ERROR", `无效响应 (${res.status})`, undefined)
  }

  if (json.code !== "SUCCESS") {
    throw new ApiBusinessError(json.code, json.message ?? json.code, json.requestId)
  }

  return json.data as T
}