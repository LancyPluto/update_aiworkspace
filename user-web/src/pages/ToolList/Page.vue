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
  FileText,
  Music,
  Braces,
  Files,
  Sparkles,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { getApiOrigin } from "@/api/client"
import { fetchTools, searchTools } from "@/api/toolApi"
import type { ToolSummary } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const token = computed(() => auth.token)

const tools = ref<ToolSummary[]>([])
const total = ref(0)
const loading = ref(false)
const keyword = ref("")
const selectedModality = ref<string | undefined>(undefined)
const currentPage = ref(1)
const pageSize = 100

const modalityLabels: Record<string, string> = {
  TEXT: "文本生成",
  IMAGE: "图片生成",
  AUDIO: "音频生成",
  VIDEO: "视频生成",
  JSON: "结构化数据",
  FILE: "文件生成",
  MULTIMODAL: "多模态生成",
}

const modalityIconMap = {
  TEXT: FileText,
  IMAGE: ImageIcon,
  AUDIO: Music,
  VIDEO: Video,
  JSON: Braces,
  FILE: Files,
  MULTIMODAL: Sparkles,
}

// 图标映射
function getIcon(tool: ToolSummary) {
  const key = normalizeModality(tool.outputModality)
  return modalityIconMap[key as keyof typeof modalityIconMap] || Pencil
}

function normalizeModality(value?: string | null): string {
  return (value || "TEXT").trim().toUpperCase()
}

function modalityLabel(value?: string | null): string {
  const key = normalizeModality(value)
  return modalityLabels[key] || key
}

function normalizeToolMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function isVideoPreviewUrl(value?: string | null): boolean {
  const raw = value?.split(/[?#]/)[0]?.toLowerCase() || ""
  return [".mp4", ".webm", ".mov", ".m4v"].some((ext) => raw.endsWith(ext))
}

const modalityFilters = computed(() => {
  const counts = new Map<string, number>()
  for (const tool of tools.value) {
    const key = normalizeModality(tool.outputModality)
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  const order = ["TEXT", "IMAGE", "AUDIO", "VIDEO", "JSON", "FILE", "MULTIMODAL"]
  return [...counts.entries()]
    .sort(([a], [b]) => {
      const ia = order.indexOf(a)
      const ib = order.indexOf(b)
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
    })
    .map(([key, count]) => ({ key, label: modalityLabel(key), count }))
})

const filteredTools = computed(() => {
  if (!selectedModality.value) return tools.value
  return tools.value.filter((tool) => normalizeModality(tool.outputModality) === selectedModality.value)
})

const displayTotal = computed(() => filteredTools.value.length)

async function loadTools() {
  loading.value = true
  try {
    const query: Record<string, string | number | boolean | undefined> = {
      pageNo: currentPage.value,
      pageSize,
    }
    if (keyword.value) {
      // 有搜索关键词时使用搜索接口
      const res = await searchTools({
        token: token.value,
        query: { keyword: keyword.value, pageNo: currentPage.value, pageSize },
      })
      tools.value = res.list
      total.value = res.list.length
    } else {
      const res = await fetchTools({ token: token.value, query })
      tools.value = res.list
      total.value = res.list.length
    }
  } catch {
    tools.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function selectModality(modality?: string) {
  selectedModality.value = modality
  currentPage.value = 1
}

function doSearch() {
  currentPage.value = 1
  loadTools()
}

onMounted(() => {
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
            <Filter class="h-3 w-3" /> 生成类型
          </p>
          <ul class="space-y-1">
            <li>
              <button
                type="button"
                class="flex w-full items-center justify-between rounded-md px-2.5 py-1.5 text-sm transition-colors"
                :class="
                  selectedModality === undefined ? 'bg-accent text-accent-foreground font-medium' : 'text-foreground/80 hover:bg-secondary'
                "
                @click="selectModality(undefined)"
              >
                <span>全部工具</span>
                <span class="text-[11px] text-muted-foreground">{{ tools.length }}</span>
              </button>
            </li>
            <li v-for="item in modalityFilters" :key="item.key">
              <button
                type="button"
                class="flex w-full items-center justify-between rounded-md px-2.5 py-1.5 text-sm transition-colors"
                :class="
                  selectedModality === item.key ? 'bg-accent text-accent-foreground font-medium' : 'text-foreground/80 hover:bg-secondary'
                "
                @click="selectModality(item.key)"
              >
                <span>{{ item.label }}</span>
                <span class="text-[11px] text-muted-foreground">{{ item.count }}</span>
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
            <h3 class="text-sm font-semibold">{{ selectedModality ? modalityLabel(selectedModality) : '全部工具' }}</h3>
            <span class="rounded-md bg-secondary px-2 py-0.5 text-[11px] text-secondary-foreground">
              共 {{ displayTotal }} 个
            </span>
          </div>
        </div>

        <!-- 加载状态 -->
        <div v-if="loading" class="flex items-center justify-center py-12">
          <span class="text-sm text-muted-foreground">加载中…</span>
        </div>

        <!-- 空状态 -->
        <div v-else-if="filteredTools.length === 0" class="flex items-center justify-center py-12">
          <span class="text-sm text-muted-foreground">暂无可用工具</span>
        </div>

        <!-- 工具列表 -->
        <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="tool in filteredTools"
            :key="tool.id"
            class="group overflow-hidden rounded-xl border border-border bg-card transition hover:border-primary/40 hover:shadow-md"
          >
            <div v-if="tool.coverUrl" class="bg-muted">
              <video
                v-if="isVideoPreviewUrl(tool.coverUrl)"
                :src="normalizeToolMediaUrl(tool.coverUrl)"
                class="aspect-video w-full object-cover"
                muted
                loop
                playsinline
                autoplay
                preload="metadata"
              />
              <img
                v-else
                :src="normalizeToolMediaUrl(tool.coverUrl)"
                :alt="tool.toolName"
                class="aspect-video w-full object-cover"
              />
            </div>
            <div v-else class="flex aspect-video items-center justify-center bg-gradient-to-br from-accent to-primary/10 text-primary">
                <component :is="getIcon(tool)" class="h-10 w-10" />
            </div>
            <div class="p-5">
            <div class="flex items-start gap-3">
              <div
                class="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-secondary text-primary ring-1 ring-border"
              >
                <component :is="getIcon(tool)" class="h-5 w-5" />
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
                  {{ modalityLabel(tool.outputModality) }}
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
    </div>
  </AppShell>
</template>
