<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from "vue"
import { RouterLink, useRoute } from "vue-router"
import { ExternalLink, Sparkles } from "lucide-vue-next"
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

function toolDescription(tool: AITool): string {
  return tool.description || "点击进入对话"
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
          class="marketplace-entry-card marketplace-entry-card--image group relative rounded-3xl border border-white/8 bg-white/[0.05] p-6 transition hover:-translate-y-1 hover:border-primary/50"
        >
          <div class="marketplace-entry-card__copy">
            <h2 class="text-xl font-semibold">图像生成</h2>
            <p class="mt-2 text-sm text-white/50">智能系统，即时开发</p>
          </div>
          <div class="marketplace-entry-card__visual">
            <div class="marketplace-entry-card__glow" aria-hidden="true" />
            <div class="marketplace-entry-card__media-shell">
              <video
                v-if="coverForTopTool('IMAGE') && isVideoPreviewUrl(coverForTopTool('IMAGE'))"
                :src="coverForTopTool('IMAGE')"
                class="marketplace-entry-card__media"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
              />
              <img
                v-else-if="coverForTopTool('IMAGE')"
                :src="coverForTopTool('IMAGE')"
                :alt="topToolForModality('IMAGE')?.name"
                class="marketplace-entry-card__media"
              />
              <div v-else class="marketplace-entry-card__media-fallback" />
            </div>
          </div>
        </RouterLink>
        <RouterLink
          :to="{ path: '/dashboard', query: { modality: 'VIDEO' } }"
          class="marketplace-entry-card marketplace-entry-card--video group relative rounded-3xl border border-white/8 bg-white/[0.05] p-6 transition hover:-translate-y-1 hover:border-primary/50"
        >
          <div class="marketplace-entry-card__copy">
            <h2 class="text-xl font-semibold">视频创作</h2>
            <p class="mt-2 text-sm text-white/50">图像、关键一代</p>
          </div>
          <div class="marketplace-entry-card__visual">
            <div class="marketplace-entry-card__glow" aria-hidden="true" />
            <div class="marketplace-entry-card__media-shell">
              <video
                v-if="coverForTopTool('VIDEO') && isVideoPreviewUrl(coverForTopTool('VIDEO'))"
                :src="coverForTopTool('VIDEO')"
                class="marketplace-entry-card__media"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
                @error="topToolForModality('VIDEO') && onToolCoverError(topToolForModality('VIDEO')!)"
              />
              <img
                v-else-if="coverForTopTool('VIDEO')"
                :src="coverForTopTool('VIDEO')"
                :alt="topToolForModality('VIDEO')?.name"
                class="marketplace-entry-card__media"
                @error="topToolForModality('VIDEO') && onToolCoverError(topToolForModality('VIDEO')!)"
              />
              <div v-else class="marketplace-entry-card__media-fallback marketplace-entry-card__media-fallback--video" />
            </div>
          </div>
        </RouterLink>
        <RouterLink
          :to="userRoutes.agentTools"
          class="marketplace-entry-card marketplace-entry-card--agent group relative rounded-3xl border border-white/8 bg-white/[0.05] p-6 transition hover:-translate-y-1 hover:border-primary/50"
        >
          <div class="marketplace-entry-card__copy">
            <h2 class="text-xl font-semibold">人工智能</h2>
            <p class="mt-2 text-sm text-white/50">百步疾驰，瞬间抵达</p>
          </div>
          <div class="marketplace-entry-card__visual" aria-hidden="true">
            <div class="marketplace-entry-card__glow" />
            <img src="https://cdn.wlcloudai.com/static/agent.png" alt="" class="marketplace-entry-card__icon" />
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
              <video
                v-if="isVideoPreviewUrl(tool.comparisonOriginalUrl)"
                :src="normalizeMediaUrl(tool.comparisonOriginalUrl)"
                class="marketplace-tool-image"
                muted loop autoplay playsinline preload="metadata"
              />
              <img
                v-else
                :src="normalizeMediaUrl(tool.comparisonOriginalUrl)"
                :alt="`${tool.name} 原图`"
                class="marketplace-tool-image"
                draggable="false"
              />
              <video
                v-if="isVideoPreviewUrl(tool.comparisonEffectUrl)"
                :src="normalizeMediaUrl(tool.comparisonEffectUrl)"
                class="marketplace-tool-image marketplace-tool-image--effect"
                :style="{ clipPath: `inset(0 0 0 ${comparisonPosition(tool)}%)` }"
                muted loop autoplay playsinline preload="metadata"
              />
              <img
                v-else
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

            <span class="marketplace-modality-badge">{{ modalityLabel(tool.outputModality) }}</span>
          </div>
          <div class="marketplace-tool-overlay">
            <div class="marketplace-tool-content">
              <h3 class="marketplace-tool-title">{{ tool.name }}</h3>
              <div class="marketplace-hover-reveal">
                <p class="marketplace-tool-desc">{{ toolDescription(tool) }}</p>
                <div class="marketplace-start-button">
                  开始创作
                  <ExternalLink class="h-3.5 w-3.5" />
                </div>
              </div>
            </div>
          </div>
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.marketplace-entry-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  overflow: visible;
  min-height: 174px;
}

