import { ref, type Ref } from "vue"
import type { TaskDetail, UserUploadAsset } from "@/api/types"
import { fetchTasks } from "@/api/taskApi"
import { fetchUploadAssets } from "@/api/toolApi"
import {
  MATERIAL_AUTO_FETCH_MAX_ROUNDS,
  MATERIAL_TASK_FETCH_BATCH,
  PICKER_PAGE_SIZE,
  UPLOAD_HISTORY_CACHE_LIMIT,
} from "@/constants/materialPicker"

export type MaterialKind = "image" | "video" | "audio" | "file"

const ALL_MATERIAL_KINDS: MaterialKind[] = ["image", "video", "audio", "file"]

function uploadHistoryStorageKey(userId: string | number, kind: MaterialKind): string {
  return `ai_tool_market_upload_history:${userId}:${kind}`
}

function dedupeByUrl<T extends { url: string; id: string }>(items: T[]): T[] {
  const seen = new Set<string>()
  const result: T[] = []
  for (const item of items) {
    const key = item.url || item.id
    if (!key || seen.has(key)) continue
    seen.add(key)
    result.push(item)
  }
  return result
}

function sortByUploadedAtDesc<T extends { uploadedAt?: string }>(items: T[]): T[] {
  return [...items].sort((left, right) => {
    const leftTime = Date.parse(left.uploadedAt || "")
    const rightTime = Date.parse(right.uploadedAt || "")
    if (Number.isFinite(leftTime) && Number.isFinite(rightTime) && leftTime !== rightTime) {
      return rightTime - leftTime
    }
    return 0
  })
}

export function useUploadHistoryList<T extends { id: string; url: string; kind: MaterialKind; uploadedAt: string }>(options: {
  getKind: () => MaterialKind | null
  getToken: () => string | null | undefined
  getUserId: () => string | number | undefined
  toHistoryItem: (asset: UserUploadAsset) => T | null
}) {
  const items = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const loadingMore = ref(false)
  const hasMore = ref(false)
  const error = ref("")
  let pageNo = 1

  function readCacheForKind(kind: MaterialKind): T[] {
    if (typeof window === "undefined") return []
    const userId = options.getUserId() ?? "guest"
    try {
      const raw = window.localStorage.getItem(uploadHistoryStorageKey(userId, kind))
      if (!raw) return []
      const parsed = JSON.parse(raw) as unknown
      if (!Array.isArray(parsed)) return []
      return parsed
        .filter((item): item is T => Boolean(item && typeof item === "object" && typeof (item as T).url === "string"))
        .slice(0, UPLOAD_HISTORY_CACHE_LIMIT)
    } catch {
      return []
    }
  }

  function readCache(): T[] {
    const kind = options.getKind()
    if (kind) return readCacheForKind(kind)
    return dedupeByUrl(ALL_MATERIAL_KINDS.flatMap((entry) => readCacheForKind(entry)))
  }

  function writeCacheForKind(kind: MaterialKind, list: T[]) {
    if (typeof window === "undefined") return
    const userId = options.getUserId() ?? "guest"
    window.localStorage.setItem(
      uploadHistoryStorageKey(userId, kind),
      JSON.stringify(list.slice(0, UPLOAD_HISTORY_CACHE_LIMIT)),
    )
  }

  function writeCache(list: T[]) {
    const kind = options.getKind()
    if (kind) {
      writeCacheForKind(kind, list)
      return
    }
    const grouped = new Map<MaterialKind, T[]>()
    for (const item of list) {
      const bucket = grouped.get(item.kind) ?? []
      bucket.push(item)
      grouped.set(item.kind, bucket)
    }
    for (const entry of ALL_MATERIAL_KINDS) {
      writeCacheForKind(entry, grouped.get(entry) ?? [])
    }
  }

  function mergeItems(...lists: T[][]): T[] {
    return sortByUploadedAtDesc(dedupeByUrl(lists.flat()))
  }

  async function resetAndLoad() {
    const kind = options.getKind()
    const localItems = readCache()
    items.value = localItems
    pageNo = 1
    loading.value = true
    loadingMore.value = false
    error.value = ""
    hasMore.value = false
    const token = options.getToken()
    if (!token) {
      loading.value = false
      return
    }
    try {
      const page = await fetchUploadAssets({
        token,
        kind: kind ?? undefined,
        pageNo: 1,
        pageSize: PICKER_PAGE_SIZE,
      })
      const serverItems = page.list
        .map(options.toHistoryItem)
        .filter((item): item is T => Boolean(item))
        .filter((item) => !kind || item.kind === kind)
      const merged = mergeItems(serverItems, localItems)
      writeCache(merged.slice(0, UPLOAD_HISTORY_CACHE_LIMIT))
      items.value = merged
      hasMore.value = page.hasNext
    } catch (err) {
      error.value = (err as Error).message || "上传历史加载失败"
      items.value = localItems
    } finally {
      loading.value = false
    }
  }

  async function loadMore() {
    if (loading.value || loadingMore.value || !hasMore.value) return
    const token = options.getToken()
    if (!token) return
    const kind = options.getKind()
    loadingMore.value = true
    try {
      const nextPage = pageNo + 1
      const page = await fetchUploadAssets({
        token,
        kind: kind ?? undefined,
        pageNo: nextPage,
        pageSize: PICKER_PAGE_SIZE,
      })
      const serverItems = page.list
        .map(options.toHistoryItem)
        .filter((item): item is T => Boolean(item))
        .filter((item) => !kind || item.kind === kind)
      items.value = mergeItems(items.value, serverItems)
      hasMore.value = page.hasNext
      pageNo = nextPage
    } catch (err) {
      error.value = (err as Error).message || "上传历史加载失败"
    } finally {
      loadingMore.value = false
    }
  }

  function rememberItem(item: T) {
    const kind = item.kind
    const existing = readCacheForKind(kind).filter((entry) => entry.url !== item.url)
    writeCacheForKind(kind, [item, ...existing])
    items.value = mergeItems([item], items.value.filter((entry) => entry.url !== item.url))
  }

  function removeItem(item: T) {
    const kind = options.getKind() ?? item.kind
    const next = readCacheForKind(kind).filter((entry) => entry.id !== item.id && entry.url !== item.url)
    writeCacheForKind(kind, next)
    items.value = items.value.filter((entry) => entry.id !== item.id && entry.url !== item.url)
  }

  function prependItems(extra: T[]) {
    if (extra.length === 0) return
    items.value = mergeItems(extra, items.value)
  }

  return {
    items,
    loading,
    loadingMore,
    hasMore,
    error,
    resetAndLoad,
    loadMore,
    rememberItem,
    removeItem,
    prependItems,
    mergeItems,
  }
}

