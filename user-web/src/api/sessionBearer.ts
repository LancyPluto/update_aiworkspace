/**
 * 登录后 JWT：与 HttpOnly Cookie 中相同；优先保证请求带 Authorization（Cookie 未带上时避免 401）。
 * 使用 sessionStorage 与内存同步，刷新单页标签后仍可恢复（本文件不直接写 localStorage）。
 * 长期持久化键仍在 authStore（localStorage）；authStore 在首次加载、登录成功与 clearAuth 时调用
 * setSessionBearerJwt / clearSessionBearerJwt，与 apiRequest 的默认 Bearer 来源对齐。
 */
const STORAGE_KEY = "atm_user_session_jwt"

let memoryJwt: string | null = null

export function setSessionBearerJwt(jwt: string | null): void {
  memoryJwt = jwt
  try {
    if (jwt) sessionStorage.setItem(STORAGE_KEY, jwt)
    else sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore private mode / quota
  }
}

export function getSessionBearerJwt(): string | null {
  if (memoryJwt) return memoryJwt
  try {
    const s = sessionStorage.getItem(STORAGE_KEY)
    if (s) {
      memoryJwt = s
      return s
    }
  } catch {
    // ignore
  }
  return null
}

export function clearSessionBearerJwt(): void {
  memoryJwt = null
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore
  }
}
