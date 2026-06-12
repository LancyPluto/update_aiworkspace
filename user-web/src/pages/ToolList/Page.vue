<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from "vue"
import { RouterLink, useRoute } from "vue-router"
import { ExternalLink, Sparkles, Zap } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchEnabledAITools } from "@/api/toolApi"
import { fetchTasks } from "@/api/taskApi"
import type { AITool } from "@/api/aiToolTypes"
import type { TaskDetail } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { resolveModelBrand } from "@/utils/modelBrand"
import { toolEntryRoute } from "@/utils/toolEntryRoute"
import { extractTaskPreviewUrl } from "@/utils/taskResultBlocks"
import {
  isVideoPreviewUrl,
  normalizeMediaUrl,
  resolveToolCoverFallback,
  resolveToolCoverUrl,
} from "@/utils/toolCoverMedia"

const auth = useAuthStore()
const route = useRoute()
const props = withDefaults(defineProps<{ mode?: "models" | "agents" }>(), {
  mode: "models",
})
const tools = ref<AITool[]>([])
const tasks = ref<TaskDetail[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const selectedOutputModality = ref<string | undefined>(undefined)
const comparisonPositions = ref<Record<string, number>>({})
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

const coverUseFallback = ref(new Set<string>())

function coverSrcForTool(tool: AITool): string {
  if (coverUseFallback.value.has(tool.id)) {
    return resolveToolCoverFallback(tool)
  }
  return resolveToolCoverUrl(tool)
}

function onToolCoverError(tool: AITool) {
  if (coverUseFallback.value.has(tool.id)) return
  coverUseFallback.value = new Set([...coverUseFallback.value, tool.id])
}

function coverForTopTool(modality: string): string {
  const tool = topToolForModality(modality)
  return tool ? coverSrcForTool(tool) : ""
}

function usesComparisonMedia(tool: AITool): boolean {
  return tool.mediaDisplayMode === "comparison" && Boolean(tool.comparisonOriginalUrl) && Boolean(tool.comparisonEffectUrl)
}

function comparisonPosition(tool: AITool): number {
  return comparisonPositions.value[tool.id] ?? 50
}

function updateComparisonPosition(event: MouseEvent, tool: AITool) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width <= 0) return
  const next = Math.min(92, Math.max(8, ((event.clientX - rect.left) / rect.width) * 100))
  comparisonPositions.value = { ...comparisonPositions.value, [tool.id]: next }
}

function modelBrand(tool: AITool) {
  return resolveModelBrand(tool)
}

function costLabel(tool: AITool): string {
  if (tool.variableCreditPricing || tool.estimatedCreditCost == null) return "算力不详"
  if (tool.estimatedCreditCost === 0) return "免费"
  return `约 ${tool.estimatedCreditCost} 算力/次`
}

function toolDescription(tool: AITool): string {
  return tool.description || "点击进入对话"
}

function toolModelLabel(tool: AITool): string {
  return tool.modelConfigName || tool.modelName || modelBrand(tool).name || modalityLabel(tool.outputModality)
}

const sortedTools = computed(() => [...tools.value].sort((a, b) => a.order - b.order))

function isAgentTool(tool: AITool): boolean {
  const type = (tool.toolType || "").trim().toUpperCase()
  const categoryCode = (tool.categoryCode || "").trim().toLowerCase()
  const text = [tool.id, tool.name, tool.categoryName].filter(Boolean).join(" ").toLowerCase()
  return type === "AGENT" || categoryCode === "agent" || text.includes("agent") || text.includes("智能体")
}

const visibleTools = computed(() =>
  props.mode === "agents"
    ? sortedTools.value.filter(isAgentTool)
    : sortedTools.value.filter((tool) => !isAgentTool(tool)),
)

const outputFilters = computed(() => {
  const counts = new Map<string, number>()
  for (const tool of visibleTools.value) {
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
  if (!selectedOutputModality.value) return visibleTools.value
  return visibleTools.value.filter((tool) => normalizeModality(tool.outputModality) === selectedOutputModality.value)
})

const toolsByCode = computed(() => new Map(visibleTools.value.map((tool) => [tool.id, tool])))

const pageTitle = computed(() => (props.mode === "agents" ? "智能体工具" : "AI 工具市场"))
const pageDescription = computed(() =>
  props.mode === "agents"
    ? "浏览后台上线的智能体工作流工具"
    : "发现模型和内容生成工具",
)
const sectionTitle = computed(() => (props.mode === "agents" ? "智能体专区" : "智能创作区"))
const emptyText = computed(() =>
  props.mode === "agents" ? "暂无上线智能体，请在管理侧智能体管理中启用" : "暂无可用工具，请联系管理员",
)

const tasksByRecency = computed(() =>
  [...tasks.value].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  ),
)

