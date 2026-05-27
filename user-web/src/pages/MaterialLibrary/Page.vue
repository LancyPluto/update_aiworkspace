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
import AssetPreviewModal from "@/components/AssetPreviewModal.vue"
import { confirmDelete } from "@/composables/useConfirmDelete"
import { deleteTask, fetchTasks } from "@/api/taskApi"
import { fetchTools } from "@/api/toolApi"
import type { TaskDetail, ToolSummary } from "@/api/types"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
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
const tools = ref<ToolSummary[]>([])
const selectedModality = ref<MaterialModality>("all")
const selectTool = ref("all")
const sortType = ref("desc")
const deletingTaskId = ref<number | null>(null)
const previewAsset = ref<AssetPreviewItem | null>(null)

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
const previewRecommendations = computed<AssetPreviewRecommendation[]>(() =>
  previewAsset.value ? recommendToolsForAsset(previewAsset.value) : [],
)

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
    const toolResponse = await fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 120 } })
    tools.value = toolResponse.list
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

function assetFromMaterial(item: MaterialItem): AssetPreviewItem | null {
  const block = primaryBlock(item)
  if (!block) return null
  const base = {
    id: `material-${item.task.taskId}`,
    title: item.task.toolName || block.title || item.task.taskNo,
    subtitle: item.task.taskNo,
    prompt: taskPrompt(item.task),
    taskId: item.task.taskId,
    taskNo: item.task.taskNo,
    toolName: item.task.toolName,
    toolCode: item.task.toolCode,
    createdAt: item.task.createdAt,
  }
  if (block.type === "image") {
    return {
      ...base,
      kind: "image",
      url: block.images[0]?.url,
      urls: block.images.map((image) => image.url),
      title: block.title || base.title,
    }
  }
  if (block.type === "video") return { ...base, kind: "video", url: block.url, title: block.title || base.title }
  if (block.type === "audio") return { ...base, kind: "audio", url: block.url, title: block.title || base.title }
  if (block.type === "text" || block.type === "json" || block.type === "report") {
    return { ...base, kind: "text", rawText: block.content, title: block.title || base.title }
  }
  if (block.type === "list") return { ...base, kind: "text", rawText: block.items.join("\n"), title: block.title || base.title }
  return { ...base, kind: "other", rawText: item.task.result?.contentText || "", title: base.title }
}

function openAssetPreview(item: MaterialItem) {
  previewAsset.value = assetFromMaterial(item)
}

function normalizeModality(value?: string | null) {
  return (value || "TEXT").trim().toUpperCase()
}

function taskPrompt(task: TaskDetail): string {
  const params = task.params || {}
  const value = params.prompt || params.text || params.description || params.videoTopic || params.productName
  return typeof value === "string" && value.trim() ? value.trim() : ""
}

function recommendToolsForAsset(asset: AssetPreviewItem): AssetPreviewRecommendation[] {
  const target = asset.kind === "image" ? "IMAGE" : asset.kind === "video" ? "VIDEO" : asset.kind === "audio" ? "AUDIO" : ""
  const keyword = asset.kind === "image" ? /图|图片|影像|photo|image|img|改图|参考/i : asset.kind === "video" ? /视频|短片|video|clip|movie/i : /音频|音乐|audio|voice|tts/i
  const matches = tools.value.filter((tool) => {
    const input = normalizeModality(tool.inputModality)
    const text = `${tool.toolName} ${tool.description || ""} ${tool.configNote || ""} ${tool.toolCode}`
    return (
      (target && (input.includes(target) || input.includes("MULTIMODAL") || input.includes("FILE"))) ||
      keyword.test(text)
    )
  })
  return (matches.length ? matches : tools.value).slice(0, 8)
}

function useAssetWithTool(tool: AssetPreviewRecommendation) {
  if (previewAsset.value) {
    window.sessionStorage.setItem("dashboard_pending_asset", JSON.stringify(previewAsset.value))
  }
  previewAsset.value = null
  window.location.href = `/dashboard?modality=${encodeURIComponent(tool.outputModality || "IMAGE")}&tool=${encodeURIComponent(tool.toolCode)}`
}

