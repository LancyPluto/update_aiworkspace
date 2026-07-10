<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from "vue"

defineOptions({ name: "MaterialLibraryPage" })
import { RouterLink, useRoute } from "vue-router"
import {
  ChevronDown,
  Filter,
  Globe2,
  LoaderCircle,
  Sparkles,
  Trash2,
} from "lucide-vue-next"
import AssetCard from "@/components/AssetCard.vue"
import MasonryLayout from "@/components/MasonryLayout.vue"
import AssetPreviewModal from "@/components/AssetPreviewModal.vue"
import CommunityPublishModal from "@/components/CommunityPublishModal.vue"
import { confirmDelete } from "@/composables/useConfirmDelete"
import { deleteTask, fetchTasks } from "@/api/taskApi"
import {
  resolvePublishedCommunityPostId,
  unpublishCommunityPost,
} from "@/api/communityApi"
import { publishAssetToCommunity, type CommunityPublishPayload } from "@/utils/publishCommunityAsset"
import { emitCommunityPostUnpublished } from "@/utils/communitySync"
import { fetchTools } from "@/api/toolApi"
import type { TaskDetail, ToolSummary } from "@/api/types"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import type { ResultBlock } from "@/types/result"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { assetFromTask, taskPromptPreview } from "@/utils/assetPreviewAdapter"
import { recommendToolsForAsset as recommendAssetTools } from "@/utils/assetToolRecommendations"
import { openCreateWithAssetRecommendation } from "@/utils/assetReplay"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"
import SubjectLibraryPanel from "@/pages/MaterialLibrary/SubjectLibraryPanel.vue"

type AssetTab = "works" | "subjects"

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
const route = useRoute()
const assetTab = computed<AssetTab>(() => (route.path.startsWith("/library/subjects") ? "subjects" : "works"))
const assetTabOptions: Array<{ value: AssetTab; label: string; to: string }> = [
  { value: "works", label: "作品", to: "/library" },
  { value: "subjects", label: "主体", to: "/library/subjects" },
]
const loading = ref(false)
const error = ref("")
const tasks = ref<TaskDetail[]>([])
const usageTasks = ref<TaskDetail[]>([])
const tools = ref<ToolSummary[]>([])
const selectedModality = ref<MaterialModality>("all")
const selectTool = ref("all")
const sortType = ref("desc")
const toolDropdownOpen = ref(false)
const sortDropdownOpen = ref(false)
const toolDropdownRef = ref<HTMLElement | null>(null)
const sortDropdownRef = ref<HTMLElement | null>(null)
const currentPage = ref(1)
const hasNextPage = ref(false)
const loadingMore = ref(false)
const deletingTaskId = ref<number | null>(null)
const communityActionTaskId = ref<number | null>(null)
const previewAsset = ref<AssetPreviewItem | null>(null)
const publishModalAsset = ref<AssetPreviewItem | null>(null)
const publishSubmitting = ref(false)

const modalityOptions: Array<{ value: MaterialModality; label: string }> = [
  { value: "all", label: "全部作品" },
  { value: "IMAGE", label: "图片" },
  { value: "VIDEO", label: "视频" },
  { value: "AUDIO", label: "音频" },
  { value: "TEXT", label: "文本" },
  { value: "OTHER", label: "其他" },
]

const sortOptions = [
  { label: "最新时间", value: "desc" },
  { label: "最早时间", value: "asc" },
] as const

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
    .flatMap((item) => assetsFromMaterial(item).map((asset) => ({ ...item, asset }))),
)

const toolOptions = computed(() => {
  const source =
    selectedModality.value === "all"
      ? originMaterials.value
      : originMaterials.value.filter((item) => item.modality === selectedModality.value)
  return Array.from(new Set(source.map((item) => item.task.toolName).filter(Boolean)))
})

const toolFilterOptions = computed(() => [
  { label: "全部工具", value: "all" },
  ...toolOptions.value.map((tool) => ({ label: tool, value: tool })),
])

