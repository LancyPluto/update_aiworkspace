<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { RouterLink } from "vue-router"
import {
  Filter,
  ChevronDown,
  Check,
  LoaderCircle,
  Sparkles,
  Trash2,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import AssetCard from "@/components/AssetCard.vue"
import AssetPreviewModal from "@/components/AssetPreviewModal.vue"
import { confirmDelete } from "@/composables/useConfirmDelete"
import { deleteTask, fetchTasks } from "@/api/taskApi"
import { publishCommunityPost, unpublishCommunityPost } from "@/api/communityApi"
import { fetchTools } from "@/api/toolApi"
import type { TaskDetail, ToolSummary } from "@/api/types"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import type { ResultBlock } from "@/types/result"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { assetFromTask, taskPrompt, taskPromptPreview } from "@/utils/assetPreviewAdapter"
import { openDashboardWithAsset } from "@/utils/assetReplay"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"

type MaterialModality = "all" | "IMAGE" | "VIDEO" | "AUDIO" | "TEXT" | "OTHER"

type MaterialItem = {
  task: TaskDetail
  blocks: ResultBlock[]
  modality: Exclude<MaterialModality, "all">
}

type MaterialAssetItem = MaterialItem & {
  asset: AssetPreviewItem
}

const auth = useAuthStore()
const loading = ref(false)
const error = ref("")
const tasks = ref<TaskDetail[]>([])
const tools = ref<ToolSummary[]>([])
const selectedModality = ref<MaterialModality>("all")
const selectTool = ref("all")
const sortType = ref("desc")
const activeFilterMenu = ref<"tool" | "sort" | null>(null)
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

const sortOptions = [
  { value: "desc", label: "最新时间" },
  { value: "asc", label: "最早时间" },
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

const materialAssets = computed<MaterialAssetItem[]>(() =>
  materials.value
    .map((item) => {
      const asset = assetFromMaterial(item)
      return asset ? { ...item, asset } : null
    })
    .filter((item): item is MaterialAssetItem => Boolean(item)),
)

const toolOptions = computed(() => {
  const source =
    selectedModality.value === "all"
      ? originMaterials.value
      : originMaterials.value.filter((item) => item.modality === selectedModality.value)
  return Array.from(new Set(source.map((item) => item.task.toolName).filter(Boolean)))
})
const selectedToolLabel = computed(() => (selectTool.value === "all" ? "全部工具" : selectTool.value))
const selectedSortLabel = computed(() => sortOptions.find((item) => item.value === sortType.value)?.label ?? "最新时间")
const previewRecommendations = computed<AssetPreviewRecommendation[]>(() =>
  previewAsset.value ? recommendToolsForAsset(previewAsset.value) : [],
)

const toolByName = computed(() => {
  const map = new Map<string, ToolSummary>()
  for (const tool of tools.value) {
    if (!tool?.toolName) continue
    map.set(tool.toolName, tool)
  }
  return map
})

function modelLabelForTask(task: TaskDetail) {
  const tool = toolByName.value.get(task.toolName)
  const model = tool?.modelConfigName || tool?.modelName
  return model ? String(model) : "默认"
}

watch(selectedModality, () => {
  selectTool.value = "all"
})

function toggleFilterMenu(menu: "tool" | "sort") {
  activeFilterMenu.value = activeFilterMenu.value === menu ? null : menu
}

function selectMaterialTool(value: string) {
  selectTool.value = value
  activeFilterMenu.value = null
}

function selectMaterialSort(value: string) {
  sortType.value = value
  activeFilterMenu.value = null
}

function closeFilterMenu() {
  activeFilterMenu.value = null
}

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

function assetFromMaterial(item: MaterialItem): AssetPreviewItem | null {
  return assetFromTask(item.task, {
    blocks: item.blocks,
    idPrefix: "material",
    source: "private",
    modality: item.modality,
  })
}

function openAssetPreview(item: MaterialAssetItem) {
  previewAsset.value = item.asset
}

function normalizeModality(value?: string | null) {
  return (value || "TEXT").trim().toUpperCase()
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
  if (previewAsset.value) openDashboardWithAsset(previewAsset.value, tool)
  previewAsset.value = null
}

function openPreviewTask(asset: AssetPreviewItem) {
  if (!asset.taskId) return
  window.location.href = `/tasks/${asset.taskId}/result`
}

async function publishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.taskId) return
  try {
    const post = await publishCommunityPost(
      {
        taskId: asset.taskId,
        title: asset.title,
        description: asset.subtitle || null,
        promptVisible: asset.promptVisible ?? false,
      },
      { token: auth.token },
    )
    previewAsset.value = { ...asset, communityPostId: post.id, promptVisible: post.promptVisible }
  } catch (err) {
    const message = err instanceof Error ? err.message : "发布失败"
    window.alert(message)
  }
}

async function unpublishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.communityPostId) return
  try {
    await unpublishCommunityPost(asset.communityPostId, { token: auth.token })
    previewAsset.value = { ...asset, communityPostId: undefined }
  } catch (err) {
    const message = err instanceof Error ? err.message : "撤回失败"
    window.alert(message)
  }
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

