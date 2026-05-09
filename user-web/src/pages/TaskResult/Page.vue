<script setup lang="ts">
  import { RouterLink } from "vue-router"
  import { ArrowLeft, ChevronRight } from "lucide-vue-next"
  import AppShell from "@/components/AppShell.vue"
  import ResultRenderer from "@/components/ResultRenderer/ResultRenderer.vue"
  import type { ResultBlock } from "@/types/result"
  import { userRoutes } from "@/router/userRoutes"

  defineProps<{
    taskId: string
  }>()

  const blocks: ResultBlock[] = [
    {
      type: "text",
      title: "标题（淘宝 30 字内）",
      content: "【海岸夏季新品】冰丝凉感连衣裙 显瘦 V 领度假风长裙 100% 莫代尔",
    },
    {
      type: "list",
      title: "核心卖点（5 条）",
      items: [
        "冰丝面料，体感降温 4-6℃，整夏清凉",
        "V 领设计修饰锁骨线条，显瘦显高 5cm",
        "裙摆 270° 大摆，走动如海风轻拂",
        "100% 莫代尔亲肤，孕妇敏感肌均可穿着",
        "多色可选，附赠同款发带，海边度假必备",
      ],
    },
    {
      type: "text",
      title: "详情页正文片段",
      content:
        "盛夏的海风，是连衣裙最好的搭档。海岸新季选用 100% 莫代尔冰丝面料，亲肤透气，体感比纯棉降温 4-6℃……",
    },
  ]
</script>

<template>
  <AppShell title="任务结果" :description="'任务 ' + taskId + ' · 生成输出'">
    <div class="px-6 py-6 max-w-4xl mx-auto space-y-6">
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="userRoutes.myTasks" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> 我的任务
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="font-mono text-foreground">{{ taskId }}</span>
      </nav>

      <ResultRenderer :blocks="blocks" />

      <div class="flex flex-wrap gap-2 justify-center pt-2">
        <RouterLink
          :to="userRoutes.myTasks"
          class="inline-flex h-10 items-center justify-center rounded-md border border-border bg-background px-4 text-sm hover:bg-secondary"
        >
          返回我的任务
        </RouterLink>
        <RouterLink
          :to="userRoutes.toolList"
          class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:opacity-90"
        >
          再去工具超市
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>
