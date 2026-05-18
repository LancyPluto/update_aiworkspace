<script setup lang="ts">
import { onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { ArrowLeft, ChevronRight } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import ResultRenderer from "@/components/ResultRenderer/ResultRenderer.vue"
import { getApiOrigin } from "@/api/client"
import { fetchTaskById } from "@/api/taskApi"
import type { TaskDetail } from "@/api/types"
import type { ResultBlock } from "@/types/result"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{ taskId: string }>()
const auth = useAuthStore()
const task = ref<TaskDetail | null>(null)
const loading = ref(true)
const error = ref("")
const blocks = ref<ResultBlock[]>([])

onMounted(async () => {
  loading.value = true
  error.value = ""
  try {
    task.value = await fetchTaskById(props.taskId, { token: auth.token })
    if (task.value.result?.contentText) {
      blocks.value = buildResultBlocks(task.value.result.contentText, task.value)
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "获取任务结果失败"
  } finally {
    loading.value = false
  }
})

function buildResultBlocks(content: string, detail?: TaskDetail): ResultBlock[] {
  const outputModality = (detail?.outputModality || detail?.result?.resourceType || "TEXT").toUpperCase()
  const parsed = parseJson(content)
  const finalVideoUrl = extractFinalVideoUrl(content)

  if (detail?.toolCode === "enterprise_diagnosis_agent") {
    return [
      {
        type: "report",
        title: detail.toolName || "企业诊断报告",
        content,
        filename: `${detail.taskNo ?? "enterprise-diagnosis"}-report`,
      },
    ]
  }

  if (outputModality === "VIDEO" || finalVideoUrl) {
    const videoUrl = finalVideoUrl || collectUrls(parsed ?? content)[0]
    if (videoUrl) {
      return [
        {
          type: "video",
          title: "最终成片",
          url: normalizeMediaUrl(videoUrl, detail?.finishedAt ?? detail?.taskId),
          downloadName: `${detail?.taskNo ?? "video"}-final.mp4`,
        },
      ]
    }
  }

  if (outputModality === "IMAGE") {
    const images = collectUrls(parsed ?? content).map((url, index) => ({
      url: normalizeMediaUrl(url),
      label: `图片 ${index + 1}`,
    }))
    if (images.length > 0) {
      return [{ type: "image", title: "图片结果", images }]
    }
  }

  if (outputModality === "AUDIO") {
    const audioUrl = collectUrls(parsed ?? content)[0]
    if (audioUrl) {
      return [
        {
          type: "audio",
          title: "音频结果",
          url: normalizeMediaUrl(audioUrl),
          downloadName: `${detail?.taskNo ?? "audio"}-result`,
        },
      ]
    }
  }

  if (parsed !== null && outputModality !== "TEXT") {
    return [{ type: "json", title: "结构化结果", content: JSON.stringify(parsed, null, 2) }]
  }
  return [{ type: "text", title: "生成结果", content }]
}

function parseJson(content: string): unknown | null {
  const trimmed = content.trim()
  if (!trimmed || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
    return null
  }
  try {
    return JSON.parse(trimmed)
  } catch {
    return null
  }
}

function collectUrls(value: unknown): string[] {
  const urls = new Set<string>()
  const visit = (item: unknown) => {
    if (typeof item === "string") {
      extractUrlsFromText(item).forEach((url) => urls.add(sanitizeUrl(url)))
      return
    }
    if (Array.isArray(item)) {
      item.forEach(visit)
      return
    }
    if (item && typeof item === "object") {
      Object.values(item).forEach(visit)
    }
  }
  visit(value)
  return Array.from(urls)
}

function extractUrlsFromText(value: string): string[] {
  const trimmed = value.trim()
  const direct = /^(https?:\/\/\S+|\/\S+|data:(?:image|audio|video)\/\S+;base64,\S+)$/i
  if (direct.test(trimmed)) {
    return [trimmed]
  }
  const matches = trimmed.match(/(?:https?:\/\/|\/)[^\s"'<>]+/g)
  return matches ?? []
}

function extractFinalVideoUrl(content: string): string {
  const patterns = [
    /最终成片[：:]\s*(\S+)/,
    /final\.mp4[)\]]?\s*[:：]?\s*(\S+)/i,
    /(\/generated\/\S+?\.mp4)/,
    /(https?:\/\/\S+?\.mp4(?:\?\S*)?)/,
  ]
  for (const pattern of patterns) {
    const match = content.match(pattern)
    if (match?.[1]) {
      return sanitizeUrl(match[1])
    }
  }
  return ""
}

function sanitizeUrl(value: string): string {
  return value.trim().replace(/[)\]，。,.]+$/g, "")
}

function normalizeMediaUrl(value: string, cacheKey?: string | number | null): string {
  const withCacheKey = (url: string) => {
    if (!cacheKey || url.startsWith("data:")) {
      return url
    }
    const separator = url.includes("?") ? "&" : "?"
    return `${url}${separator}v=${encodeURIComponent(String(cacheKey))}`
  }
  if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
    return withCacheKey(value)
  }
  const path = value.startsWith("/") ? value : `/${value}`
  const apiOrigin = getApiOrigin()
  return withCacheKey(apiOrigin ? `${apiOrigin}${path}` : path)
}
</script>

<template>
  <AppShell title="任务结果" :description="'任务 ' + (task?.taskNo ?? taskId) + ' · 生成输出'">
    <div class="mx-auto max-w-4xl space-y-6 px-6 py-6">
      <nav class="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <RouterLink :to="userRoutes.myTasks" class="inline-flex items-center gap-1 hover:text-foreground">
          <ArrowLeft class="h-3 w-3" /> 我的任务
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="font-mono text-foreground">{{ task?.taskNo ?? taskId }}</span>
      </nav>

      <div v-if="loading" class="flex justify-center py-12 text-sm text-muted-foreground">加载中...</div>

      <div v-else-if="error" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
      </div>

      <div v-else-if="blocks.length === 0" class="rounded-xl border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
        暂无结果数据
      </div>

      <ResultRenderer v-else :blocks="blocks" />

      <div class="flex flex-wrap justify-center gap-2 pt-2">
        <RouterLink :to="userRoutes.myTasks" class="inline-flex h-10 items-center justify-center rounded-md border border-border bg-background px-4 text-sm hover:bg-secondary">
          返回我的任务
        </RouterLink>
        <RouterLink :to="userRoutes.toolList" class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:opacity-90">
          再去工具超市
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>