function openPreviewTask(asset: AssetPreviewItem) {
  if (!asset.taskId) return
  window.location.href = `/tasks/${asset.taskId}/result`
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
    <div class="mx-auto h-full w-full max-w-[1540px] px-5 py-7">
      <div class="mb-7 rounded-3xl border border-white/8 bg-white/[0.04] p-5 shadow-[0_18px_50px_rgb(0_0_0_/_0.24)]">
        <div class="flex flex-wrap items-center justify-between gap-4">
          <div class="flex items-center gap-2 text-sm text-white/60">
            <Filter class="h-4 w-4 text-white/45" />
            <span>筛选浏览</span>
          </div>
          <div class="flex flex-wrap items-center gap-3">
            <div class="flex rounded-2xl border border-white/10 bg-black/20 p-1">
              <button
                v-for="option in modalityOptions"
                :key="option.value"
                type="button"
                class="rounded-xl px-3 py-1.5 text-sm transition"
                :class="selectedModality === option.value ? 'bg-white/10 text-primary shadow-sm' : 'text-white/45 hover:text-white'"
                @click="selectedModality = option.value"
              >
                {{ option.label }}
              </button>
            </div>
            <select
              v-model="selectTool"
              class="h-10 rounded-2xl border border-white/10 bg-white/[0.05] px-3 text-sm text-white outline-none focus:border-primary"
            >
              <option value="all">全部工具</option>
              <option v-for="tool in toolOptions" :key="tool" :value="tool">
                {{ tool }}
              </option>
            </select>
            <select
              v-model="sortType"
              class="h-10 rounded-2xl border border-white/10 bg-white/[0.05] px-3 text-sm text-white outline-none focus:border-primary"
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
        class="rounded-3xl border border-dashed border-white/12 bg-white/[0.04] p-10 text-center shadow-sm"
      >
        <div class="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-white/8">
          <Sparkles class="h-6 w-6 text-primary" />
        </div>
        <h3 class="mt-4 text-base font-medium text-white">暂无匹配素材</h3>
        <p class="mx-auto mt-2 max-w-sm text-sm text-white/50">
          切换筛选条件，或完成一次 AI 任务生成新的作品。
        </p>
        <RouterLink
          :to="userRoutes.toolList"
          class="mt-6 inline-flex items-center rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white shadow-sm transition hover:brightness-110"
        >
          去创建任务
        </RouterLink>
      </div>

      <div v-else class="columns-1 gap-5 sm:columns-2 lg:columns-3 2xl:columns-4">
        <article
          v-for="item in materials"
          :key="item.task.taskId"
          class="group mb-5 inline-block w-full break-inside-avoid cursor-zoom-in overflow-hidden rounded-3xl border border-white/8 bg-white/[0.04] shadow-[0_18px_42px_rgb(0_0_0_/_0.24)] transition-all duration-200 hover:-translate-y-1 hover:border-primary/50"
          @click="openAssetPreview(item)"
        >
          <div class="relative bg-muted">
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
              <div class="space-y-5 bg-white/[0.05] p-5 pt-12">
                <div class="flex items-center gap-3">
                  <div class="flex h-12 w-12 items-center justify-center rounded-full bg-white/10 text-primary shadow-sm">
                    <Music class="h-6 w-6" />
                  </div>
                  <div class="min-w-0">
                    <p class="truncate text-sm font-medium text-white">{{ primaryBlock(item)?.title }}</p>
                    <p class="text-xs text-white/45">音频作品</p>
                  </div>
                </div>
                <audio :src="primaryBlock(item)?.url" controls preload="metadata" class="w-full" />
              </div>
            </template>
            <template v-else>
              <div class="space-y-4 bg-white/[0.05] p-5 pt-12">
                <div class="flex items-center gap-3">
                  <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/10 text-white/55 shadow-sm">
                    <FileText class="h-5 w-5" />
                  </div>
                  <div class="min-w-0">
                    <p class="truncate text-sm font-medium text-white">{{ primaryBlock(item)?.title || item.task.toolName }}</p>
                    <p class="text-xs text-white/45">文本作品</p>
                  </div>
                </div>
                <p class="line-clamp-10 whitespace-pre-line text-sm leading-6 text-white/70">
                  {{ textPreview(item) }}
                </p>
              </div>
            </template>

            <div class="absolute left-3 top-3 flex items-center gap-2">
              <span class="rounded-full bg-black/55 px-2.5 py-1 text-xs font-medium text-white shadow-sm backdrop-blur">
                {{ modalityLabel(item.modality) }}
              </span>
            </div>
            <button
              type="button"
              class="absolute right-3 top-3 inline-flex h-8 w-8 items-center justify-center rounded-full bg-black/55 text-white/55 opacity-0 shadow-sm backdrop-blur transition hover:bg-red-500/15 hover:text-red-300 group-hover:opacity-100 disabled:cursor-not-allowed disabled:opacity-70"
              :disabled="deletingTaskId === item.task.taskId"
              :title="`删除素材：${taskPromptPreview(item.task)}`"
              @click.stop="removeMaterial(item)"
            >
              <LoaderCircle v-if="deletingTaskId === item.task.taskId" class="h-4 w-4 animate-spin" />
              <Trash2 v-else class="h-4 w-4" />
            </button>
          </div>

          <div class="space-y-3 p-4">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <h3 class="truncate text-base font-semibold text-white">{{ item.task.toolName }}</h3>
                <p class="mt-1 truncate text-xs text-white/45" :title="taskPromptText(item.task) || item.task.taskNo">
                  {{ taskPromptPreview(item.task) }}
                </p>
              </div>
              <div class="flex shrink-0 items-center gap-1 text-xs text-white/35">
                <Clock class="h-3 w-3" />
                {{ formatTime(item.task.createdAt) }}
              </div>
            </div>

            <div class="flex items-center justify-between gap-3">
              <span class="truncate text-xs text-white/35">{{ item.task.toolCode }}</span>
              <RouterLink
                :to="userRoutes.taskResult(String(item.task.taskId))"
                class="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-primary transition hover:text-white"
                @click.stop
              >
                查看完整内容
                <ArrowRight class="h-3 w-3 transition-transform group-hover:translate-x-0.5" />
              </RouterLink>
            </div>
          </div>
        </article>
      </div>
    </div>
    <AssetPreviewModal
      :asset="previewAsset"
      :recommendations="previewRecommendations"
      @close="previewAsset = null"
      @use-tool="useAssetWithTool"
      @open-task="openPreviewTask"
    />
  </AppShell>
</template>
