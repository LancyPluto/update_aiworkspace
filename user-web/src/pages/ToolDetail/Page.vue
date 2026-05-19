<script setup lang="ts">
import { RouterLink } from "vue-router"
import {
  ArrowLeft,
  ArrowRight,
  Pencil,
  Star,
  Zap,
  CheckCircle2,
  ChevronRight,
  Heart,
  Share2,
  Clock,
} from "lucide-vue-next"
import { computed, ref, onMounted } from "vue"
import AppShell from "@/components/AppShell.vue"
import { fetchToolByCode } from "@/api/toolApi"
import type { ToolDetail } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  id: string
}>()

const auth = useAuthStore()

const tool = ref<ToolDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const tab = ref<"intro" | "cases" | "input" | "output">("intro")

const title = computed(() => tool.value?.toolName ?? `工具 · ${props.id}`)
const isOffline = computed(() => tool.value?.status === "OFFLINE")

const toolTypeLabels: Record<string, string> = {
  TEXT_GENERATION: "文本生成",
  IMAGE_GENERATION: "文生图",
  IMAGE_TO_IMAGE: "图生图",
  IMAGE_UNDERSTANDING: "图片理解",
  SPEECH_TO_TEXT: "语音转文字",
  TEXT_TO_SPEECH: "文字转语音",
  VIDEO_GENERATION: "视频生成",
  EMBEDDING: "Embedding",
  RERANK: "Rerank",
  AGENT: "Agent 编排",
}

const modalityLabels: Record<string, string> = {
  TEXT: "文本",
  IMAGE: "图片",
  AUDIO: "音频",
  VIDEO: "视频",
  JSON: "JSON",
  FILE: "文件",
  MULTIMODAL: "多模态",
}

function labelOf(labels: Record<string, string>, value?: string | null) {
  return value ? labels[value] || value : "-"
}

