import { apiRequest } from "./client"
import type { UserProfile } from "./types"

<<<<<<< Updated upstream
/** GET /api/v1/users/me */
=======
const P = {
  me: "/api/v1/users/me",
} as const

/** GET /api/v1/users/me —— Cookie 或 Bearer */
>>>>>>> Stashed changes
export async function getCurrentUser(options?: { token?: string | null }): Promise<UserProfile> {
  return apiRequest<UserProfile>("GET", "/api/v1/users/me", { token: options?.token })
}
