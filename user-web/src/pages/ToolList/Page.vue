<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink, useRoute } from "vue-router"
import { Film, Sparkles } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import CreditCostBadge from "@/components/CreditCostBadge/CreditCostBadge.vue"
import { getApiOrigin } from "@/api/client"
import { fetchEnabledAITools } from "@/api/aiToolApi"
import type { AITool } from "@/api/aiToolTypes"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const route = useRoute()
const tools = ref<AITool[]>([])
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

async function loadTools() {
  loading.value = true
  error.value = null
  try {
    tools.value = await fetchEnabledAITools({ token: auth.token })
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
  <AppShell title="大模型" description="选择 AI 工具，在统一界面中使用不同能力">
    <div class="px-6 py-8">
      <div
        v-if="offlineNotice"
        class="mb-4 rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-800 dark:text-amber-200"
      >
        该工具已不可用，请选择其他工具
      </div>

      <div class="mb-6 flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="rounded-full border px-3 py-1.5 text-xs font-medium transition"
          :class="
            selectedOutputModality === undefined
              ? 'border-primary bg-primary text-primary-foreground'
              : 'border-border bg-card text-foreground/80 hover:border-primary/40'
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
          class="rounded-full border px-3 py-1.5 text-xs font-medium transition"
          :class="
            selectedOutputModality === item.key
              ? 'border-primary bg-primary text-primary-foreground'
              : 'border-border bg-card text-foreground/80 hover:border-primary/40'
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

      <div v-else-if="error" class="mx-auto max-w-md rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
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

      <div v-else class="grid gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <RouterLink
          v-for="tool in filteredTools"
          :key="tool.id"
          :to="`/chat/${tool.id}`"
          class="group overflow-hidden rounded-2xl border border-border bg-card transition hover:border-primary/40 hover:shadow-lg"
        >
          <div v-if="usesEffectMedia(tool)" class="flex h-full flex-col">
            <div class="relative aspect-[16/10] overflow-hidden bg-muted">
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
              <div class="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/70 via-black/10 to-transparent" />
              <div class="absolute bottom-3 left-3 right-3 flex items-end justify-between gap-3">
                <div class="min-w-0">
                  <h3 class="truncate text-base font-semibold text-white drop-shadow">{{ tool.name }}</h3>
                  <p class="mt-1 line-clamp-1 text-xs text-white/75">
                    {{ tool.description || "点击进入对话" }}
                  </p>
                </div>
                <span class="shrink-0 rounded-full bg-white/18 px-2.5 py-1 text-[11px] font-medium text-white ring-1 ring-white/25 backdrop-blur">
                  {{ modalityLabel(tool.outputModality) }}
                </span>
              </div>
              <div
                class="absolute left-3 top-3 flex h-8 w-8 items-center justify-center rounded-full bg-black/35 text-white ring-1 ring-white/20 backdrop-blur"
              >
                <Film class="h-4 w-4" />
              </div>
            </div>
            <div class="flex flex-1 flex-col px-5 py-4">
              <p class="line-clamp-2 min-h-[40px] text-sm text-muted-foreground">
                {{ tool.description || "点击进入对话" }}
              </p>
              <div class="mt-4 flex flex-col items-start gap-2">
                <CreditCostBadge :cost="tool.estimatedCreditCost" />
                <span class="text-xs font-medium text-primary">开始使用</span>
              </div>
            </div>
          </div>

          <div v-else class="flex flex-col items-center px-6 pb-6 pt-8">
            <div
              class="mb-4 flex h-[72px] w-[72px] items-center justify-center overflow-hidden rounded-full ring-2 ring-border transition group-hover:ring-primary/30"
              :style="{ backgroundColor: tool.primaryColor ? `${tool.primaryColor}18` : undefined }"
            >
              <img
                v-if="tool.iconUrl"
                :src="normalizeMediaUrl(tool.iconUrl)"
                :alt="tool.name"
                class="h-full w-full object-cover"
              />
              <Sparkles v-else class="h-8 w-8 text-primary" />
            </div>
            <h3 class="text-base font-semibold">{{ tool.name }}</h3>
            <p class="mt-2 line-clamp-2 min-h-[40px] text-center text-xs text-muted-foreground">
              {{ tool.description || "点击进入对话" }}
            </p>
            <div class="mt-4 flex flex-wrap items-center justify-center gap-2">
              <span class="rounded-full bg-secondary px-2.5 py-1 text-[11px] text-muted-foreground">
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
