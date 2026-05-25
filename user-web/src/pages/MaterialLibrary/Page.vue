<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { ArrowRight, FileText, LoaderCircle, Clock, Filter } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchTasks } from "@/api/taskApi"
import type { TaskDetail } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const loading = ref(false)
const error = ref("")
const tasks = ref<TaskDetail[]>([])
const selectTool = ref("all")
const sortType = ref("desc")

const originMaterials = computed(() =>
  tasks.value.filter((task) => task.status === "SUCCESS" && task.result?.contentText),
)

const materials = computed(() => {
  let list = [...originMaterials.value]
  if (selectTool.value !== "all") {
    list = list.filter(item => item.toolName === selectTool.value)
  }
  list.sort((a, b) => {
    const timeA = new Date(a.createTime || 0).getTime()
    const timeB = new Date(b.createTime || 0).getTime()
    return sortType.value === "desc" ? timeB - timeA : timeA - timeB
  })
  return list
})

const toolOptions = computed(() => {
  const set = new Set(originMaterials.value.map(item => item.toolName))
  return Array.from(set)
})

async function loadMaterials() {
  loading.value = true
  error.value = ""
  try {
    const response = await fetchTasks({
      token: auth.token,
      query: { status: "SUCCESS", pageNo: 1, pageSize: 50 },
    })
    tasks.value = response.list
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载素材库失败"
  } finally {
    loading.value = false
  }
}

onMounted(loadMaterials)
</script>

<template>
  <AppShell
    title="素材库"
    description="按工具、时间分类浏览，快速查阅复用生成内容"
  >
    <!-- 这里已经改成铺满外层容器 ✅ -->
    <div class="w-full h-full px-4 py-6 sm:px-6">

      <!-- 筛选工具栏 -->
      <div class="flex flex-wrap items-center justify-between gap-3 mb-6 p-4 rounded-xl bg-white shadow-sm border border-gray-100">
        <div class="flex items-center gap-2">
          <Filter class="w-4 h-4 text-gray-500" />
          <span class="text-sm text-gray-600">筛选浏览</span>
        </div>
        <div class="flex flex-wrap gap-4">
          <select
            v-model="selectTool"
            class="text-sm border border-gray-200 rounded-lg px-3 py-1.5 outline-none focus:border-blue-400"
          >
            <option value="all">全部工具</option>
            <option v-for="tool in toolOptions" :key="tool" :value="tool">
              {{ tool }}
            </option>
          </select>
          <select
            v-model="sortType"
            class="text-sm border border-gray-200 rounded-lg px-3 py-1.5 outline-none focus:border-blue-400"
          >
            <option value="desc">最新时间</option>
            <option value="asc">最早时间</option>
          </select>
        </div>
      </div>

      <!-- 错误提示 -->
      <div
        v-if="error"
        class="mb-6 flex items-center gap-2 rounded-xl bg-red-50 text-red-600 p-4 text-sm border border-red-100 shadow-sm"
      >
        <div class="w-1.5 h-1.5 rounded-full bg-red-500"></div>
        {{ error }}
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="py-20 flex flex-col items-center justify-center text-muted-foreground">
        <LoaderCircle class="h-8 w-8 animate-spin mb-3" />
        <p class="text-sm">正在加载素材库...</p>
      </div>

      <!-- 空状态 -->
      <div
        v-else-if="materials.length === 0"
        class="rounded-2xl border border-dashed border-gray-200 bg-white p-10 text-center shadow-sm"
      >
        <div class="h-14 w-14 rounded-full bg-gray-100 flex items-center justify-center mx-auto">
          <FileText class="h-6 w-6 text-gray-400" />
        </div>
        <h3 class="mt-4 text-base font-medium text-gray-900">暂无匹配素材</h3>
        <p class="mt-2 text-sm text-gray-500 max-w-sm mx-auto">
          切换筛选条件，或完成AI任务生成新素材
        </p>
        <RouterLink
          :to="userRoutes.toolList"
          class="mt-6 inline-flex items-center rounded-xl bg-blue-600 px-5 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-blue-700 transition"
        >
          去创建任务
        </RouterLink>
      </div>

      <!-- 素材列表 -->
      <div v-else class="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <article
          v-for="item in materials"
          :key="item.taskId"
          class="group relative rounded-2xl border border-gray-200 bg-white p-6 shadow-sm hover:shadow-md hover:-translate-y-1 transition-all duration-200"
        >
          <div class="flex items-center justify-between mb-3">
            <div class="flex items-center gap-2">
              <FileText class="h-4 w-4 text-gray-400" />
              <span class="text-xs text-gray-500">{{ item.toolName }}</span>
            </div>
            <div class="flex items-center gap-1 text-xs text-gray-400">
              <Clock class="h-3 w-3" />
              {{ item.createTime || "" }}
            </div>
          </div>

          <div class="relative min-h-[120px]">
            <p class="text-base leading-relaxed text-gray-800 whitespace-pre-line line-clamp-6 font-normal">
              {{ item.result?.contentText }}
            </p>
            <div class="absolute bottom-0 left-0 right-0 h-10 bg-gradient-to-t from-white to-transparent pointer-events-none"></div>
          </div>

          <div class="mt-4 flex items-center justify-between">
            <span class="text-xs text-gray-400">编号：{{ item.taskNo }}</span>
            <RouterLink
              :to="userRoutes.taskResult(String(item.taskId))"
              class="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-800 transition"
            >
              查看完整内容
              <ArrowRight class="h-3 w-3 group-hover:translate-x-0.5 transition-transform" />
            </RouterLink>
          </div>
        </article>
      </div>
    </div>
  </AppShell>
</template>