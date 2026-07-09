/** 旧 Bearer 过渡兼容；浏览器正常会话以 HttpOnly Cookie 为准，不再作为默认请求来源。 */
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
