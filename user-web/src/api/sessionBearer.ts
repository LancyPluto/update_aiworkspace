/**
 * 登录后 JWT：与 HttpOnly Cookie 中相同；优先保证请求带 Authorization（Cookie 未带上时避免 401）。
 * 使用 sessionStorage 与内存同步，刷新单页标签后仍可恢复（不写 localStorage）。
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