export function useGeneratedMaterialList<T extends { id: string; kind: MaterialKind; url: string }>(options: {
  getKind: () => MaterialKind | null
  getToken: () => string | null | undefined
  createAssetsFromTask: (task: TaskDetail, kind: MaterialKind) => T[]
}) {
  const assets = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const loadingMore = ref(false)
  const hasMore = ref(false)
  const error = ref("")
  let pageNo = 1
  const loadedTasks: TaskDetail[] = []

  function assetKey(item: T): string {
    return `${item.kind}:${item.url}`
  }

  function extractAssets(taskList: TaskDetail[], targetKind: MaterialKind | null, seen: Set<string>): T[] {
    const result: T[] = []
    for (const task of taskList) {
      for (const asset of options.createAssetsFromTask(task, targetKind ?? "file")) {
        const key = assetKey(asset)
        if (seen.has(key)) continue
        seen.add(key)
        result.push(asset)
      }
    }
    return result
  }

  async function fetchTaskBatch(nextPageNo: number) {
    const token = options.getToken()
    if (!token) return { list: [] as TaskDetail[], hasNext: false }
    return fetchTasks({
      token,
      query: { pageNo: nextPageNo, pageSize: MATERIAL_TASK_FETCH_BATCH, status: "SUCCESS" },
    })
  }

  async function loadInitialAssets() {
    const targetKind = options.getKind()
    const seen = new Set<string>()
    let nextPageNo = 1
    let taskHasNext = true
    let rounds = 0
    const collected: T[] = []

    while (taskHasNext && rounds < MATERIAL_AUTO_FETCH_MAX_ROUNDS) {
      const page = await fetchTaskBatch(nextPageNo)
      loadedTasks.push(...page.list)
      collected.push(...extractAssets(page.list, targetKind, seen))
      taskHasNext = page.hasNext
      nextPageNo += 1
      rounds += 1
      if (collected.length >= PICKER_PAGE_SIZE) break
    }

    pageNo = nextPageNo - 1
    hasMore.value = taskHasNext
    return collected
  }

  async function resetAndLoad() {
    loadedTasks.length = 0
    pageNo = 1
    assets.value = []
    loading.value = true
    loadingMore.value = false
    error.value = ""
    hasMore.value = false
    const token = options.getToken()
    if (!token) {
      loading.value = false
      return
    }
    try {
      assets.value = await loadInitialAssets()
    } catch (err) {
      error.value = (err as Error).message || "素材加载失败"
      assets.value = []
    } finally {
      loading.value = false
    }
  }

  async function loadMore() {
    if (loading.value || loadingMore.value || !hasMore.value) return
    const token = options.getToken()
    if (!token) return
    const targetKind = options.getKind()
    loadingMore.value = true
    try {
      const seen = new Set(assets.value.map((item) => assetKey(item)))
      let nextPageNo = pageNo + 1
      let taskHasNext = true
      let rounds = 0
      const collected: T[] = []

      while (taskHasNext && rounds < MATERIAL_AUTO_FETCH_MAX_ROUNDS) {
        const page = await fetchTaskBatch(nextPageNo)
        loadedTasks.push(...page.list)
        collected.push(...extractAssets(page.list, targetKind, seen))
        taskHasNext = page.hasNext
        nextPageNo += 1
        rounds += 1
        if (collected.length > 0) break
      }

      pageNo = nextPageNo - 1
      hasMore.value = taskHasNext
      if (collected.length > 0) {
        assets.value = [...assets.value, ...collected]
      }
    } catch (err) {
      error.value = (err as Error).message || "素材加载失败"
    } finally {
      loadingMore.value = false
    }
  }

  return {
    assets,
    loading,
    loadingMore,
    hasMore,
    error,
    resetAndLoad,
    loadMore,
  }
}
