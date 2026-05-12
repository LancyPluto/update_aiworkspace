<script setup lang="ts">
import { ref, onMounted, computed } from "vue"
import { RouterLink } from "vue-router"
import {
  Search,
  Filter,
  Star,
  Zap,
  TrendingUp,
  Pencil,
  Megaphone,
  Image as ImageIcon,
  Video,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchToolCategories, fetchTools, searchTools } from "@/api/toolApi"
import type { ToolCategory, ToolSummary } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const token = computed(() => auth.token)

const categories = ref<ToolCategory[]>([])
const tools = ref<ToolSummary[]>([])
const total = ref(0)
const loading = ref(false)
const keyword = ref("")
const selectedCategoryId = ref<number | undefined>(undefined)
const currentPage = ref(1)
const pageSize = 20

// 图标映射
function getIcon(_name: string) {
  // 简单轮转图标
  const icons = [Pencil, Megaphone, ImageIcon, Video]
  return icons[Math.floor(Math.random() * icons.length)]
}

async function loadCategories() {
  try {
    categories.value = await fetchToolCategories({ token: token.value })
  } catch {
    // 静默处理
  }
}

async function loadTools() {
  loading.value = true
  try {
    const query: Record<string, string | number | boolean | undefined> = {
      pageNo: currentPage.value,
      pageSize,
    }
    if (selectedCategoryId.value) {
      query.categoryId = selectedCategoryId.value
    }
    if (keyword.value) {
      // 有搜索关键词时使用搜索接口
      const res = await searchTools({
        token: token.value,
        query: { keyword: keyword.value, pageNo: currentPage.value, pageSize },
      })
      tools.value = res.list
      total.value = res.total
    } else {
      const res = await fetchTools({ token: token.value, query })
      tools.value = res.list
      total.value = res.total
    }
  } catch {
    tools.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function selectCategory(categoryId?: number) {
  selectedCategoryId.value = categoryId
  currentPage.value = 1
  loadTools()
}

function doSearch() {
  currentPage.value = 1
  loadTools()
}

onMounted(() => {
  loadCategories()
  loadTools()
})
</script>

<template>
  <AppShell title="AI 工具超市" description="企业级 AI 工具，覆盖电商、内容、客服、运营全链路">
    <div class="flex flex-col xl:flex-row">
      <!-- 分类侧栏 -->
      <div class="hidden xl:block w-56 shrink-0 border-r border-border bg-card/40 p-5 space-y-6">
        <div>
          <p class="mb-3 flex items-center gap-2 text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <Filter class="h-3 w-3" /> 分类
          </p>
          <ul class="space-y-1">
            <li>
              <button
                type="button"
                class="flex w-full items-center justify-between rounded-md px-2.5 py-1.5 text-sm transition-colors"
                :class="
                  selectedCategoryId === undefined ? 'bg-accent text-accent-foreground font-medium' : 'text-foreground/80 hover:bg-secondary'
                "
                @click="selectCategory(undefined)"
              >
                <span>全部工具</span>
                <span class="text-[11px] text-muted-foreground">{{ total }}</span>
              </button>
            </li>
            <li v-for="c in categories" :key="c.id">
              <button
                type="button"
                class="flex w-full items-center justify-between rounded-md px-2.5 py-1.5 text-sm transition-colors"
                :class="
                  selectedCategoryId === c.id ? 'bg-accent text-accent-foreground font-medium' : 'text-foreground/80 hover:bg-secondary'
                "
                @click="selectCategory(c.id)"
              >
                <span>{{ c.categoryName }}</span>
              </button>
            </li>
          </ul>
        </div>
      </div>

      <!-- 主内容区 -->
      <div class="flex-1 px-6 py-6 space-y-6 min-w-0">
        <div class="space-y-4">
          <div class="relative max-w-2xl">
            <Search
              class="absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground pointer-events-none"
            />
            <input
              v-model="keyword"
              class="flex h-12 w-full rounded-lg border border-border bg-card pl-11 pr-32 text-sm shadow-sm"
              placeholder="搜索工具名称、场景、关键词…"
              @keyup.enter="doSearch"
            />
            <button
              type="button"
              class="absolute right-1.5 top-1.5 h-9 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground"
              @click="doSearch"
            >
              搜索
            </button>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <span class="inline-flex items-center gap-1 text-xs text-muted-foreground">
              <TrendingUp class="h-3 w-3 text-destructive" /> 热门搜索：
            </span>
            <button
              v-for="t in ['618 大促', '小红书爆款', '短视频脚本', '商品主图']"
              :key="t"
              type="button"
              class="rounded-full border px-2.5 py-1 text-xs font-medium transition"
              :class="
                'border-border bg-card text-foreground/80 hover:border-primary/40'
              "
              @click="keyword = t; doSearch()"
            >
              {{ t }}
            </button>
          </div>
        </div>

        <div class="flex items-center justify-between border-b border-border pb-3">
          <div class="flex items-center gap-2">
            <h3 class="text-sm font-semibold">全部工具</h3>
            <span class="rounded-md bg-secondary px-2 py-0.5 text-[11px] text-secondary-foreground">
              共 {{ total }} 个
            </span>
          </div>
        </div>

        <!-- 加载状态 -->
        <div v-if="loading" class="flex items-center justify-center py-12">
          <span class="text-sm text-muted-foreground">加载中…</span>
        </div>

        <!-- 空状态 -->
        <div v-else-if="tools.length === 0" class="flex items-center justify-center py-12">
          <span class="text-sm text-muted-foreground">暂无可用工具</span>
        </div>

        <!-- 工具列表 -->
        <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="tool in tools"
            :key="tool.id"
            class="group rounded-xl border border-border bg-card p-5 transition hover:border-primary/40 hover:shadow-md"
          >
            <div class="flex items-start gap-3">
              <div
                class="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-accent to-primary/10 text-primary"
              >
                <component :is="getIcon(tool.toolName)" class="h-5 w-5" />
              </div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <h4 class="text-sm font-semibold truncate">{{ tool.toolName }}</h4>
                  <span
                    v-if="tool.estimatedCreditCost > 50"
                    class="shrink-0 rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium text-destructive"
                  >
                    HOT
                  </span>
                </div>
                <p class="mt-0.5 inline-block rounded bg-secondary px-1.5 py-0.5 text-[10px] text-muted-foreground">
                  {{ tool.categoryName }}
                </p>
              </div>
            </div>
            <p class="mt-3 text-xs text-muted-foreground line-clamp-2 min-h-[32px]">{{ tool.description || '暂无描述' }}</p>
            <div class="mt-4 flex items-center justify-between text-[11px] text-muted-foreground">
              <span class="inline-flex items-center gap-1">
                <Star class="h-3 w-3 fill-warning text-warning" /> 4.8
              </span>
            </div>
            <div class="mt-3 flex items-center justify-between border-t border-border pt-3">
              <span class="inline-flex items-center gap-1 text-xs font-medium">
                <Zap class="h-3.5 w-3.5 text-warning" /> {{ tool.estimatedCreditCost }}
                <span class="text-muted-foreground font-normal">算力 / 次</span>
              </span>
              <RouterLink
                :to="'/tools/' + tool.toolCode"
                class="inline-flex h-7 items-center rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:opacity-90"
              >
                立即使用
              </RouterLink>
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>
