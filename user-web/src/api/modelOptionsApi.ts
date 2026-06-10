import { apiRequest } from "./client"
import type { ModelOptionGroup, ModelOptionsResponse } from "./types"

export async function fetchModelOptions(
  mode: string,
  options?: { signal?: AbortSignal },
): Promise<ModelOptionsResponse> {
  const response = await apiRequest<ModelOptionsResponse | ModelOptionGroup[]>("GET", "/api/v1/model-options", {
    query: { mode },
    signal: options?.signal,
  })
  if (Array.isArray(response)) {
    return { mode, groups: response }
  }
  return {
    mode: response?.mode ?? mode,
    groups: response?.groups ?? [],
  }
}