type RecentUsedEntry = {
  tool: AITool
  previewUrl: string
  previewIsVideo: boolean
}

const usageCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const task of tasks.value) {
    if (!task.toolCode) continue
    counts.set(task.toolCode, (counts.get(task.toolCode) || 0) + 1)
  }
  return counts
})

const recentUsedEntries = computed(() => {
  const seen = new Set<string>()
  const list: RecentUsedEntry[] = []
  for (const task of tasksByRecency.value) {
    const tool = toolsByCode.value.get(task.toolCode)
    if (!tool || seen.has(tool.id)) continue
    seen.add(tool.id)
    const generatedPreview = extractTaskPreviewUrl(task)
    const fallbackPreview = normalizeMediaUrl(tool.iconUrl)
    const previewUrl = generatedPreview || fallbackPreview
    list.push({
      tool,
      previewUrl,
      previewIsVideo: isVideoPreviewUrl(previewUrl),
    })
    if (list.length >= 5) break
  }
  if (list.length < 5) {
    for (const tool of visibleTools.value) {
      if (seen.has(tool.id)) continue
      const previewUrl = normalizeMediaUrl(tool.iconUrl)
      list.push({
        tool,
        previewUrl,
        previewIsVideo: isVideoPreviewUrl(previewUrl),
      })
      if (list.length >= 5) break
    }
  }
  return list
})