const currentToolLabel = computed(
  () => toolFilterOptions.value.find((item) => item.value === selectTool.value)?.label || "全部工具",
)

const currentSortLabel = computed(
  () => sortOptions.find((item) => item.value === sortType.value)?.label || "最新时间",
)
const previewRecommendations = computed<AssetPreviewRecommendation[]>(() =>
  previewAsset.value ? recommendToolsForAsset(previewAsset.value) : [],
)

watch(
  () => assetTab.value,
  (tab) => {
    if (tab === "works" && tasks.value.length === 0 && !loading.value) {
      loadMaterials()
    }
  },
)

watch(selectedModality, () => {
  selectTool.value = "all"
})

function selectToolFilter(value: string) {
  selectTool.value = value
  toolDropdownOpen.value = false
}

function selectSortType(value: string) {
  sortType.value = value
  sortDropdownOpen.value = false
}

function closeDropdowns() {
  toolDropdownOpen.value = false
  sortDropdownOpen.value = false
}

function handleDocumentClick(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof Node)) return
  if (toolDropdownRef.value?.contains(target) || sortDropdownRef.value?.contains(target)) return
  closeDropdowns()
}

async function loadMaterials(reset = true) {
  if (reset) {
    currentPage.value = 1
    loading.value = true
  } else {
    loadingMore.value = true
  }
  error.value = ""
  try {
    const response = await fetchTasks({
      token: auth.token,
      query: { status: "SUCCESS", pageNo: currentPage.value, pageSize: 40 },
    })
    tasks.value = reset ? response.list : [...tasks.value, ...response.list]
    hasNextPage.value = response.hasNext
    currentPage.value += 1
    if (reset) {
      const [toolResponse, usageResponse] = await Promise.all([
        fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 120 } }),
        fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 120 } }).catch(() => null),
      ])
      tools.value = toolResponse.list
      usageTasks.value = usageResponse?.list || response.list
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载素材库失败"
  } finally {
    if (reset) loading.value = false
    else loadingMore.value = false
  }
}

async function loadMoreMaterials() {
  if (loading.value || loadingMore.value || !hasNextPage.value) return
  await loadMaterials(false)
}

function inferModality(task: TaskDetail, blocks: ResultBlock[]): Exclude<MaterialModality, "all"> {
  const raw = (task.outputModality || task.result?.resourceType || "").toUpperCase()
  if (raw === "IMAGE" || blocks.some((block) => block.type === "image")) return "IMAGE"
  if (raw === "VIDEO" || blocks.some((block) => block.type === "video")) return "VIDEO"
  if (raw === "AUDIO" || blocks.some((block) => block.type === "audio")) return "AUDIO"
  if (raw === "TEXT" || blocks.some((block) => block.type === "text" || block.type === "report")) return "TEXT"
  return "OTHER"
}

function assetsFromMaterial(item: MaterialItem): AssetPreviewItem[] {
  const asset = assetFromTask(item.task, {
    blocks: item.blocks,
    idPrefix: "material",
    source: "private",
    modality: item.modality,
  })
  if (!asset) return []
  // 多图任务拆分为独立卡片：每张图一个结果卡（与任务真实图片数一致）
  if (asset.kind === "image" && asset.urls && asset.urls.length > 1) {
    return asset.urls.map((url, index) => ({
      ...asset,
      id: `${asset.id}-${index + 1}`,
      url,
      urls: [url],
      title: `${asset.title} · 图${index + 1}`,
      subtitle: `${asset.taskNo || asset.subtitle || ""} · 第 ${index + 1}/${asset.urls!.length} 张`,
    }))
  }
  return [asset]
}

function openAssetPreview(item: MaterialAssetItem) {
  previewAsset.value = item.asset
}

function patchTaskCommunityPost(taskId: number, communityPostId?: number | null) {
  tasks.value = tasks.value.map((task) =>
    task.taskId === taskId ? { ...task, communityPostId: communityPostId ?? null } : task,
  )
}