onMounted(() => {
  document.addEventListener("click", closeFilterMenu)
  void loadMaterials()
})

onUnmounted(() => {
  document.removeEventListener("click", closeFilterMenu)
})
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
            <div class="relative" @click.stop>
              <button
                type="button"
                class="flex h-10 min-w-[148px] items-center justify-between gap-3 rounded-2xl border border-white/10 bg-[#18181c]/90 px-3 text-sm text-white shadow-[inset_0_1px_0_rgb(255_255_255_/_0.05)] outline-none transition hover:border-white/18 hover:bg-[#202026] focus:border-primary"
                @click="toggleFilterMenu('tool')"
              >
                <span class="truncate">{{ selectedToolLabel }}</span>
                <ChevronDown class="h-4 w-4 text-white/45 transition" :class="{ 'rotate-180': activeFilterMenu === 'tool' }" />
              </button>
              <div
                v-if="activeFilterMenu === 'tool'"
                class="absolute right-0 z-30 mt-2 max-h-72 w-56 overflow-y-auto rounded-2xl border border-white/10 bg-[#18181d] p-1.5 shadow-[0_18px_54px_rgb(0_0_0_/_0.48)] ring-1 ring-black/40"
              >
                <button
                  type="button"
                  class="flex w-full items-center justify-between rounded-xl px-3 py-2 text-left text-sm text-white/72 transition hover:bg-white/8 hover:text-white"
                  :class="{ 'bg-white/10 text-white': selectTool === 'all' }"
                  @click="selectMaterialTool('all')"
                >
                  <span>全部工具</span>
                  <Check v-if="selectTool === 'all'" class="h-4 w-4 text-primary" />
                </button>
                <button
                  v-for="tool in toolOptions"
                  :key="tool"
                  type="button"
                  class="flex w-full items-center justify-between gap-3 rounded-xl px-3 py-2 text-left text-sm text-white/72 transition hover:bg-white/8 hover:text-white"
                  :class="{ 'bg-white/10 text-white': selectTool === tool }"
                  @click="selectMaterialTool(tool)"
                >
                  <span class="truncate">{{ tool }}</span>
                  <Check v-if="selectTool === tool" class="h-4 w-4 shrink-0 text-primary" />
                </button>
              </div>
            </div>
            <div class="relative" @click.stop>
              <button
                type="button"
                class="flex h-10 min-w-[132px] items-center justify-between gap-3 rounded-2xl border border-white/10 bg-[#18181c]/90 px-3 text-sm text-white shadow-[inset_0_1px_0_rgb(255_255_255_/_0.05)] outline-none transition hover:border-white/18 hover:bg-[#202026] focus:border-primary"
                @click="toggleFilterMenu('sort')"
              >
                <span>{{ selectedSortLabel }}</span>
                <ChevronDown class="h-4 w-4 text-white/45 transition" :class="{ 'rotate-180': activeFilterMenu === 'sort' }" />
              </button>
              <div
                v-if="activeFilterMenu === 'sort'"
                class="absolute right-0 z-30 mt-2 w-40 rounded-2xl border border-white/10 bg-[#18181d] p-1.5 shadow-[0_18px_54px_rgb(0_0_0_/_0.48)] ring-1 ring-black/40"
              >
                <button
                  v-for="option in sortOptions"
                  :key="option.value"
                  type="button"
                  class="flex w-full items-center justify-between rounded-xl px-3 py-2 text-left text-sm text-white/72 transition hover:bg-white/8 hover:text-white"
                  :class="{ 'bg-white/10 text-white': sortType === option.value }"
                  @click="selectMaterialSort(option.value)"
                >
                  <span>{{ option.label }}</span>
                  <Check v-if="sortType === option.value" class="h-4 w-4 text-primary" />
                </button>
              </div>
            </div>
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
        v-else-if="materialAssets.length === 0"
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
        <AssetCard
          v-for="item in materialAssets"
          :key="item.task.taskId"
          :asset="item.asset"
          source="private"
          class="mb-5"
          @open="openAssetPreview(item)"
        >
          <template #media-actions>
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
          </template>
          <template #footer>
            <div class="min-w-0 space-y-0.5">
              <div class="truncate text-xs text-white/40">任务ID：{{ item.task.taskId }}</div>
              <div class="truncate text-xs text-white/40">模型：{{ modelLabelForTask(item.task) }}</div>
            </div>
            <RouterLink
              :to="userRoutes.taskResult(String(item.task.taskId))"
              class="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-primary transition hover:text-white"
              @click.stop
            >
              只看
            </RouterLink>
          </template>
        </AssetCard>
      </div>
    </div>
    <AssetPreviewModal
      :asset="previewAsset"
      :recommendations="previewRecommendations"
      @close="previewAsset = null"
      @use-tool="useAssetWithTool"
      @open-task="openPreviewTask"
      @publish="publishPreviewAsset"
      @unpublish="unpublishPreviewAsset"
    />
  </AppShell>
</template>