.marketplace-entry-card__copy {
  position: relative;
  z-index: 2;
  min-width: 0;
}

.marketplace-entry-card__visual {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  min-height: 126px;
  overflow: visible;
}

.marketplace-entry-card__visual::before {
  content: "";
  position: absolute;
  inset: -10px -20px -6px -8px;
  pointer-events: none;
}

.marketplace-entry-card--image .marketplace-entry-card__visual::before {
  background:
    radial-gradient(circle at 72% 56%, rgb(176 92 255 / 0.16), transparent 58%),
    radial-gradient(circle at 88% 72%, rgb(255 255 255 / 0.08), transparent 52%);
}

.marketplace-entry-card--video .marketplace-entry-card__visual::before {
  background:
    radial-gradient(circle at 72% 56%, rgb(70 170 255 / 0.18), transparent 58%),
    radial-gradient(circle at 88% 72%, rgb(255 255 255 / 0.08), transparent 52%);
}

.marketplace-entry-card--agent .marketplace-entry-card__visual::before {
  background:
    radial-gradient(circle at 72% 56%, rgb(176 92 255 / 0.16), transparent 58%),
    radial-gradient(circle at 88% 72%, rgb(56 189 248 / 0.1), transparent 52%);
}

.marketplace-entry-card__glow {
  position: absolute;
  right: 4%;
  bottom: 6px;
  z-index: 0;
  width: 78%;
  height: 28px;
  border-radius: 999px;
  filter: blur(14px);
  opacity: 0.9;
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.marketplace-entry-card--image .marketplace-entry-card__glow {
  background: radial-gradient(ellipse at center, rgb(176 92 255 / 0.72) 0%, rgb(255 255 255 / 0.18) 42%, transparent 72%);
}

.marketplace-entry-card--video .marketplace-entry-card__glow {
  background: radial-gradient(ellipse at center, rgb(70 170 255 / 0.72) 0%, rgb(56 189 248 / 0.34) 42%, transparent 72%);
}

.marketplace-entry-card--agent .marketplace-entry-card__glow {
  background: radial-gradient(ellipse at center, rgb(176 92 255 / 0.72) 0%, rgb(56 189 248 / 0.42) 42%, transparent 72%);
}

.marketplace-entry-card__media-shell {
  position: relative;
  z-index: 1;
  width: clamp(88px, 8.5vw, 118px);
  height: clamp(96px, 9vw, 128px);
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid rgb(255 255 255 / 0.12);
  transform: rotate(-10deg) translate(14%, -6%);
  box-shadow:
    0 16px 24px rgb(88 120 255 / 0.22),
    0 0 18px rgb(176 92 255 / 0.16);
  transition: transform 0.28s ease, box-shadow 0.28s ease;
}

.marketplace-entry-card--video .marketplace-entry-card__media-shell {
  box-shadow:
    0 16px 24px rgb(56 140 255 / 0.24),
    0 0 18px rgb(70 170 255 / 0.18);
}

.marketplace-entry-card__media,
.marketplace-entry-card__media-fallback {
  width: 100%;
  height: 100%;
}

.marketplace-entry-card__media {
  object-fit: cover;
}

.marketplace-entry-card__media-fallback {
  background: linear-gradient(135deg, rgb(255 255 255 / 0.12), rgb(176 92 255 / 0.22));
}

.marketplace-entry-card__media-fallback--video {
  background: linear-gradient(135deg, rgb(70 170 255 / 0.22), rgb(255 255 255 / 0.1));
}

.marketplace-entry-card__icon {
  position: relative;
  z-index: 1;
  width: auto;
  height: clamp(96px, 9vw, 128px);
  max-width: none;
  object-fit: contain;
  transform: rotate(-10deg) translate(14%, -6%);
  filter:
    drop-shadow(0 16px 24px rgb(88 120 255 / 0.28))
    drop-shadow(0 0 18px rgb(176 92 255 / 0.22));
  transition: transform 0.28s ease, filter 0.28s ease;
}

.marketplace-entry-card:hover .marketplace-entry-card__glow {
  opacity: 1;
  transform: scale(1.08);
}

.marketplace-entry-card:hover .marketplace-entry-card__media-shell {
  transform: rotate(-6deg) translate(18%, -10%) scale(1.04);
  box-shadow:
    0 22px 32px rgb(88 120 255 / 0.32),
    0 0 24px rgb(176 92 255 / 0.24);
}

.marketplace-entry-card--video:hover .marketplace-entry-card__media-shell {
  box-shadow:
    0 22px 32px rgb(56 140 255 / 0.34),
    0 0 24px rgb(70 170 255 / 0.28);
}

.marketplace-entry-card:hover .marketplace-entry-card__icon {
  transform: rotate(-6deg) translate(18%, -10%) scale(1.04);
  filter:
    drop-shadow(0 22px 32px rgb(88 120 255 / 0.38))
    drop-shadow(0 0 24px rgb(176 92 255 / 0.32));
}
</style>
