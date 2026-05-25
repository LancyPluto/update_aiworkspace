<script setup lang="ts">
import { FileText, ChevronRight } from "lucide-vue-next"
import type { PptProjectSummary } from "@/api/pptApi"

defineProps<{
  project: PptProjectSummary
}>()

const creationLabels: Record<string, string> = {
  idea: "一句话",
  outline: "大纲",
  description: "描述",
  ppt_renovation: "翻新",
}

const statusLabels: Record<string, string> = {
  DRAFT: "草稿",
  OUTLINE_READY: "大纲就绪",
  DESCRIPTIONS_READY: "描述就绪",
  IMAGES_READY: "已出图",
  COMPLETED: "已完成",
  FAILED: "失败",
}

function formatTime(value?: string | null) {
  if (!value) return ""
  try {
    return new Date(value).toLocaleString("zh-CN", {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    })
  } catch {
    return value
  }
}
</script>

<template>
  <div
    class="group flex items-center gap-4 rounded-xl border border-border bg-card p-4 transition-colors hover:border-primary/40 hover:bg-secondary/30"
  >
    <div
      class="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
    >
      <FileText class="h-5 w-5" />
    </div>
    <div class="min-w-0 flex-1">
      <p class="truncate font-medium text-foreground">
        {{ project.title || `项目 #${project.bindingId}` }}
      </p>
      <p class="mt-0.5 text-xs text-muted-foreground">
        {{ creationLabels[project.creationType] || project.creationType }}
        · {{ statusLabels[project.status] || project.status }}
        <template v-if="project.pageCount != null"> · {{ project.pageCount }} 页</template>
        <template v-if="project.updatedAt"> · {{ formatTime(project.updatedAt) }}</template>
      </p>
    </div>
    <ChevronRight
      class="h-5 w-5 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-primary"
    />
  </div>
</template>
