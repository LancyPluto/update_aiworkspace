<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { useRoute } from "vue-router"
import { ImageIcon, RefreshCw, Search, Video, Workflow } from "lucide-vue-next"
import WorkspaceShell from "@/components/workspace/WorkspaceShell.vue"
import WorkspaceHeading from "@/components/workspace/WorkspaceHeading.vue"
import WorkspaceToolCard from "@/components/workspace/WorkspaceToolCard.vue"
import { fetchToolCategories, fetchTools, searchTools } from "@/api/toolApi"
import type { ToolSummary } from "@/api/types"
import { toWorkspaceToolCards, type ToolModeFilter } from "@/adapters/toolPresentationAdapter"
import { useAuthStore } from "@/store/authStore"
import { cleanToolDisplayText } from "@/utils/toolDisplayText"
import "@/styles/workspace.css"

const props = withDefaults(defineProps<{
  mode?: ToolModeFilter
  title?: string
  description?: string
}>(), {
  mode: "all",
  title: "AI 工具中心",
  description: "",
})

const auth = useAuthStore()
const route = useRoute()
const tools = ref<ToolSummary[]>([])
const categories = ref<{ id: number; categoryName: string }[]>([])
const loading = ref(false)
const error = ref("")
const tab = ref<ToolModeFilter>(props.mode)
const searchKeyword = ref("")
const selectedCategoryId = ref<number | "">("")
const pageNo = ref(1)
const pageSize = 120
const total = ref(0)

const cards = computed(() => toWorkspaceToolCards(tools.value, tab.value))
const visibleTotal = computed(() => cards.value.length)
const hasNext = computed(() => pageNo.value * pageSize < total.value)

function keywordFromRoute() {
  const value = route.query.keyword
  if (Array.isArray(value)) return value[0] ?? ""
  return typeof value === "string" ? value : ""
}

async function loadTools() {
  loading.value = true
  error.value = ""
  try {
    const keyword = searchKeyword.value.trim()
    const query = {
      pageNo: pageNo.value,
      pageSize,
      keyword: keyword || undefined,
      categoryId: selectedCategoryId.value || undefined,
    }
    const page = keyword
      ? await searchTools({ token: auth.token, query })
      : await fetchTools({ token: auth.token, query })
    tools.value = page.list
    total.value = page.total
  } catch (e) {
    error.value = (e as Error).message || "工具加载失败"
  } finally {
    loading.value = false
  }
}

function resetAndLoad() {
  pageNo.value = 1
  void loadTools()
}

function changePage(nextPage: number) {
  pageNo.value = Math.max(1, nextPage)
  void loadTools()
}

watch(tab, () => {
  pageNo.value = 1
})

watch(
  () => route.query.keyword,
  () => {
    searchKeyword.value = keywordFromRoute()
    resetAndLoad()
  },
)

onMounted(async () => {
  searchKeyword.value = keywordFromRoute()
  try {
    categories.value = await fetchToolCategories({ token: auth.token })
  } catch {
    categories.value = []
  }
  await loadTools()
})
</script>

<template>
  <WorkspaceShell>
    <WorkspaceHeading :title="title" :description="description">
      <template #actions>
        <button class="workspace-pink-button" type="button" @click="loadTools">
          <RefreshCw :size="15" />刷新
        </button>
      </template>
    </WorkspaceHeading>

    <div class="workspace-tool-tabs">
      <button :class="{ active: tab === 'video' }" type="button" @click="tab = 'video'"><Video :size="16" />视频工具</button>
      <button :class="{ active: tab === 'image' }" type="button" @click="tab = 'image'"><ImageIcon :size="16" />图像工具</button>
      <button :class="{ active: tab === 'workflow' }" type="button" @click="tab = 'workflow'"><Workflow :size="16" />工作流</button>
      <button :class="{ active: tab === 'all' }" type="button" @click="tab = 'all'">全部工具</button>
    </div>

    <form class="workspace-tool-filters" @submit.prevent="resetAndLoad">
      <label class="workspace-search-field">
        <Search :size="16" />
        <input v-model="searchKeyword" type="search" placeholder="搜索工具、模型或场景" />
      </label>
      <select v-model="selectedCategoryId" class="workspace-filter-select" @change="resetAndLoad">
        <option value="">全部分类</option>
        <option v-for="category in categories" :key="category.id" :value="category.id">{{ cleanToolDisplayText(category.categoryName) }}</option>
      </select>
      <button class="workspace-pink-button" type="submit">搜索</button>
    </form>

    <div v-if="loading" class="workspace-state">正在加载真实工具列表...</div>
    <div v-else-if="error" class="workspace-state">
      <div>
        <p>{{ error }}</p>
        <button class="workspace-pink-button" type="button" @click="loadTools">重试</button>
      </div>
    </div>
    <div v-else-if="cards.length === 0" class="workspace-state">当前分类暂无可用工具</div>
    <template v-else>
      <div class="workspace-official-tool-grid">
        <WorkspaceToolCard v-for="tool in cards" :key="tool.id" :tool="tool" />
      </div>
      <div class="workspace-pagination">
        <button type="button" :disabled="pageNo <= 1 || loading" @click="changePage(pageNo - 1)">上一页</button>
        <span>第 {{ pageNo }} 页 · 共 {{ visibleTotal }} 个工具</span>
        <button type="button" :disabled="!hasNext || loading" @click="changePage(pageNo + 1)">下一页</button>
      </div>
    </template>
  </WorkspaceShell>
</template>