function topToolForModality(modality: string): AITool | null {
  const key = normalizeModality(modality)
  const candidates = visibleTools.value.filter((tool) => normalizeModality(tool.outputModality) === key)
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

onActivated(() => {
  loadTools()
})

watch(
  () => auth.token,
  (token, previous) => {
    if (token !== previous) loadTools()
  },
)
</script>

<template>
  <AppShell :title="pageTitle" :description="pageDescription">
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
            <template v-if="coverForTopTool('IMAGE')">
              <video
                v-if="isVideoPreviewUrl(coverForTopTool('IMAGE'))"
                :src="coverForTopTool('IMAGE')"
                class="h-full w-full object-cover"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
              />
              <img
                v-else
                :src="coverForTopTool('IMAGE')"
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
            <template v-if="coverForTopTool('VIDEO')">
              <video
                v-if="isVideoPreviewUrl(coverForTopTool('VIDEO'))"
                :src="coverForTopTool('VIDEO')"
                class="h-full w-full object-cover"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
                @error="topToolForModality('VIDEO') && onToolCoverError(topToolForModality('VIDEO')!)"
              />
              <img
                v-else
                :src="coverForTopTool('VIDEO')"
                :alt="topToolForModality('VIDEO')?.name"
                class="h-full w-full object-cover"
                @error="topToolForModality('VIDEO') && onToolCoverError(topToolForModality('VIDEO')!)"
              />
              <div class="absolute inset-0 bg-gradient-to-t from-black/55 to-transparent" />
              <span class="absolute bottom-3 left-3 text-sm font-semibold text-white">{{ topToolForModality('VIDEO')?.name }}</span>
            </template>
          </div>
        </RouterLink>
        <RouterLink
          :to="userRoutes.agentTools"
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
              v-for="entry in recentUsedEntries"
              :key="entry.tool.id"
              :to="toolEntryRoute(entry.tool.id)"
              class="h-28 w-20 shrink-0 overflow-hidden rounded-2xl bg-secondary"
            >
              <video
                v-if="entry.previewUrl && entry.previewIsVideo"
                :src="entry.previewUrl"
                class="h-full w-full object-cover"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
              />
              <img
                v-else-if="entry.previewUrl"
                :src="entry.previewUrl"
                :alt="entry.tool.name"
                class="h-full w-full object-cover"
              />
              <div v-else class="flex h-full w-full items-center justify-center text-primary">
                <Sparkles class="h-6 w-6" />
              </div>
            </RouterLink>
          </div>
        </div>
      </section>

      <h2 class="mb-4 text-xl font-semibold">{{ sectionTitle }}</h2>

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
        <p class="text-sm text-muted-foreground">{{ emptyText }}</p>
      </div>

      <div v-else class="grid gap-6 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-5">
        <RouterLink
          v-for="tool in filteredTools"
          :key="tool.id"
          :to="toolEntryRoute(tool.id)"
          class="marketplace-tool-card"
        >
          <div class="marketplace-tool-media" @mousemove="usesComparisonMedia(tool) && updateComparisonPosition($event, tool)">
            <template v-if="usesComparisonMedia(tool)">
              <img
                :src="normalizeMediaUrl(tool.comparisonOriginalUrl)"
                :alt="`${tool.name} 原图`"
                class="marketplace-tool-image"
                draggable="false"
              />
              <img
                :src="normalizeMediaUrl(tool.comparisonEffectUrl)"
                :alt="`${tool.name} 效果图`"
                class="marketplace-tool-image marketplace-tool-image--effect"
                :style="{ clipPath: `inset(0 0 0 ${comparisonPosition(tool)}%)` }"
                draggable="false"
              />
              <div
                class="marketplace-comparison-line"
                :style="{ left: `${comparisonPosition(tool)}%` }"
              />
              <div
                class="marketplace-comparison-handle"
                :style="{ left: `${comparisonPosition(tool)}%` }"
              >
                ↔
              </div>
            </template>
            <template v-else>
              <video
                v-if="isVideoPreviewUrl(coverSrcForTool(tool))"
                :src="coverSrcForTool(tool)"
                class="marketplace-tool-image"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
                @error="onToolCoverError(tool)"
              />
              <img
                v-else-if="coverSrcForTool(tool)"
                :src="coverSrcForTool(tool)"
                :alt="tool.name"
                class="marketplace-tool-image"
                @error="onToolCoverError(tool)"
              />
              <div v-else class="marketplace-tool-empty">
                <img
                  v-if="modelBrand(tool).iconUrl"
                  :src="normalizeMediaUrl(modelBrand(tool).iconUrl)"
                  :alt="modelBrand(tool).name"
                />
                <Sparkles v-else class="h-10 w-10 text-white/48" />
              </div>
            </template>

            <div class="marketplace-tool-shade" />
            <span class="marketplace-modality-badge">{{ modalityLabel(tool.outputModality) }}</span>
            <span class="marketplace-cost-badge"><Zap class="h-3.5 w-3.5" />{{ costLabel(tool) }}</span>
            <div class="marketplace-title-strip">
              <h3>{{ tool.name }}</h3>
              <p>{{ toolModelLabel(tool) }}</p>
            </div>
            <div class="marketplace-hover-panel">
              <p>{{ toolDescription(tool) }}</p>
              <span>{{ toolModelLabel(tool) }}</span>
              <div class="marketplace-start-button">
                开始创作
                <ExternalLink class="h-3.5 w-3.5" />
              </div>
            </div>
          </div>
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.marketplace-tool-card {
  position: relative;
  display: block;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.055);
  border-radius: 22px;
  background: #121216;
  box-shadow: 0 24px 60px rgb(0 0 0 / 0.24);
  transition: transform 180ms ease, border-color 180ms ease, background-color 180ms ease, box-shadow 180ms ease;
}

.marketplace-tool-card:hover {
  transform: translateY(-3px);
  border-color: rgb(168 85 247 / 0.34);
  background: #15151b;
  box-shadow: 0 26px 70px rgb(0 0 0 / 0.5);
}

.marketplace-tool-media {
  position: relative;
  display: grid;
  aspect-ratio: 1 / 1;
  place-items: center;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 14%, rgb(255 63 121 / 0.32), transparent 34%),
    radial-gradient(circle at 74% 34%, rgb(124 92 255 / 0.28), transparent 36%),
    radial-gradient(circle at 48% 100%, rgb(18 215 178 / 0.16), transparent 42%),
    #0d0d12;
}

.marketplace-tool-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1);
  transition: transform 520ms ease;
}

.marketplace-tool-card:hover .marketplace-tool-image {
  transform: scale(1.055);
}

.marketplace-tool-image--effect {
  z-index: 1;
}

.marketplace-tool-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background:
    linear-gradient(135deg, rgb(255 255 255 / 0.06), transparent 42%),
    radial-gradient(circle at 25% 35%, rgb(255 63 121 / 0.34), transparent 26%),
    radial-gradient(circle at 70% 52%, rgb(124 92 255 / 0.34), transparent 30%),
    radial-gradient(circle at 48% 82%, rgb(24 198 174 / 0.18), transparent 32%);
}

