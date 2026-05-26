<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { RouterLink } from "vue-router"
import {
  ArrowRight,
  Clock,
  FileText,
  Filter,
  LoaderCircle,
  Music,
  Sparkles,
  Trash2,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { confirmDelete } from "@/composables/useConfirmDelete"
import { deleteTask, fetchTasks } from "@/api/taskApi"
import type { TaskDetail } from "@/api/types"
import type { ResultBlock } from "@/types/result"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"

type MaterialModality = "all" | "IMAGE" | "VIDEO" | "AUDIO" | "TEXT" | "OTHER"

type MaterialItem = {
  task: TaskDetail
  blocks: ResultBlock[]
  modality: Exclude<MaterialModality, "all">
}

const auth = useAuthStore()
const loading = ref(false)
const error = ref("")
const tasks = ref<TaskDetail[]>([])
const selectedModality = ref<MaterialModality>("all")
const selectTool = ref("all")
const sortType = ref("desc")
const deletingTaskId = ref<number | null>(null)

const modalityOptions: Array<{ value: MaterialModality; label: string }> = [
  { value: "all", label: "全部作品" },
  { value: "IMAGE", label: "图片" },
  { value: "VIDEO", label: "视频" },
  { value: "AUDIO", label: "音频" },
  { value: "TEXT", label: "文本" },
  { value: "OTHER", label: "其他" },
]

const originMaterials = computed<MaterialItem[]>(() =>
  tasks.value
    .filter((task) => task.status === "SUCCESS" && task.result?.contentText)
    .map((task) => {
      const blocks = buildTaskResultBlocks(task.result?.contentText || "", task)
      return {
        task,
        blocks,
        modality: inferModality(task, blocks),
      }
    }),
)

const materials = computed(() => {
  let list = [...originMaterials.value]
  if (selectedModality.value !== "all") {
    list = list.filter((item) => item.modality === selectedModality.value)
  }
  if (selectTool.value !== "all") {
    list = list.filter((item) => item.task.toolName === selectTool.value)
  }
  list.sort((a, b) => {
    const timeA = new Date(a.task.createdAt || 0).getTime()
    const timeB = new Date(b.task.createdAt || 0).getTime()
    return sortType.value === "desc" ? timeB - timeA : timeA - timeB
  })
  return list
})

const toolOptions = computed(() => {
  const source =
    selectedModality.value === "all"
      ? originMaterials.value
      : originMaterials.value.filter((item) => item.modality === selectedModality.value)
  return Array.from(new Set(source.map((item) => item.task.toolName).filter(Boolean)))
})

watch(selectedModality, () => {
  selectTool.value = "all"
})

async function loadMaterials() {
  loading.value = true
  error.value = ""
  try {
    const response = await fetchTasks({
      token: auth.token,
      query: { status: "SUCCESS", pageNo: 1, pageSize: 80 },
    })
    tasks.value = response.list
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载素材库失败"
  } finally {
    loading.value = false
  }
}

function inferModality(task: TaskDetail, blocks: ResultBlock[]): Exclude<MaterialModality, "all"> {
  const raw = (task.outputModality || task.result?.resourceType || "").toUpperCase()
  if (raw === "IMAGE" || blocks.some((block) => block.type === "image")) return "IMAGE"
  if (raw === "VIDEO" || blocks.some((block) => block.type === "video")) return "VIDEO"
  if (raw === "AUDIO" || blocks.some((block) => block.type === "audio")) return "AUDIO"
  if (raw === "TEXT" || blocks.some((block) => block.type === "text" || block.type === "report")) return "TEXT"
  return "OTHER"
}

function primaryBlock(item: MaterialItem): ResultBlock | null {
  return (
    item.blocks.find((block) => block.type === "image" || block.type === "video" || block.type === "audio") ||
    item.blocks[0] ||
    null
  )
}

function modalityLabel(value: MaterialModality) {
  return modalityOptions.find((option) => option.value === value)?.label || value
}

function formatTime(value?: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  })
}

const PROMPT_PREVIEW_MAX_LENGTH = 48

function taskPromptText(task: TaskDetail) {
  const params = task.params || {}
  const value = params.prompt || params.text || params.description || params.videoTopic || params.productName
  return typeof value === "string" && value.trim() ? value.trim() : ""
}

function taskPromptPreview(task: TaskDetail, maxLength = PROMPT_PREVIEW_MAX_LENGTH) {
  const prompt = taskPromptText(task)
  if (!prompt) return task.taskNo
  if (prompt.length <= maxLength) return prompt
  return `${prompt.slice(0, maxLength)}…`
}