function recommendToolsForAsset(asset: AssetPreviewItem): AssetPreviewRecommendation[] {
  return recommendAssetTools(asset, tools.value, { tasks: usageTasks.value })
}

function useAssetWithTool(tool: AssetPreviewRecommendation) {
  if (previewAsset.value) openCreateWithAssetRecommendation(previewAsset.value, tool)
  previewAsset.value = null
}

function openPreviewTask(asset: AssetPreviewItem) {
  if (!asset.taskId) return
  window.location.href = `/tasks/${asset.taskId}/result`
}

function syncPreviewAssetCommunityState(asset: AssetPreviewItem) {
  if (previewAsset.value?.taskId === asset.taskId) {
    previewAsset.value = asset
  }
}

function openPublishModal(asset: AssetPreviewItem) {
  if (!auth.isLoggedIn || !asset.taskId || communityActionTaskId.value) return
  publishModalAsset.value = asset
}

function closePublishModal() {
  if (publishSubmitting.value) return
  publishModalAsset.value = null
}

async function publishMaterialAsset(asset: AssetPreviewItem, payload?: CommunityPublishPayload) {
  if (!auth.isLoggedIn || !asset.taskId || communityActionTaskId.value) return
  communityActionTaskId.value = asset.taskId
  publishSubmitting.value = true
  try {
    const post = await publishAssetToCommunity(asset, {
      token: auth.token,
      payload,
      defaultPromptVisible: auth.user?.promptPublicByDefault ?? false,
    })
    patchTaskCommunityPost(asset.taskId, post.id)
    syncPreviewAssetCommunityState({ ...asset, communityPostId: post.id, promptVisible: post.promptVisible, title: post.title })
    publishModalAsset.value = null
  } catch (err) {
    const message = err instanceof Error ? err.message : "发布失败"
    window.alert(message)
  } finally {
    communityActionTaskId.value = null
    publishSubmitting.value = false
  }
}

async function confirmPublishMaterial(payload: CommunityPublishPayload) {
  if (!publishModalAsset.value) return
  await publishMaterialAsset(publishModalAsset.value, payload)
}

async function unpublishMaterialAsset(asset: AssetPreviewItem) {
  if (!auth.isLoggedIn || !asset.taskId || communityActionTaskId.value) return
  communityActionTaskId.value = asset.taskId
  try {
    const postId = await resolvePublishedCommunityPostId(asset.taskId, {
      token: auth.token,
      userId: auth.user?.id ?? tasks.value.find((task) => task.taskId === asset.taskId)?.userId,
      hint: asset.communityPostId,
    })
    if (!postId) return
    await unpublishCommunityPost(postId, { token: auth.token })
    emitCommunityPostUnpublished({ postId, taskId: asset.taskId })
    patchTaskCommunityPost(asset.taskId, null)
    syncPreviewAssetCommunityState({ ...asset, communityPostId: undefined })
  } catch (err) {
    const message = err instanceof Error ? err.message : "撤回失败"
    window.alert(message)
  } finally {
    communityActionTaskId.value = null
  }
}

