<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { FileText, ArrowRight } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchTasks } from "@/api/taskApi"
import type { TaskDetail } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { userRoutes } from "@/router/userRoutes"

const auth = useAuthStore()
const loading = ref(false)
const tasks = ref<TaskDetail[]>([])

const materials = computed(() => tasks.value.filter(task => task.status === "SUCCESS" && task.result?.contentText))

async function loadMaterials() {
  loading.value = true
  try {
    const res = await fetchTasks({
      token: auth.token,
      query: { status: "SUCCESS", pageNo: 1, pageSize: 50 },
    })
    tasks.value = res.list
  } finally {
    loading.value = false
  }
}

onMounted(loadMaterials)
</script>

<template>
  <AppShell title="素材库" description="从已完成任务中沉淀可复用生成结果">
    <div class="px-6 py-6">
      <div v-if="loading" class="py-12 text-center text-sm text-muted-foreground">加载中...</div>
      <div v-else-if="materials.length === 0" class="rounded-lg border border-dashed border-border bg-card px-6 py-12 text-center">
        <FileText class="mx-auto h-8 w-8 text-muted-foreground" />
        <p class="mt-3 text-sm font-medium">暂无素材</p>
        <p class="mt-1 text-xs text-muted-foreground">完成一次 AI 任务后，结果会自动出现在这里。</p>
        <RouterLink
          :to="userRoutes.toolList"
          class="mt-4 inline-flex rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
        >
          去创建任务
        </RouterLink>
      </div>

      <div v-else class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        <article
          v-for="item in materials"
          :key="item.taskId"
          class="rounded-lg border border-border bg-card p-5"
        >
          <div class="flex items-start gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
              <FileText class="h-5 w-5" />
            </div>
            <div class="min-w-0">
              <h2 class="truncate text-sm font-semibold">{{ item.toolName }}</h2>
              <p class="text-xs text-muted-foreground">{{ item.taskNo }}</p>
            </div>
          </div>
          <p class="mt-4 line-clamp-5 whitespace-pre-line text-sm text-muted-foreground">
            {{ item.result?.contentText }}
          </p>
          <RouterLink
            :to="userRoutes.taskResult(String(item.taskId))"
            class="mt-4 inline-flex items-center gap-1 text-xs font-medium text-primary"
          >
            查看完整结果 <ArrowRight class="h-3 w-3" />
          </RouterLink>
        </article>
      </div>
    </div>
  </AppShell>
</template>
