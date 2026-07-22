import { ref, watch } from "vue"
import type { RouteLocationRaw } from "vue-router"
import { searchTools } from "@/api/toolApi"
import { searchCommunityPosts } from "@/api/communityApi"
import { fetchTasks } from "@/api/taskApi"
import type { CommunityPost, ToolSummary } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { communityDisplayTitle } from "@/utils/communityDisplay"
import { taskPrompt, taskPromptPreview } from "@/utils/assetPreviewAdapter"

export type GlobalSearchScope = "all" | "models" | "agents" | "materials"

export type GlobalSearchResultItem = {
  id: string
  kind: "model" | "agent" | "material" | "community"
  title: string
  subtitle?: string
  href: RouteLocationRaw
  coverUrl?: string | null
}

const scopeLabels: Record<GlobalSearchScope, string> = {
  all: "全部",
  models: "模型",
  agents: "智能体",
  materials: "素材",
}

export function isAgentTool(tool: Pick<ToolSummary, "toolCode" | "toolType" | "toolKind" | "categoryCode">): boolean {
  const type = (tool.toolType || "").toUpperCase()
  const kind = (tool.toolKind || "").toLowerCase()
  const category = (tool.categoryCode || "").toLowerCase()
  if (type === "AGENT" || kind === "agent" || category === "agent") return true
  const code = (tool.toolCode || "").toLowerCase()
  return code.includes("agent")
}

function toolHref(tool: ToolSummary, loggedIn: boolean): RouteLocationRaw {
  if (loggedIn) {
    return { path: "/dashboard", query: { tool: tool.toolCode } }
  }
  return { path: `/tools/${encodeURIComponent(tool.toolCode)}` }
}

function matchesTaskKeyword(task: { toolName?: string | null; taskNo?: string | null; params?: Record<string, unknown> | null }, keyword: string) {
  const prompt = taskPrompt(task as Parameters<typeof taskPrompt>[0])
  const haystack = `${task.toolName || ""} ${task.taskNo || ""} ${prompt}`.toLowerCase()
  return haystack.includes(keyword.toLowerCase())
}

function communityResultItem(post: CommunityPost): GlobalSearchResultItem {
  return {
    id: `community-${post.id}`,
    kind: "community",
    title: communityDisplayTitle({
      title: post.title,
      topic: post.topic,
      tags: post.tags,
      toolName: post.toolName,
      toolCode: post.toolCode,
      modality: post.modality,
      promptVisible: post.promptVisible,
    }),
    subtitle: post.toolName || post.topic || "社区作品",
    href: { path: `/community/posts/${post.id}` },
    coverUrl: post.coverUrl,
  }
}

export function useGlobalSearch() {
  const auth = useAuthStore()
  const keyword = ref("")
  const scope = ref<GlobalSearchScope>("all")
  const panelOpen = ref(false)
  const loading = ref(false)
  const error = ref("")
  const results = ref<GlobalSearchResultItem[]>([])

  let debounceTimer: ReturnType<typeof setTimeout> | null = null
  let requestSeq = 0

  function clearDebounce() {
    if (debounceTimer) {
      clearTimeout(debounceTimer)
      debounceTimer = null
    }
  }

  function closePanel() {
    panelOpen.value = false
  }

  function openPanel() {
    panelOpen.value = true
  }

  async function runSearch(rawKeyword?: string) {
    const q = (rawKeyword ?? keyword.value).trim()
    if (!q) {
      results.value = []
      error.value = ""
      loading.value = false
      return
    }

    const currentSeq = ++requestSeq
    loading.value = true
    error.value = ""

    const items: GlobalSearchResultItem[] = []
    const includeModels = scope.value === "all" || scope.value === "models"
    const includeAgents = scope.value === "all" || scope.value === "agents"
    const includeMaterials = scope.value === "all" || scope.value === "materials"

    try {
      const jobs: Promise<void>[] = []

      if (includeModels || includeAgents) {
        jobs.push(
          searchTools({
            token: auth.token,
            query: { keyword: q, pageNo: 1, pageSize: 12 },
          }).then((page) => {
            for (const tool of page.list) {
              const agent = isAgentTool(tool)
              if (agent && !includeAgents) continue
              if (!agent && !includeModels) continue
              items.push({
                id: `tool-${tool.toolCode}`,
                kind: agent ? "agent" : "model",
                title: tool.toolName,
                subtitle: tool.description?.trim() || tool.categoryName || undefined,
                href: toolHref(tool, auth.isLoggedIn),
                coverUrl: tool.coverUrl ?? undefined,
              })
            }
          }),
        )
      }

      if (includeMaterials && auth.isLoggedIn) {
        jobs.push(
          fetchTasks({
            token: auth.token,
            query: { pageNo: 1, pageSize: 40, status: "SUCCESS" },
          }).then((page) => {
            for (const task of page.list) {
              if (!task.result?.contentText) continue
              if (!matchesTaskKeyword(task, q)) continue
              items.push({
                id: `task-${task.taskId}`,
                kind: "material",
                title: taskPromptPreview(task, 56) || task.toolName,
                subtitle: task.toolName,
                href: userRoutes.taskResult(String(task.taskId)),
              })
            }
          }),
        )
      }

      if (includeMaterials) {
        jobs.push(
          searchCommunityPosts({
            token: auth.token,
            query: { keyword: q, pageNo: 1, pageSize: 6, sort: "LATEST" },
          })
            .then((page) => {
              for (const post of page.list) {
                items.push(communityResultItem(post))
              }
            })
            .catch(() => {
              // 社区搜索不可用时静默跳过
            }),
        )
      }

      await Promise.all(jobs)

      if (currentSeq !== requestSeq) return

      const deduped = new Map<string, GlobalSearchResultItem>()
      for (const item of items) {
        deduped.set(item.id, item)
      }
      results.value = [...deduped.values()].slice(0, 12)
    } catch (e) {
      if (currentSeq !== requestSeq) return
      results.value = []
      error.value = e instanceof Error ? e.message : "搜索失败，请稍后重试"
    } finally {
      if (currentSeq === requestSeq) loading.value = false
    }
  }

  function scheduleSearch() {
    clearDebounce()
    debounceTimer = setTimeout(() => {
      void runSearch()
    }, 280)
  }

  watch(keyword, (value) => {
    if (!value.trim()) {
      results.value = []
      error.value = ""
      loading.value = false
      clearDebounce()
      return
    }
    if (panelOpen.value) scheduleSearch()
  })

  watch(scope, () => {
    if (keyword.value.trim() && panelOpen.value) scheduleSearch()
  })

  function submitSearch() {
    const q = keyword.value.trim()
    if (!q) return
    panelOpen.value = true
    void runSearch(q)
  }

  function setScope(next: GlobalSearchScope) {
    scope.value = next
  }

  return {
    keyword,
    scope,
    scopeLabels,
    panelOpen,
    loading,
    error,
    results,
    openPanel,
    closePanel,
    submitSearch,
    setScope,
    runSearch,
    clearDebounce,
  }
}
