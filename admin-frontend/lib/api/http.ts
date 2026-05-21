import type { ApiResponse } from './types'

const TOKEN_STORAGE_KEY = 'admin_access_token'
const USER_STORAGE_KEY = 'admin_user_profile'

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || ''
const LOCAL_BACKEND_URL = 'http://127.0.0.1:8080'

export class ApiError extends Error {
  code: string
  status?: number
  constructor(message: string, code: string, status?: number) {
    super(message)
    this.code = code
    this.status = status
  }
}

export function getToken(): string | null {
  if (typeof window === 'undefined') return null
  try {
    return window.sessionStorage.getItem(TOKEN_STORAGE_KEY)
  } catch {
    return null
  }
}

export function setToken(token: string | null) {
  if (typeof window === 'undefined') return
  try {
    if (token) {
      window.sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
      window.localStorage.removeItem(TOKEN_STORAGE_KEY)
    } else {
      window.sessionStorage.removeItem(TOKEN_STORAGE_KEY)
      window.localStorage.removeItem(TOKEN_STORAGE_KEY)
    }
  } catch {
    // ignore storage errors
  }
}

export function getStoredUser<T = unknown>(): T | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.sessionStorage.getItem(USER_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}

export function setStoredUser(profile: unknown | null) {
  if (typeof window === 'undefined') return
  try {
    if (profile) {
      window.sessionStorage.setItem(USER_STORAGE_KEY, JSON.stringify(profile))
      window.localStorage.removeItem(USER_STORAGE_KEY)
    } else {
      window.sessionStorage.removeItem(USER_STORAGE_KEY)
      window.localStorage.removeItem(USER_STORAGE_KEY)
    }
  } catch {
    // ignore storage errors
  }
}

export function clearSession() {
  setToken(null)
  setStoredUser(null)
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  query?: Record<string, string | number | boolean | undefined | null>
  body?: unknown
  signal?: AbortSignal
  skipAuthRedirect?: boolean
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const baseUrl = getBaseUrl()
  const url = `${baseUrl}${path}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue
    const str = String(value).trim()
    if (str.length === 0) continue
    params.append(key, str)
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

export function getBaseUrl(): string {
  if (BASE_URL) return BASE_URL
  if (typeof window === 'undefined') return ''
  const { hostname, port } = window.location
  if ((hostname === '127.0.0.1' || hostname === 'localhost') && port === '5174') {
    return LOCAL_BACKEND_URL
  }
  return ''
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method: options.method || 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
      credentials: 'include',
    })
  } catch (err) {
    if ((err as Error).name === 'AbortError') {
      throw err
    }
    throw new ApiError('网络异常，请检查后端服务是否启动', 'NETWORK_ERROR')
  }

  if ((response.status === 401 || response.status === 403) && !options.skipAuthRedirect) {
    clearSession()
    if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
      window.location.href = '/login'
    }
    throw new ApiError('登录已过期，请重新登录', 'UNAUTHORIZED', 401)
  }

  let payload: ApiResponse<T> | null = null
  try {
    payload = (await response.json()) as ApiResponse<T>
  } catch {
    // ignore JSON parse error
  }

  if (!response.ok || !payload) {
    const message = payload?.message || `请求失败 (${response.status})`
    const code = payload?.code || 'HTTP_ERROR'
    throw new ApiError(message, code, response.status)
  }

  if (payload.code && payload.code !== 'SUCCESS') {
    throw new ApiError(payload.message || payload.code, payload.code)
  }

  return payload.data
}

export const http = {
  get<T>(path: string, query?: RequestOptions['query']) {
    return request<T>(path, { method: 'GET', query })
  },
  post<T>(path: string, body?: unknown) {
    return request<T>(path, { method: 'POST', body })
  },
  put<T>(path: string, body?: unknown) {
    return request<T>(path, { method: 'PUT', body })
  },
  patch<T>(path: string, body?: unknown) {
    return request<T>(path, { method: 'PATCH', body })
  },
  delete<T>(path: string) {
    return request<T>(path, { method: 'DELETE' })
  },
  async postForm<T>(path: string, formData: FormData): Promise<T> {
    const headers: Record<string, string> = {
      Accept: 'application/json',
    }
    const token = getToken()
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    const response = await fetch(buildUrl(path), {
      method: 'POST',
      headers,
      body: formData,
    })

    let payload: ApiResponse<T> | null = null
    try {
      payload = (await response.json()) as ApiResponse<T>
    } catch {
      // ignore JSON parse error
    }

    if (!response.ok || !payload) {
      const message = payload?.message || `请求失败 (${response.status})`
      const code = payload?.code || 'HTTP_ERROR'
      throw new ApiError(message, code, response.status)
    }

    if (payload.code && payload.code !== 'SUCCESS') {
      throw new ApiError(payload.message || payload.code, payload.code)
    }

    return payload.data
  },
}
