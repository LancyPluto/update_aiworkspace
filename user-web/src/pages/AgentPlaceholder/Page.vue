<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { ArrowRight, Bot, Coins, Loader2, RefreshCw, Search, Workflow } from "lucide-vue-next"
import { listTools, type WorkflowToolPage, type WorkflowToolSummary } from "@/api/workflowApi"
import { userRoutes } from "@/router/userRoutes"

const tools = ref<WorkflowToolSummary[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const keyword = ref("")
let loadController: AbortController | null = null

const filteredTools = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  if (!query) return tools.value
  return tools.value.filter((tool) =>
    [tool.toolName, tool.toolCode, tool.description, tool.categoryName]
      .some((value) => value?.toLocaleLowerCase().includes(query)),
  )
})

function toolRoute(tool: WorkflowToolSummary) {
  return tool.toolCode === "ai_comic_drama_agent"
    ? userRoutes.comicProjects
    : userRoutes.workflowTool(tool.toolCode)
}

function normalizeTools(result: WorkflowToolPage): WorkflowToolSummary[] {
  if (Array.isArray(result)) return result
  const compatible = result as WorkflowToolPage & { records?: WorkflowToolSummary[]; content?: WorkflowToolSummary[] }
  return compatible.items ?? compatible.records ?? compatible.content ?? []
}

function costLabel(tool: WorkflowToolSummary): string {
  const cost = tool.minimumRequiredCredits ?? tool.estimatedCreditCost
  return cost == null ? "按步骤计费" : `${cost} 算力起`
}

async function loadTools() {
  loadController?.abort()
  loadController = new AbortController()
  loading.value = true
  error.value = null
  try {
    const result = await listTools({ query: { page: 1, pageSize: 100 }, signal: loadController.signal })
    tools.value = normalizeTools(result)
  } catch (loadError) {
    if (loadController.signal.aborted) return
    error.value = loadError instanceof Error ? loadError.message : "工作流工具暂时不可用"
    tools.value = []
  } finally {
    if (!loadController.signal.aborted) loading.value = false
  }
}

onMounted(loadTools)
onBeforeUnmount(() => loadController?.abort())
</script>

<template>
  <main class="mx-auto w-full max-w-6xl px-5 py-7 sm:px-7 lg:py-10">
    <header class="flex flex-col gap-5 border-b border-border pb-6 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <div class="mb-2 flex items-center gap-2 text-sm font-medium text-primary">
          <Workflow class="h-4 w-4" />
          工作流工具
        </div>
        <h1 class="text-2xl font-semibold text-foreground">工具中心</h1>
        <p class="mt-2 text-sm text-muted-foreground">选择工具，填写输入并跟踪每一步运行结果。</p>
      </div>
      <label class="relative block w-full sm:w-72">
        <Search class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <input
          v-model="keyword"
          type="search"
          placeholder="搜索工具"
          class="h-10 w-full rounded-md border border-input bg-background pl-9 pr-3 text-sm outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
        />
      </label>
    </header>

    <div v-if="loading" class="flex min-h-64 items-center justify-center text-sm text-muted-foreground">
      <Loader2 class="mr-2 h-4 w-4 animate-spin" />
      正在加载工具
    </div>

    <section v-else-if="error" class="flex min-h-64 flex-col items-center justify-center text-center">
      <Bot class="h-8 w-8 text-muted-foreground" />
      <h2 class="mt-4 text-base font-semibold">无法加载工作流工具</h2>
      <p class="mt-2 max-w-md text-sm text-muted-foreground">{{ error }}</p>
      <button
        type="button"
        class="mt-5 inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary"
        @click="loadTools"
      >
        <RefreshCw class="h-4 w-4" />
        重新加载
      </button>
    </section>

    <section v-else-if="filteredTools.length" class="grid gap-4 py-6 md:grid-cols-2 xl:grid-cols-3">
      <RouterLink
        v-for="tool in filteredTools"
        :key="tool.toolCode"
        :to="toolRoute(tool)"
        class="group flex min-h-44 flex-col rounded-lg border border-border bg-card p-5 transition hover:border-primary/50 hover:bg-secondary/30"
      >
        <div class="flex items-start justify-between gap-3">
          <div class="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
            <Workflow class="h-5 w-5" />
          </div>
          <span class="inline-flex items-center gap-1 text-xs text-muted-foreground">
            <Coins class="h-3.5 w-3.5" />
            {{ costLabel(tool) }}
          </span>
        </div>
        <h2 class="mt-4 text-base font-semibold text-foreground">{{ tool.toolName }}</h2>
        <p class="mt-1 line-clamp-2 text-sm leading-6 text-muted-foreground">
          {{ tool.description || "打开工具查看输入项与运行信息。" }}
        </p>
        <div class="mt-auto flex items-center justify-between pt-4 text-xs text-muted-foreground">
          <span>{{ tool.categoryName || "工作流" }}</span>
          <ArrowRight class="h-4 w-4 transition-transform group-hover:translate-x-0.5 group-hover:text-primary" />
        </div>
      </RouterLink>
    </section>

    <section v-else class="flex min-h-64 flex-col items-center justify-center text-center">
      <Workflow class="h-8 w-8 text-muted-foreground" />
      <h2 class="mt-4 text-base font-semibold">{{ keyword ? "没有匹配的工具" : "暂无可用工作流工具" }}</h2>
      <p class="mt-2 text-sm text-muted-foreground">{{ keyword ? "试试其他关键词。" : "工具上线后会显示在这里。" }}</p>
    </section>
  </main>
</template>