async function removeMaterial(item: MaterialItem) {
  if (deletingTaskId.value) return
  const name = taskPromptPreview(item.task) || item.task.toolName || item.task.taskNo
  const confirmed = await confirmDelete({
    title: "删除素材",
    description: `确定删除「${name}」这个素材吗？删除后素材库和任务历史中将不再显示，若已发布到社区也会一并下架。`,
  })
  if (!confirmed) return
  deletingTaskId.value = item.task.taskId
  error.value = ""
  try {
    const communityPostId = auth.token
      ? await resolvePublishedCommunityPostId(item.task.taskId, {
          token: auth.token,
          userId: auth.user?.id ?? item.task.userId,
          hint: item.task.communityPostId,
        })
      : null
    if (auth.isLoggedIn && communityPostId) {
      await unpublishCommunityPost(communityPostId, { token: auth.token })
      emitCommunityPostUnpublished({ postId: communityPostId, taskId: item.task.taskId })
    }
    await deleteTask(item.task.taskId, { token: auth.token })
    tasks.value = tasks.value.filter((task) => task.taskId !== item.task.taskId)
    if (previewAsset.value?.taskId === item.task.taskId) {
      previewAsset.value = null
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "删除素材失败"
  } finally {
    deletingTaskId.value = null
  }
}

let lastLoadAt = 0
const STALE_MS = 60_000

onMounted(() => {
  if (assetTab.value === "works") {
    loadMaterials()
    lastLoadAt = Date.now()
  }
  document.addEventListener("click", handleDocumentClick)
})

onActivated(() => {
  document.addEventListener("click", handleDocumentClick)
  if (assetTab.value === "works" && Date.now() - lastLoadAt > STALE_MS) {
    loadMaterials()
    lastLoadAt = Date.now()
  }
})

onDeactivated(() => {
  document.removeEventListener("click", handleDocumentClick)
})

onUnmounted(() => {
  document.removeEventListener("click", handleDocumentClick)
})
</script>

<template>
    <div class="mx-auto h-full w-full max-w-[1540px] px-5 py-7">
      <div class="mb-7 flex rounded-2xl border border-white/10 bg-black/20 p-1">
        <RouterLink
          v-for="option in assetTabOptions"
          :key="option.value"
          :to="option.to"
          class="flex-1 rounded-xl px-4 py-2.5 text-center text-sm font-medium transition"
          :class="assetTab === option.value ? 'bg-white/10 text-primary shadow-sm' : 'text-white/45 hover:text-white'"
        >
          {{ option.label }}
        </RouterLink>
      </div>

      <SubjectLibraryPanel v-if="assetTab === 'subjects'" />

      <template v-else>
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
            <div ref="toolDropdownRef" class="filter-dropdown">
              <button
                type="button"
                class="filter-dropdown-trigger"
                aria-label="筛选工具"
                aria-haspopup="listbox"
                :aria-expanded="toolDropdownOpen"
                @click.stop="toolDropdownOpen = !toolDropdownOpen"
              >
                <span>{{ currentToolLabel }}</span>
                <ChevronDown class="h-4 w-4" :class="{ rotated: toolDropdownOpen }" />
              </button>
              <div v-show="toolDropdownOpen" class="filter-dropdown-menu" role="listbox">
                <button
                  v-for="item in toolFilterOptions"
                  :key="item.value"
                  type="button"
                  class="filter-dropdown-option"
                  :class="{ active: selectTool === item.value }"
                  role="option"
                  :aria-selected="selectTool === item.value"
                  @click.stop="selectToolFilter(item.value)"
                >
                  {{ item.label }}
                </button>
              </div>
            </div>
            <div ref="sortDropdownRef" class="filter-dropdown">
              <button
                type="button"
                class="filter-dropdown-trigger"
                aria-label="排序"
                aria-haspopup="listbox"
                :aria-expanded="sortDropdownOpen"
                @click.stop="sortDropdownOpen = !sortDropdownOpen"
              >
                <span>{{ currentSortLabel }}</span>
                <ChevronDown class="h-4 w-4" :class="{ rotated: sortDropdownOpen }" />
              </button>
              <div v-show="sortDropdownOpen" class="filter-dropdown-menu" role="listbox">
                <button
                  v-for="item in sortOptions"
                  :key="item.value"
                  type="button"
                  class="filter-dropdown-option"
                  :class="{ active: sortType === item.value }"
                  role="option"
                  :aria-selected="sortType === item.value"
                  @click.stop="selectSortType(item.value)"
                >
                  {{ item.label }}
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

      <MasonryLayout
        v-else
        :items="materialAssets"
        :item-key="(item) => item.asset.id"
        aria-label="素材库作品"
      >
        <template #default="{ item }">
        <AssetCard
          :asset="item.asset"
          source="private"
          masonry
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
            <span class="truncate text-xs text-white/35">{{ item.task.toolCode }}</span>
            <button
              v-if="!(item.asset.communityPostId ?? item.task.communityPostId)"
              type="button"
              class="inline-flex shrink-0 items-center gap-1 rounded-full border border-primary/35 bg-primary/15 px-2.5 py-1 text-xs font-semibold text-primary transition hover:bg-primary/25 disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="communityActionTaskId === item.task.taskId || deletingTaskId === item.task.taskId"
              @click.stop="openPublishModal(item.asset)"
            >
              <LoaderCircle v-if="communityActionTaskId === item.task.taskId" class="h-3 w-3 animate-spin" />
              <template v-else>
                <Globe2 class="h-3 w-3" />
                发布
              </template>
            </button>
            <button
              v-else
              type="button"
              class="inline-flex shrink-0 items-center rounded-full border border-white/10 bg-white/[0.06] px-2.5 py-1 text-xs font-semibold text-white/55 transition hover:bg-white/10 hover:text-white/75 disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="communityActionTaskId === item.task.taskId || deletingTaskId === item.task.taskId"
              @click.stop="unpublishMaterialAsset(item.asset)"
            >
              <LoaderCircle v-if="communityActionTaskId === item.task.taskId" class="h-3 w-3 animate-spin" />
              <span v-else>撤销</span>
            </button>
          </template>
        </AssetCard>
        </template>
      </MasonryLayout>
      <div v-if="!loading && (hasNextPage || loadingMore)" class="mt-8 flex justify-center">
        <button
          type="button"
          class="inline-flex h-10 items-center justify-center rounded-full border border-white/10 bg-white/[0.06] px-5 text-sm font-medium text-white/70 transition hover:bg-white/10 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
          :disabled="loadingMore"
          @click="loadMoreMaterials"
        >
          <LoaderCircle v-if="loadingMore" class="mr-2 h-4 w-4 animate-spin" />
          加载更多
        </button>
      </div>
      </template>
    </div>
    <AssetPreviewModal
      :asset="previewAsset"
      :recommendations="previewRecommendations"
      @close="previewAsset = null"
      @use-tool="useAssetWithTool"
      @open-task="openPreviewTask"
      @publish="publishMaterialAsset"
      @unpublish="unpublishMaterialAsset"
    />
    <CommunityPublishModal
      :open="Boolean(publishModalAsset)"
      :asset="publishModalAsset"
      :submitting="publishSubmitting"
      @close="closePublishModal"
      @confirm="confirmPublishMaterial"
    />
</template>

<style scoped>
.filter-dropdown {
  position: relative;
}

.filter-dropdown-trigger {
  display: inline-flex;
  height: 40px;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 16px;
  background: rgb(255 255 255 / 0.05);
  padding: 0 12px;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.filter-dropdown-trigger:hover,
.filter-dropdown-trigger:focus-visible {
  border-color: var(--primary);
  outline: none;
}

.filter-dropdown-trigger svg {
  color: rgb(255 255 255 / 0.42);
  transition: transform 0.18s ease;
}

.filter-dropdown-trigger svg.rotated {
  transform: rotate(180deg);
}

.filter-dropdown-menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 50;
  min-width: 100%;
  width: max-content;
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 14px;
  background: #121216;
  padding: 6px;
  box-shadow: 0 22px 60px rgb(0 0 0 / 0.45);
}

.filter-dropdown-option {
  display: flex;
  width: 100%;
  align-items: center;
  border: 0;
  border-radius: 10px;
  background: transparent;
  padding: 9px 12px;
  color: rgb(255 255 255 / 0.72);
  font-size: 14px;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.filter-dropdown-option:hover,
.filter-dropdown-option.active {
  background: rgb(255 255 255 / 0.08);
  color: #fff;
}
</style>
