<script setup lang="ts">
import { ref, onMounted, computed } from "vue"
import { RouterLink, useRoute } from "vue-router"
import { Sparkles } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { getApiOrigin } from "@/api/client"
import { fetchEnabledAITools } from "@/api/aiToolApi"
import type { AITool } from "@/api/aiToolTypes"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const route = useRoute()
const tools = ref<AITool[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const offlineNotice = computed(() => route.query.notice === "offline")

function normalizeMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

const sortedTools = computed(() => [...tools.value].sort((a, b) => a.order - b.order))

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
  <AppShell title="大模型" description="选择 AI 模型，在统一聊天界面中体验不同能力">
    <div class="px-6 py-8">
      <div
        v-if="offlineNotice"
        class="mb-4 rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-800 dark:text-amber-200"
      >
        该模型已不可用，请选择其他模型
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

      <div v-else-if="sortedTools.length === 0" class="flex flex-col items-center justify-center py-20 text-center">
        <Sparkles class="mb-4 h-12 w-12 text-muted-foreground/50" />
        <p class="text-sm text-muted-foreground">暂无可用模型，请联系管理员</p>
      </div>

      <div v-else class="grid gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <RouterLink
          v-for="tool in sortedTools"
          :key="tool.id"
          :to="`/chat/${tool.id}`"
          class="group overflow-hidden rounded-2xl border border-border bg-card transition hover:border-primary/40 hover:shadow-lg"
        >
          <div class="flex flex-col items-center px-6 pb-6 pt-8">
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
          </div>
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>
