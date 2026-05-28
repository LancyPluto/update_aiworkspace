<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink, useRoute } from "vue-router"
import { Sparkles } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import CreditCostBadge from "@/components/CreditCostBadge/CreditCostBadge.vue"
import { getApiOrigin } from "@/api/client"
import { fetchEnabledAITools } from "@/api/aiToolApi"
import { fetchTasks } from "@/api/taskApi"
import type { AITool } from "@/api/aiToolTypes"
import type { TaskDetail } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { isPptWorkspaceTool } from "@/api/pptApi"
import { resolveModelBrand } from "@/utils/modelBrand"
import { toolEntryRoute } from "@/utils/toolEntryRoute"

const auth = useAuthStore()
const route = useRoute()
const tools = ref<AITool[]>([])
const tasks = ref<TaskDetail[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const selectedOutputModality = ref<string | undefined>(undefined)
const offlineNotice = computed(() => route.query.notice === "offline")

const modalityLabels: Record<string, string> = {
  TEXT: "文本",
  IMAGE: "图片",
  AUDIO: "音频",
  VIDEO: "视频",
  JSON: "结构化",
  FILE: "文件",
  MULTIMODAL: "多模态",
}

const modalityOrder = ["TEXT", "IMAGE", "AUDIO", "VIDEO", "JSON", "FILE", "MULTIMODAL"]

function normalizeModality(value?: string | null): string {
  return (value || "TEXT").trim().toUpperCase()
}

function modalityLabel(value?: string | null): string {
  const key = normalizeModality(value)
  return modalityLabels[key] || key
}

function normalizeMediaUrl(value?: string | null): string {
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

function usesEffectMedia(tool: AITool): boolean {
  return tool.mediaDisplayMode === "effect" && Boolean(tool.iconUrl)
}

function modelBrand(tool: AITool) {
  return resolveModelBrand(tool)
}

const sortedTools = computed(() => [...tools.value].sort((a, b) => a.order - b.order))

const outputFilters = computed(() => {
  const counts = new Map<string, number>()
  for (const tool of tools.value) {
    const key = normalizeModality(tool.outputModality)
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  return [...counts.entries()]
    .sort(([a], [b]) => {
      const ia = modalityOrder.indexOf(a)
      const ib = modalityOrder.indexOf(b)
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
    })
    .map(([key, count]) => ({ key, label: modalityLabel(key), count }))
})

const filteredTools = computed(() => {
  if (!selectedOutputModality.value) return sortedTools.value
  return sortedTools.value.filter((tool) => normalizeModality(tool.outputModality) === selectedOutputModality.value)
})

const toolsById = computed(() => new Map(tools.value.map((tool) => [tool.id, tool])))

const usageCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const task of tasks.value) {
    if (!task.toolCode) continue
    counts.set(task.toolCode, (counts.get(task.toolCode) || 0) + 1)
  }
  return counts
})

const recentUsedTools = computed(() => {
  const seen = new Set<string>()
  const list: AITool[] = []
  for (const task of tasks.value) {
    const tool = toolsById.value.get(task.toolCode)
    if (!tool || seen.has(tool.id)) continue
    seen.add(tool.id)
    list.push(tool)
    if (list.length >= 5) break
  }
  if (list.length < 5) {
    for (const tool of sortedTools.value) {
      if (seen.has(tool.id)) continue
      list.push(tool)
      if (list.length >= 5) break
    }
  }
  return list
})

function topToolForModality(modality: string): AITool | null {
  const key = normalizeModality(modality)
  const candidates = tools.value.filter((tool) => normalizeModality(tool.outputModality) === key)
  if (candidates.length === 0) return null
  return [...candidates].sort((a, b) => {
    const used = (usageCounts.value.get(b.id) || 0) - (usageCounts.value.get(a.id) || 0)
    return used || a.order - b.order
  })[0]
}

async function loadTools() {
  loading.value = true
  error.value = null
  try {
    const [toolRes, taskRes] = await Promise.all([
      fetchEnabledAITools({ token: auth.token }),
      fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 80 } }).catch(() => null),
    ])
    tools.value = toolRes
    tasks.value = taskRes?.list || []
  } catch (e) {
    error.value = (e as Error).message || "加载失败"
    tools.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadTools()
})
</script>