onMounted(async () => {
  try {
    tool.value = await fetchToolByCode(props.id, { token: auth.token })
  } catch (e) {
    error.value = (e as Error).message || "加载工具详情失败"
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <AppShell title="工具详情" :description="title">
    <div class="px-6 py-6 max-w-6xl mx-auto space-y-6">
      <!-- 面包屑 -->
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="{ name: 'ToolList' }" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> AI 工具超市
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="text-foreground">{{ title }}</span>
      </nav>

      <!-- 加载中 -->
      <div v-if="loading" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">加载中…</span>
      </div>

      <!-- 错误 -->
      <div v-else-if="error" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
        <RouterLink :to="{ name: 'ToolList' }" class="mt-3 inline-block text-sm text-primary hover:underline">
          返回工具超市
        </RouterLink>
      </div>

      <!-- 工具详情 -->
      <template v-else-if="tool">
        <div
          v-if="isOffline"
          class="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-950 dark:text-amber-100"
        >
          该工具已下架，暂时无法使用。
        </div>

        <div class="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div class="flex flex-col md:flex-row md:items-start gap-5">
            <img
              v-if="tool.coverUrl"
              :src="tool.coverUrl"
              :alt="tool.toolName"
              class="h-16 w-16 shrink-0 rounded-2xl object-cover ring-1 ring-border"
            />
            <div
              v-else
              class="flex h-16 w-16 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-primary to-chart-2 text-primary-foreground"
            >
              <Pencil class="h-8 w-8" />
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <h2 class="text-xl font-semibold">{{ tool.toolName }}</h2>
                <span
                  v-if="tool.estimatedCreditCost > 50"
                  class="rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium text-destructive"
                >
                  HOT
                </span>
                <span class="rounded bg-secondary px-1.5 py-0.5 text-[10px] text-muted-foreground">{{ tool.categoryName }}</span>
                <span class="rounded bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">
                  {{ labelOf(toolTypeLabels, tool.toolType || 'TEXT_GENERATION') }}
                </span>
              </div>
              <p class="mt-2 text-sm text-muted-foreground leading-relaxed">
                {{ tool.description || '暂无描述' }}
              </p>
              <div class="mt-3 flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
                <span class="inline-flex items-center gap-1">
                  <Star class="h-3.5 w-3.5 fill-warning text-warning" /> 4.9 / 5.0
                </span>
                <span>
                  {{ labelOf(modalityLabels, tool.inputModality || 'TEXT') }}
                  →
                  {{ labelOf(modalityLabels, tool.outputModality || 'TEXT') }}
                </span>
              </div>
            </div>
            <div class="flex flex-col items-stretch md:items-end gap-2 shrink-0">
              <div class="flex items-center gap-2 text-sm">
                <Zap class="h-4 w-4 text-warning" />
                <span class="text-2xl font-semibold">{{ tool.estimatedCreditCost }}</span>
                <span class="text-xs text-muted-foreground">算力 / 次</span>
              </div>
              <RouterLink
                v-if="!isOffline"
                :to="'/tools/' + id + '/use'"
                class="inline-flex h-11 items-center justify-center rounded-md bg-primary px-8 text-sm font-medium text-primary-foreground hover:opacity-90"
              >
                开始使用 <ArrowRight class="ml-1.5 h-4 w-4" />
              </RouterLink>
              <span
                v-else
                class="inline-flex h-11 cursor-not-allowed items-center justify-center rounded-md border border-border bg-muted px-8 text-sm font-medium text-muted-foreground"
              >
                已下架
              </span>
              <div class="flex items-center gap-1 justify-end">
                <button
                  type="button"
                  class="inline-flex h-8 items-center rounded-md px-2 text-xs text-muted-foreground hover:bg-secondary"
                >
                  <Heart class="h-3.5 w-3.5 mr-1" /> 收藏
                </button>
                <button
                  type="button"
                  class="inline-flex h-8 items-center rounded-md px-2 text-xs text-muted-foreground hover:bg-secondary"
                >
                  <Share2 class="h-3.5 w-3.5 mr-1" /> 分享
                </button>
              </div>
            </div>
          </div>
        </div>

        <div class="grid gap-6 lg:grid-cols-3">
          <div class="lg:col-span-2 space-y-6">
            <div class="rounded-xl border border-border bg-card p-1 shadow-sm">
              <div class="flex flex-wrap gap-1">
                <button
                  v-for="t in (['intro', 'cases', 'input', 'output'] as const)"
                  :key="t"
                  type="button"
                  class="rounded-md px-3 py-2 text-xs font-medium transition"
                  :class="tab === t ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-secondary'"
                  @click="tab = t"
                >
                  {{
                    t === "intro"
                      ? "工具介绍"
                      : t === "cases"
                        ? "适用场景"
                        : t === "input"
                          ? "输入说明"
                          : "输出示例"
                  }}
                </button>
              </div>
            </div>

            <div v-show="tab === 'intro'" class="rounded-xl border border-border bg-card p-6 space-y-4 text-sm">
              <h4 class="text-sm font-semibold">核心能力</h4>
              <ul class="space-y-2 text-muted-foreground">
                <li
                  v-for="c in ['多平台适配', '智能生成', '一键输出']"
                  :key="c"
                  class="flex gap-2"
                >
                  <CheckCircle2 class="h-4 w-4 shrink-0 text-success mt-0.5" />
                  <span>{{ c }}</span>
                </li>
              </ul>
            </div>

            <div v-show="tab === 'cases'" class="rounded-xl border border-border bg-card p-6">
              <p class="text-sm text-muted-foreground">适用多种业务场景</p>
            </div>

            <div v-show="tab === 'input'" class="rounded-xl border border-border bg-card overflow-hidden">
              <table class="w-full text-sm">
                <thead class="bg-secondary/60">
                  <tr class="text-xs text-muted-foreground">
                    <th class="px-4 py-2.5 text-left font-medium">字段</th>
                    <th class="px-4 py-2.5 text-left font-medium">类型</th>
                    <th class="px-4 py-2.5 text-left font-medium">必填</th>
                    <th class="px-4 py-2.5 text-left font-medium">说明</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-border">
                  <tr v-for="f in tool.fields" :key="f.fieldKey">
                    <td class="px-4 py-3 font-medium">{{ f.fieldName }}</td>
                    <td class="px-4 py-3 text-muted-foreground">{{ f.fieldType }}</td>
                    <td class="px-4 py-3">
                      <span
                        v-if="f.required"
                        class="rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] text-destructive"
                      >必填</span>
                      <span v-else class="text-xs text-muted-foreground">可选</span>
                    </td>
                    <td class="px-4 py-3 text-muted-foreground">{{ f.placeholder || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-show="tab === 'output'" class="rounded-xl border border-border bg-card p-6 space-y-4">
              <div class="rounded-lg border border-border bg-secondary/40 p-4">
                <p class="text-xs text-muted-foreground">AI 将生成符合要求的结果内容</p>
              </div>
            </div>
          </div>

          <div class="space-y-6">
            <div class="rounded-xl border border-border bg-card p-5 shadow-sm">
              <h3 class="text-sm font-semibold mb-4 flex items-center gap-2">
                <Clock class="h-4 w-4 text-primary" /> 使用步骤
              </h3>
              <ol class="space-y-4">
                <li v-for="(s, i) in [
                  { title: '填写参数', desc: '填写工具所需的输入参数' },
                  { title: 'AI 生成', desc: '系统自动调用 AI 模型处理' },
                  { title: '查看结果', desc: '在任务结果页查看输出内容' },
                ]" :key="s.title" class="flex gap-3">
                  <span
                    class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary"
                  >
                    {{ i + 1 }}
                  </span>
                  <div>
                    <p class="text-sm font-medium">{{ s.title }}</p>
                    <p class="text-xs text-muted-foreground mt-0.5">{{ s.desc }}</p>
                  </div>
                </li>
              </ol>
            </div>
          </div>
        </div>
      </template>
    </div>
  </AppShell>
</template>
