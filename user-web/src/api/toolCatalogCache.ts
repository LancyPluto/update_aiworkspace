import type { ToolListView } from "./types"

export const TOOL_CATALOG_MAX_PAGE_SIZE = 100
export const TOOL_CATALOG_CACHE_TTL_MS = 180_000

export type ToolCatalogQueryValue = string | number | boolean | undefined

export type ToolCatalogQuery = Record<string, ToolCatalogQueryValue> & {
  view?: ToolListView
  pageNo?: number
  pageSize?: number
  keyword?: string
  categoryId?: number
}

export interface ToolCatalogFetchOptions {
  token?: string | null
  query?: ToolCatalogQuery
}

function normalizePositiveInteger(value: unknown, fallback: number): number {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric < 1) return fallback
  return Math.floor(numeric)
}

export function normalizeToolCatalogQuery(query: ToolCatalogQuery = {}): ToolCatalogQuery {
  const normalized: ToolCatalogQuery = {
    view: query.view === "compact" ? "compact" : "summary",
    pageNo: normalizePositiveInteger(query.pageNo, 1),
    pageSize: Math.min(normalizePositiveInteger(query.pageSize, 20), TOOL_CATALOG_MAX_PAGE_SIZE),
  }

  const reservedKeys = new Set(["view", "pageNo", "pageSize"])
  for (const key of Object.keys(query).sort()) {
    if (reservedKeys.has(key)) continue
    const value = query[key]
    if (value === undefined || value === null) continue
    if (typeof value === "string") {
      const trimmed = value.trim()
      if (trimmed) normalized[key] = trimmed
      continue
    }
    if (typeof value === "number" && !Number.isFinite(value)) continue
    normalized[key] = value
  }

  return normalized
}

function toolCatalogCacheKey(query: ToolCatalogQuery): string {
  return Object.entries(query)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join("&")
}

export function createToolCatalogFetcher<TResult>(
  loader: (options: Required<Pick<ToolCatalogFetchOptions, "query">> & Pick<ToolCatalogFetchOptions, "token">) => Promise<TResult>,
  options: { ttlMs?: number; now?: () => number } = {},
): (request?: ToolCatalogFetchOptions) => Promise<TResult> {
  const ttlMs = options.ttlMs ?? TOOL_CATALOG_CACHE_TTL_MS
  const now = options.now ?? Date.now
  const cache = new Map<string, { expiresAt: number; value: TResult }>()
  const inFlight = new Map<string, Promise<TResult>>()

  return (request = {}) => {
    const query = normalizeToolCatalogQuery(request.query)
    const key = toolCatalogCacheKey(query)
    const cached = cache.get(key)
    if (cached && cached.expiresAt > now()) return Promise.resolve(cached.value)
    if (cached) cache.delete(key)

    const pending = inFlight.get(key)
    if (pending) return pending

    let promise: Promise<TResult>
    promise = loader({ token: request.token, query })
      .then((value) => {
        cache.set(key, { expiresAt: now() + ttlMs, value })
        return value
      })
      .finally(() => {
        if (inFlight.get(key) === promise) inFlight.delete(key)
      })
    inFlight.set(key, promise)
    return promise
  }
}
