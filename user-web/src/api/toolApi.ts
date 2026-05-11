import { apiRequest } from "./client"
import type { ToolCategory, ToolDetail, ToolSummary } from "./types"

/** GET /api/v1/tool-categories */
export async function fetchToolCategories(options?: { token?: string | null }): Promise<ToolCategory[]> {
  return apiRequest<ToolCategory[]>("GET", "/api/v1/tool-categories", { token: options?.token })
}

/** GET /api/v1/tools */
export async function fetchTools(options?: {
  token?: string | null
  query?: Record<string, string | number | boolean | undefined>
}): Promise<ToolSummary[]> {
  return apiRequest<ToolSummary[]>("GET", "/api/v1/tools", {
    token: options?.token,
    query: options?.query,
  })
}

/** GET /api/v1/tools/{toolCode} */
export async function fetchToolByCode(
  toolCode: string,
  options?: { token?: string | null },
): Promise<ToolDetail> {
  const encoded = encodeURIComponent(toolCode)
  return apiRequest<ToolDetail>("GET", `/api/v1/tools/${encoded}`, { token: options?.token })
}