function textPreview(item: MaterialItem) {
  const block = primaryBlock(item)
  if (!block) return ""
  if (block.type === "text" || block.type === "json" || block.type === "report") return block.content
  if (block.type === "list") return block.items.join("\n")
  return item.task.result?.contentText || ""
}

async function removeMaterial(item: MaterialItem) {
  if (deletingTaskId.value) return
  const name = taskPromptPreview(item.task) || item.task.toolName || item.task.taskNo
  const confirmed = await confirmDelete({
    title: "删除素材",
    description: `确定删除「${name}」这个素材吗？删除后素材库和任务历史中将不再显示。`,
  })
  if (!confirmed) return
  deletingTaskId.value = item.task.taskId
  error.value = ""
  try {
    await deleteTask(item.task.taskId, { token: auth.token })
    tasks.value = tasks.value.filter((task) => task.taskId !== item.task.taskId)
  } catch (err) {
    error.value = err instanceof Error ? err.message : "删除素材失败"
  } finally {
    deletingTaskId.value = null
  }
}

onMounted(loadMaterials)
</script>

<template>
  <AppShell
    title="素材库"
    description="按生成模态和工具浏览作品，快速复用你的 AI 创造成果"
  >
    <div class="h-full w-full px-4 py-6 sm:px-6">
      <div class="mb-6 rounded-2xl border border-gray-100 bg-white p-4 shadow-sm">
        <div class="flex flex-wrap items-center justify-between gap-4">
          <div class="flex items-center gap-2 text-sm text-gray-600">
            <Filter class="h-4 w-4 text-gray-500" />
            <span>筛选浏览</span>
          </div>
          <div class="flex flex-wrap items-center gap-3">
            <div class="flex rounded-xl border border-gray-200 bg-gray-50 p-1">
              <button
                v-for="option in modalityOptions"
                :key="option.value"
                type="button"
                class="rounded-lg px-3 py-1.5 text-sm transition"
                :class="selectedModality === option.value ? 'bg-white text-blue-600 shadow-sm' : 'text-gray-500 hover:text-gray-900'"
                @click="selectedModality = option.value"
              >
                {{ option.label }}
              </button>
            </div>
            <select
              v-model="selectTool"
              class="h-10 rounded-xl border border-gray-200 bg-white px-3 text-sm outline-none focus:border-blue-400"
            >
              <option value="all">全部工具</option>
              <option v-for="tool in toolOptions" :key="tool" :value="tool">
                {{ tool }}
              </option>
            </select>
            <select
              v-model="sortType"
              class="h-10 rounded-xl border border-gray-200 bg-white px-3 text-sm outline-none focus:border-blue-400"
            >
              <option value="desc">最新时间</option>
              <option value="asc">最早时间</option>
            </select>
          </div>
        </div>
      </div>

      <div
        v-if="error"
        class="mb-6 flex items-center gap-2 rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-600 shadow-sm"
      >
        <div class="h-1.5 w-1.5 rounded-full bg-red-500"></div>
        {{ error }}
      </div>

      <div v-if="loading" class="flex flex-col items-center justify-center py-20 text-muted-foreground">
        <LoaderCircle class="mb-3 h-8 w-8 animate-spin" />
        <p class="text-sm">正在加载素材库...</p>
      </div>

      <div
        v-else-if="materials.length === 0"
        class="rounded-2xl border border-dashed border-gray-200 bg-white p-10 text-center shadow-sm"
      >
        <div class="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-gray-100">
          <Sparkles class="h-6 w-6 text-gray-400" />
        </div>
        <h3 class="mt-4 text-base font-medium text-gray-900">暂无匹配素材</h3>
        <p class="mx-auto mt-2 max-w-sm text-sm text-gray-500">
          切换筛选条件，或完成一次 AI 任务生成新的作品。
        </p>
        <RouterLink
          :to="userRoutes.toolList"
          class="mt-6 inline-flex items-center rounded-xl bg-blue-600 px-5 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-blue-700"
        >
          去创建任务
        </RouterLink>
      </div>

      <div v-else class="columns-1 gap-5 sm:columns-2 lg:columns-3 2xl:columns-4">
        <article
          v-for="item in materials"
          :key="item.task.taskId"
          class="group mb-5 inline-block w-full break-inside-avoid overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-sm transition-all duration-200 hover:-translate-y-1 hover:shadow-md"
        >
          <div class="relative bg-gray-100">
            <template v-if="primaryBlock(item)?.type === 'image'">
              <img
                :src="primaryBlock(item)?.images[0]?.url"
                :alt="item.task.toolName"
                class="max-h-[560px] w-full object-cover"
                loading="lazy"
              />
              <span
                v-if="primaryBlock(item)?.images.length > 1"
                class="absolute bottom-3 right-3 rounded-full bg-black/65 px-2.5 py-1 text-xs text-white"
              >
                {{ primaryBlock(item)?.images.length }} 张
              </span>
            </template>
            <template v-else-if="primaryBlock(item)?.type === 'video'">
              <video
                :src="primaryBlock(item)?.url"
                controls
                playsinline
                preload="metadata"
                class="w-full bg-black"
              />
            </template>
            <template v-else-if="primaryBlock(item)?.type === 'audio'">
              <div class="space-y-5 bg-gradient-to-br from-indigo-50 to-blue-50 p-5 pt-12">
                <div class="flex items-center gap-3">
                  <div class="flex h-12 w-12 items-center justify-center rounded-full bg-white text-blue-600 shadow-sm">
                    <Music class="h-6 w-6" />
                  </div>
                  <div class="min-w-0">
                    <p class="truncate text-sm font-medium text-gray-900">{{ primaryBlock(item)?.title }}</p>
                    <p class="text-xs text-gray-500">音频作品</p>
                  </div>
                </div>
                <audio :src="primaryBlock(item)?.url" controls preload="metadata" class="w-full" />
              </div>
            </template>
            <template v-else>
              <div class="space-y-4 bg-gradient-to-br from-slate-50 to-gray-100 p-5 pt-12">
                <div class="flex items-center gap-3">
                  <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white text-gray-500 shadow-sm">
                    <FileText class="h-5 w-5" />
                  </div>
                  <div class="min-w-0">
                    <p class="truncate text-sm font-medium text-gray-900">{{ primaryBlock(item)?.title || item.task.toolName }}</p>
                    <p class="text-xs text-gray-500">文本作品</p>
                  </div>
                </div>
                <p class="line-clamp-10 whitespace-pre-line text-sm leading-6 text-gray-700">
                  {{ textPreview(item) }}
                </p>
              </div>
            </template>

            <div class="absolute left-3 top-3 flex items-center gap-2">
              <span class="rounded-full bg-white/90 px-2.5 py-1 text-xs font-medium text-gray-700 shadow-sm backdrop-blur">
                {{ modalityLabel(item.modality) }}
              </span>
            </div>
            <button
              type="button"
              class="absolute right-3 top-3 inline-flex h-8 w-8 items-center justify-center rounded-full bg-white/90 text-gray-500 opacity-0 shadow-sm backdrop-blur transition hover:bg-red-50 hover:text-red-600 group-hover:opacity-100 disabled:cursor-not-allowed disabled:opacity-70"
              :disabled="deletingTaskId === item.task.taskId"
              :title="`删除素材：${taskPromptPreview(item.task)}`"
              @click="removeMaterial(item)"
            >
              <LoaderCircle v-if="deletingTaskId === item.task.taskId" class="h-4 w-4 animate-spin" />
              <Trash2 v-else class="h-4 w-4" />
            </button>
          </div>

          <div class="space-y-3 p-4">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <h3 class="truncate text-sm font-semibold text-gray-900">{{ item.task.toolName }}</h3>
                <p class="mt-1 truncate text-xs text-gray-500" :title="taskPromptText(item.task) || item.task.taskNo">
                  {{ taskPromptPreview(item.task) }}
                </p>
              </div>
              <div class="flex shrink-0 items-center gap-1 text-xs text-gray-400">
                <Clock class="h-3 w-3" />
                {{ formatTime(item.task.createdAt) }}
              </div>
            </div>

            <div class="flex items-center justify-between gap-3">
              <span class="truncate text-xs text-gray-400">{{ item.task.toolCode }}</span>
              <RouterLink
                :to="userRoutes.taskResult(String(item.task.taskId))"
                class="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-blue-600 transition hover:text-blue-800"
              >
                查看完整内容
                <ArrowRight class="h-3 w-3 transition-transform group-hover:translate-x-0.5" />
              </RouterLink>
            </div>
          </div>
        </article>
      </div>
    </div>
  </AppShell>
</template>
