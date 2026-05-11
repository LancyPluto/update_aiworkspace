import { apiRequest } from "./client"
import type { UserProfile } from "./types"

/** GET /api/v1/users/me */
export async function getCurrentUser(options?: { token?: string | null }): Promise<UserProfile> {
  return apiRequest<UserProfile>("GET", "/api/v1/users/me", { token: options?.token })
}