<template>
  <AppShell title="AI 工具市场" description="发现模型、智能体和内容生成工具">
    <div class="mx-auto w-full max-w-[1540px] px-5 py-7">
      <div
        v-if="offlineNotice"
        class="mb-4 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-100"
      >
        该工具已不可用，请选择其他工具
      </div>

      <section class="mb-8 grid gap-4 xl:grid-cols-[1fr_1fr_1fr_1.5fr]">
        <RouterLink
          :to="{ path: '/dashboard', query: { modality: 'IMAGE' } }"
          class="group overflow-hidden rounded-3xl border border-white/8 bg-white/[0.05] p-6 transition hover:-translate-y-1 hover:border-primary/50"
        >
          <h2 class="text-xl font-semibold">图像生成</h2>
          <p class="mt-2 text-sm text-white/50">智能系统，即时开发</p>
          <div class="relative mt-8 h-24 overflow-hidden rounded-2xl bg-[linear-gradient(135deg,rgb(255_255_255_/_0.12),rgb(176_92_255_/_0.22))] transition group-hover:brightness-125">
            <template v-if="topToolForModality('IMAGE')?.iconUrl">
              <video
                v-if="isVideoPreviewUrl(topToolForModality('IMAGE')?.iconUrl)"
                :src="normalizeMediaUrl(topToolForModality('IMAGE')?.iconUrl)"
                class="h-full w-full object-cover"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
              />
              <img
                v-else
                :src="normalizeMediaUrl(topToolForModality('IMAGE')?.iconUrl)"
                :alt="topToolForModality('IMAGE')?.name"
                class="h-full w-full object-cover"
              />
              <div class="absolute inset-0 bg-gradient-to-t from-black/55 to-transparent" />
              <span class="absolute bottom-3 left-3 text-sm font-semibold text-white">{{ topToolForModality('IMAGE')?.name }}</span>
            </template>
          </div>
        </RouterLink>
        <RouterLink
          :to="{ path: '/dashboard', query: { modality: 'VIDEO' } }"
          class="group overflow-hidden rounded-3xl border border-white/8 bg-white/[0.05] p-6 transition hover:-translate-y-1 hover:border-primary/50"
        >
          <h2 class="text-xl font-semibold">视频创作</h2>
          <p class="mt-2 text-sm text-white/50">图像、关键一代</p>
          <div class="relative mt-8 h-24 overflow-hidden rounded-2xl bg-[linear-gradient(135deg,rgb(70_170_255_/_0.22),rgb(255_255_255_/_0.1))] transition group-hover:brightness-125">
            <template v-if="topToolForModality('VIDEO')?.iconUrl">
              <video
                v-if="isVideoPreviewUrl(topToolForModality('VIDEO')?.iconUrl)"
                :src="normalizeMediaUrl(topToolForModality('VIDEO')?.iconUrl)"
                class="h-full w-full object-cover"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
              />
              <img
                v-else
                :src="normalizeMediaUrl(topToolForModality('VIDEO')?.iconUrl)"
                :alt="topToolForModality('VIDEO')?.name"
                class="h-full w-full object-cover"
              />
              <div class="absolute inset-0 bg-gradient-to-t from-black/55 to-transparent" />
              <span class="absolute bottom-3 left-3 text-sm font-semibold text-white">{{ topToolForModality('VIDEO')?.name }}</span>
            </template>
          </div>
        </RouterLink>
        <RouterLink
          :to="userRoutes.agent"
          class="group overflow-hidden rounded-3xl border border-white/8 bg-white/[0.05] p-6 transition hover:-translate-y-1 hover:border-primary/50"
        >
          <h2 class="text-xl font-semibold">人工智能</h2>
          <p class="mt-2 text-sm text-white/50">百步疾驰，瞬间抵达</p>
          <div class="relative mt-8 h-24 overflow-hidden rounded-2xl bg-[linear-gradient(135deg,rgb(255_193_7_/_0.22),rgb(176_92_255_/_0.18))] transition group-hover:brightness-125">
            <template v-if="topToolForModality('MULTIMODAL')?.iconUrl || topToolForModality('TEXT')?.iconUrl">
              <img
                :src="normalizeMediaUrl((topToolForModality('MULTIMODAL') || topToolForModality('TEXT'))?.iconUrl)"
                :alt="(topToolForModality('MULTIMODAL') || topToolForModality('TEXT'))?.name"
                class="h-full w-full object-cover"
              />
              <div class="absolute inset-0 bg-gradient-to-t from-black/55 to-transparent" />
              <span class="absolute bottom-3 left-3 text-sm font-semibold text-white">
                {{ (topToolForModality('MULTIMODAL') || topToolForModality('TEXT'))?.name }}
              </span>
            </template>
          </div>
        </RouterLink>
        <div class="rounded-3xl border border-white/8 bg-white/[0.04] p-5">
          <div class="flex items-center justify-between">
            <h2 class="text-base font-semibold">最近使用过</h2>
            <span class="text-xs text-white/35">Recent</span>
          </div>
          <div class="mt-4 flex gap-3 overflow-hidden">
            <RouterLink
              v-for="tool in recentUsedTools"
              :key="tool.id"
              :to="toolEntryRoute(tool.id)"
              class="h-28 w-20 shrink-0 overflow-hidden rounded-2xl bg-secondary"
            >
              <video
                v-if="tool.iconUrl && isVideoPreviewUrl(tool.iconUrl)"
                :src="normalizeMediaUrl(tool.iconUrl)"
                class="h-full w-full object-cover"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
              />
              <img
                v-else-if="tool.iconUrl"
                :src="normalizeMediaUrl(tool.iconUrl)"
                :alt="tool.name"
                class="h-full w-full object-cover"
              />
              <div v-else class="flex h-full w-full items-center justify-center text-primary">
                <Sparkles class="h-6 w-6" />
              </div>
            </RouterLink>
          </div>
        </div>
      </section>

      <h2 class="mb-4 text-xl font-semibold">一个工作站类型的 AI 生产公司区</h2>

      <div class="mb-6 flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="rounded-full border px-4 py-2 text-sm font-medium transition"
          :class="
            selectedOutputModality === undefined
              ? 'border-primary bg-primary text-white'
              : 'border-white/10 bg-white/[0.05] text-white/70 hover:border-primary/50 hover:text-white'
          "
          @click="selectedOutputModality = undefined"
        >
          全部
          <span class="ml-1 opacity-70">{{ tools.length }}</span>
        </button>
        <button
          v-for="item in outputFilters"
          :key="item.key"
          type="button"
          class="rounded-full border px-4 py-2 text-sm font-medium transition"
          :class="
            selectedOutputModality === item.key
              ? 'border-primary bg-primary text-white'
              : 'border-white/10 bg-white/[0.05] text-white/70 hover:border-primary/50 hover:text-white'
          "
          @click="selectedOutputModality = item.key"
        >
          {{ item.label }}
          <span class="ml-1 opacity-70">{{ item.count }}</span>
        </button>
      </div>

      <div v-if="loading" class="flex items-center justify-center py-20">
        <span class="text-sm text-muted-foreground">加载中…</span>
      </div>

      <div v-else-if="error" class="mx-auto max-w-md rounded-2xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
        <button
          type="button"
          class="mt-4 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
          @click="loadTools"
        >
          重试
        </button>
      </div>

      <div v-else-if="filteredTools.length === 0" class="flex flex-col items-center justify-center py-20 text-center">
        <Sparkles class="mb-4 h-12 w-12 text-muted-foreground/50" />
        <p class="text-sm text-muted-foreground">暂无可用工具，请联系管理员</p>
      </div>

      <div v-else class="grid gap-6 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-5">
        <RouterLink
          v-for="tool in filteredTools"
          :key="tool.id"
          :to="toolEntryRoute(tool.id)"
          class="group overflow-hidden rounded-3xl border border-white/8 bg-white/[0.04] transition hover:-translate-y-1 hover:border-primary/50 hover:shadow-[0_20px_45px_rgb(0_0_0_/_0.38)]"
        >
          <div v-if="usesEffectMedia(tool)" class="flex h-full flex-col">
            <div class="relative aspect-[3/4] overflow-hidden bg-muted">
              <video
                v-if="isVideoPreviewUrl(tool.iconUrl)"
                :src="normalizeMediaUrl(tool.iconUrl)"
                class="h-full w-full object-cover transition duration-500 group-hover:scale-[1.03]"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
              />
              <img
                v-else
                :src="normalizeMediaUrl(tool.iconUrl)"
                :alt="tool.name"
                class="h-full w-full object-cover transition duration-500 group-hover:scale-[1.03]"
              />
              <div class="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/85 via-black/10 to-transparent" />
              <div class="absolute bottom-4 left-4 right-4 flex items-end justify-between gap-3">
                <div class="min-w-0">
                  <h3 class="truncate text-2xl font-semibold text-white drop-shadow">{{ tool.name }}</h3>
                  <p class="mt-1 line-clamp-1 text-xs text-white/75">
                    {{ tool.description || (isPptWorkspaceTool(tool.id) ? "进入 PPT 工作台" : "点击进入对话") }}
                  </p>
                </div>
                <span class="shrink-0 rounded-full bg-white/18 px-2.5 py-1 text-[11px] font-medium text-white ring-1 ring-white/25 backdrop-blur">
                  {{ modalityLabel(tool.outputModality) }}
                </span>
              </div>
              <div
                class="absolute left-3 top-3 flex items-center gap-1.5 rounded-full bg-white/90 py-1 pl-1 pr-2 text-[11px] font-medium text-slate-900 shadow-sm ring-1 ring-white/50 backdrop-blur"
                :title="modelBrand(tool).name"
              >
                <img
                  :src="normalizeMediaUrl(modelBrand(tool).iconUrl)"
                  :alt="modelBrand(tool).name"
                  class="h-5 w-5 rounded-full bg-white object-contain"
                />
                <span class="max-w-[88px] truncate">{{ modelBrand(tool).name }}</span>
              </div>
            </div>
            <div class="flex flex-1 flex-col px-4 py-4">
              <p class="line-clamp-2 min-h-[40px] text-sm text-muted-foreground">
                {{ tool.description || (isPptWorkspaceTool(tool.id) ? "进入 PPT 工作台" : "点击进入对话") }}
              </p>
              <div class="mt-4 flex items-center justify-between gap-2">
                <CreditCostBadge :cost="tool.estimatedCreditCost" />
                <span class="text-lg font-semibold text-primary">4.7</span>
              </div>
            </div>
          </div>

          <div v-else class="flex min-h-[320px] flex-col px-5 pb-5 pt-6">
            <div
              class="mb-5 flex h-[92px] w-[92px] items-center justify-center overflow-hidden rounded-3xl border border-border bg-white p-4 shadow-sm ring-1 ring-white/10 transition group-hover:ring-primary/50 dark:bg-white"
              :style="{ backgroundColor: `${modelBrand(tool).color}14` }"
              :title="modelBrand(tool).name"
            >
              <img
                :src="normalizeMediaUrl(modelBrand(tool).iconUrl)"
                :alt="modelBrand(tool).name"
                class="h-full w-full object-contain"
              />
            </div>
            <h3 class="text-xl font-semibold">{{ tool.name }}</h3>
            <p class="mt-1 max-w-full truncate text-[11px] font-medium text-primary">
              {{ modelBrand(tool).name }}
            </p>
            <p class="mt-2 line-clamp-3 min-h-[60px] text-sm text-muted-foreground">
              {{ tool.description || (isPptWorkspaceTool(tool.id) ? "进入 PPT 工作台" : "点击进入对话") }}
            </p>
            <div class="mt-auto flex flex-wrap items-center justify-between gap-2 pt-6">
              <span class="rounded-full bg-white/8 px-2.5 py-1 text-[11px] text-muted-foreground">
                {{ modalityLabel(tool.outputModality) }}
              </span>
              <CreditCostBadge :cost="tool.estimatedCreditCost" />
            </div>
          </div>
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>