.marketplace-tool-empty img {
  width: 76px;
  height: 76px;
  border-radius: 22px;
  background: rgb(255 255 255 / 0.9);
  object-fit: contain;
  padding: 14px;
  box-shadow: 0 18px 46px rgb(0 0 0 / 0.28);
}

.marketplace-tool-shade {
  position: absolute;
  inset: 0;
  z-index: 2;
  background: linear-gradient(180deg, rgb(0 0 0 / 0.06), transparent 36%, rgb(0 0 0 / 0.82));
  pointer-events: none;
}

.marketplace-modality-badge,
.marketplace-cost-badge {
  position: absolute;
  top: 12px;
  z-index: 5;
  display: inline-flex;
  align-items: center;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 8px;
  box-shadow: 0 10px 28px rgb(0 0 0 / 0.28);
  backdrop-filter: blur(14px);
}

.marketplace-modality-badge {
  left: 12px;
  background: rgb(0 0 0 / 0.46);
  padding: 5px 9px;
  color: rgb(255 255 255 / 0.78);
  font-size: 11px;
  font-weight: 650;
}

.marketplace-cost-badge {
  right: 12px;
  gap: 4px;
  border-color: rgb(168 85 247 / 0.28);
  background: rgb(168 85 247 / 0.2);
  padding: 5px 9px;
  color: rgb(216 180 254);
  font-size: 11px;
  font-weight: 680;
}

.marketplace-title-strip {
  position: absolute;
  inset: auto 0 0;
  z-index: 3;
  padding: 18px;
  transition: transform 260ms ease;
}

.marketplace-tool-card:hover .marketplace-title-strip {
  transform: translateY(-8px);
}

.marketplace-title-strip h3 {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: rgb(255 255 255 / 0.92);
  font-size: 16px;
  font-weight: 720;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
}

.marketplace-title-strip p {
  overflow: hidden;
  margin: 3px 0 0;
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.marketplace-hover-panel {
  position: absolute;
  inset: auto 0 0;
  z-index: 4;
  display: flex;
  min-height: 52%;
  flex-direction: column;
  justify-content: flex-end;
  border-top: 1px solid rgb(255 255 255 / 0.07);
  background: linear-gradient(180deg, rgb(18 18 22 / 0.62), rgb(18 18 22 / 0.96) 54%, #121216);
  padding: 18px;
  opacity: 0;
  transform: translateY(16px);
  transition: opacity 260ms ease, transform 260ms ease;
  backdrop-filter: blur(18px);
}

.marketplace-tool-card:hover .marketplace-hover-panel {
  opacity: 1;
  transform: translateY(0);
}

.marketplace-hover-panel p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: rgb(255 255 255 / 0.7);
  font-size: 12px;
  line-height: 1.75;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.marketplace-hover-panel span {
  align-self: flex-start;
  max-width: 100%;
  overflow: hidden;
  margin-top: 10px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 7px;
  background: rgb(255 255 255 / 0.055);
  padding: 4px 7px;
  color: rgb(255 255 255 / 0.42);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.marketplace-start-button {
  display: inline-flex;
  width: 100%;
  height: 40px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 10px;
  background: linear-gradient(135deg, #ff3f79, #8f5cff);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  margin-top: 14px;
  box-shadow: 0 12px 32px rgb(255 63 121 / 0.18);
  transition: filter 160ms ease, transform 160ms ease;
}

.marketplace-tool-card:hover .marketplace-start-button:hover {
  filter: brightness(1.06);
  transform: translateY(-1px);
}

.marketplace-comparison-line {
  position: absolute;
  inset-block: 0;
  z-index: 5;
  width: 1px;
  background: rgb(255 255 255 / 0.86);
  box-shadow: 0 0 0 1px rgb(0 0 0 / 0.35);
  pointer-events: none;
}

.marketplace-comparison-handle {
  position: absolute;
  top: 50%;
  z-index: 5;
  display: flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.65);
  border-radius: 999px;
  background: rgb(0 0 0 / 0.45);
  color: white;
  font-size: 11px;
  font-weight: 700;
  box-shadow: 0 16px 32px rgb(0 0 0 / 0.3);
  transform: translate(-50%, -50%);
  pointer-events: none;
  backdrop-filter: blur(12px);
}
</style>
