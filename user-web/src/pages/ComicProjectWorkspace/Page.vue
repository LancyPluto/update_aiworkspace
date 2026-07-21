<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import {
  ArrowLeft,
  ChevronDown,
  Clapperboard,
  Loader2,
  Plus,
  RefreshCw,
} from "lucide-vue-next"
import {
  comicProjectApi,
  type ComicEpisodeDetail,
  type ComicEpisodeSummary,
  type ComicProjectDetail,
} from "@/api/comicProjectApi"
import { ApiBusinessError } from "@/api/client"
import ComicAssetsStage from "@/components/comic/ComicAssetsStage.vue"
import ComicDeliveryStage from "@/components/comic/ComicDeliveryStage.vue"
import ComicProjectStageNav from "@/components/comic/ComicProjectStageNav.vue"
import ComicScriptStage from "@/components/comic/ComicScriptStage.vue"
import ComicShotsStage from "@/components/comic/ComicShotsStage.vue"
import ComicStoryboardStage from "@/components/comic/ComicStoryboardStage.vue"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import {
  comicEpisodeGenerationStatus,
  currentComicStage,
  type ComicWorkspaceStage,
} from "@/utils/comicProject"

const props = defineProps<{ projectId: string }>()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const project = ref<ComicProjectDetail | null>(null)
const episode = ref<ComicEpisodeDetail | null>(null)
const activeStage = ref<ComicWorkspaceStage>("script")
const creatingEpisode = ref(false)
const loading = ref(true)
const episodeLoading = ref(false)
const error = ref<string | null>(null)
const refreshError = ref<string | null>(null)
let loadController: AbortController | null = null
let episodeController: AbortController | null = null
let episodeStatusPollController: AbortController | null = null
let episodeStatusPollTimer: ReturnType<typeof setTimeout> | null = null

const episodes = computed(() => [...(project.value?.episodes ?? [])].sort((left, right) => (left.episodeNo ?? 0) - (right.episodeNo ?? 0)))
const nextEpisodeNo = computed(() => Math.max(0, ...episodes.value.map((item) => item.episodeNo ?? 0)) + 1)

function formatUpdatedAt(value?: string | null): string {
  if (!value) return "尚未保存"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false })
}

function mergeEpisodeSummary(detail: ComicEpisodeDetail): ComicEpisodeSummary {
  return {
    id: detail.id,
    projectId: detail.projectId,
    episodeNo: detail.episodeNo,
    title: detail.title,
    sourceType: detail.sourceType,
    status: detail.status,
    stage: detail.stage,
    revision: detail.revision,
    storyboardLocked: detail.storyboardLocked,
    assetsConfirmed: detail.assetsConfirmed,
    shotCount: detail.shots?.length ?? detail.shotCount,
    totalDurationMs: detail.totalDurationMs,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
  }
}

function syncEpisode(result: ComicEpisodeDetail) {
  episode.value = result
  creatingEpisode.value = false
  if (!project.value) return
  const summaries = [...(project.value.episodes ?? [])]
  const index = summaries.findIndex((item) => item.id === result.id)
  if (index >= 0) summaries[index] = mergeEpisodeSummary(result)
  else summaries.push(mergeEpisodeSummary(result))
  project.value = {
    ...project.value,
    episodes: summaries,
    currentEpisodeId: result.id,
    updatedAt: result.updatedAt ?? project.value.updatedAt,
  }
}

function clearEpisodeStatusPoll() {
  if (episodeStatusPollTimer) clearTimeout(episodeStatusPollTimer)
  episodeStatusPollTimer = null
}

function stopEpisodeStatusPoll() {
  clearEpisodeStatusPoll()
  episodeStatusPollController?.abort()
  episodeStatusPollController = null
}

function scheduleEpisodeStatusPoll(delay = 2_500) {
  clearEpisodeStatusPoll()
  if (!episode.value || !comicEpisodeGenerationStatus(episode.value.status)) return
  episodeStatusPollTimer = setTimeout(() => void pollEpisodeStatus(), delay)
}

async function pollEpisodeStatus() {
  const currentEpisode = episode.value
  if (!currentEpisode || !comicEpisodeGenerationStatus(currentEpisode.status)) return
  episodeStatusPollController?.abort()
  const controller = new AbortController()
  episodeStatusPollController = controller
  try {
    const result = await comicProjectApi.getEpisode(props.projectId, currentEpisode.id, {
      token: auth.token,
      signal: controller.signal,
    })
    if (controller.signal.aborted || episode.value?.id !== currentEpisode.id) return
    refreshError.value = null
    syncEpisode(result)
    if (comicEpisodeGenerationStatus(result.status)) scheduleEpisodeStatusPoll()
    else await refreshProject()
  } catch (pollError) {
    if (controller.signal.aborted || episode.value?.id !== currentEpisode.id) return
    clearEpisodeStatusPoll()
    refreshError.value = pollError instanceof Error ? pollError.message : "AI 处理状态刷新失败"
  } finally {
    if (episodeStatusPollController === controller) episodeStatusPollController = null
  }
}

async function updateEpisodeQuery(episodeId?: number | null) {
  const query = { ...route.query }
  delete query.episodeId
  if (episodeId == null) delete query.episode
  else query.episode = String(episodeId)
  await router.replace({ name: "ComicProjectWorkspace", params: { projectId: props.projectId }, query })
}

async function loadEpisode(episodeId: number, resetStage = true) {
  stopEpisodeStatusPoll()
  episodeController?.abort()
  const controller = new AbortController()
  episodeController = controller
  episodeLoading.value = true
  refreshError.value = null
  try {
    const result = await comicProjectApi.getEpisode(props.projectId, episodeId, { token: auth.token, signal: controller.signal })
    try {
      result.latestGenerationBatch = await comicProjectApi.getLatestGenerationBatch(
        props.projectId,
        episodeId,
        { token: auth.token, signal: controller.signal },
      )
    } catch (batchError) {
      if (!(batchError instanceof ApiBusinessError) || !String(batchError.code).includes("NOT_FOUND")) {
        refreshError.value = batchError instanceof Error ? batchError.message : "镜头批次状态加载失败"
      }
    }
    syncEpisode(result)
    if (resetStage) activeStage.value = currentComicStage(result)
    if (comicEpisodeGenerationStatus(result.status)) scheduleEpisodeStatusPoll()
    await updateEpisodeQuery(result.id)
  } catch (loadError) {
    if (controller.signal.aborted) return
    refreshError.value = loadError instanceof Error ? loadError.message : "单集详情加载失败"
  } finally {
    if (!controller.signal.aborted) episodeLoading.value = false
  }
}

async function loadProject() {
  stopEpisodeStatusPoll()
  loadController?.abort()
  episodeController?.abort()
  const controller = new AbortController()
  loadController = controller
  loading.value = true
  error.value = null
  project.value = null
  episode.value = null
  try {
    const result = await comicProjectApi.get(props.projectId, { token: auth.token, signal: controller.signal })
    project.value = result
    const queryEpisodeId = Number(route.query.episode ?? route.query.episodeId)
    const targetId = Number.isFinite(queryEpisodeId) && queryEpisodeId > 0
      ? queryEpisodeId
      : result.currentEpisodeId ?? result.episodes?.[0]?.id ?? null
    if (targetId != null) await loadEpisode(targetId)
    else {
      creatingEpisode.value = true
      activeStage.value = "script"
    }
  } catch (loadError) {
    if (controller.signal.aborted) return
    error.value = loadError instanceof Error ? loadError.message : "漫剧项目暂时无法加载"
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}

async function refreshProject() {
  refreshError.value = null
  try {
    project.value = await comicProjectApi.get(props.projectId, { token: auth.token })
  } catch (loadError) {
    refreshError.value = loadError instanceof Error ? loadError.message : "项目资料刷新失败"
  }
}

async function reloadEpisode() {
  if (!episode.value) return
  await loadEpisode(episode.value.id, false)
}

function handleEpisodeUpdated(result: ComicEpisodeDetail) {
  syncEpisode(result)
  if (comicEpisodeGenerationStatus(result.status)) scheduleEpisodeStatusPoll()
  void updateEpisodeQuery(result.id)
}

function startNewEpisode() {
  stopEpisodeStatusPoll()
  episodeController?.abort()
  episode.value = null
  creatingEpisode.value = true
  activeStage.value = "script"
  refreshError.value = null
  void updateEpisodeQuery(null)
}

function selectEpisode(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (value === "new") {
    startNewEpisode()
    return
  }
  const id = Number(value)
  if (Number.isFinite(id)) void loadEpisode(id)
}

function openStage(stage: ComicWorkspaceStage) {
  activeStage.value = stage
}

watch(() => props.projectId, loadProject, { immediate: true })
watch(
  () => [episode.value?.id, episode.value?.status] as const,
  () => {
    if (comicEpisodeGenerationStatus(episode.value?.status)) scheduleEpisodeStatusPoll()
    else clearEpisodeStatusPoll()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  stopEpisodeStatusPoll()
  loadController?.abort()
  episodeController?.abort()
})
</script>

<template>
  <main class="min-h-[calc(100vh-80px)] w-full">
    <div v-if="loading" class="flex min-h-[60vh] items-center justify-center text-sm text-muted-foreground">
      <Loader2 class="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />正在加载项目工作台
    </div>

    <section v-else-if="error" class="flex min-h-[60vh] flex-col items-center justify-center px-5 text-center">
      <Clapperboard class="h-9 w-9 text-muted-foreground" aria-hidden="true" />
      <h1 class="mt-4 text-lg font-semibold">无法打开漫剧项目</h1>
      <p class="mt-2 max-w-lg text-sm leading-6 text-muted-foreground">{{ error }}</p>
      <div class="mt-5 flex gap-3">
        <RouterLink :to="userRoutes.comicProjects" class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary"><ArrowLeft class="h-4 w-4" />项目列表</RouterLink>
        <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground" @click="loadProject"><RefreshCw class="h-4 w-4" />重试</button>
      </div>
    </section>

    <template v-else-if="project">
      <header class="border-b border-border bg-background/70 px-4 py-4 backdrop-blur sm:px-6">
        <div class="mx-auto flex max-w-[1500px] flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
          <div class="flex min-w-0 items-start gap-3">
            <RouterLink :to="userRoutes.comicProjects" class="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground" aria-label="返回漫剧项目" title="返回项目列表"><ArrowLeft class="h-4 w-4" /></RouterLink>
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2"><h1 class="truncate text-lg font-semibold">{{ project.title }}</h1><span class="rounded-md bg-primary/10 px-2 py-1 text-[11px] font-medium text-primary">{{ project.aspectRatio || '9:16' }}</span></div>
              <p class="mt-1 line-clamp-1 text-xs text-muted-foreground">{{ project.description || project.visualStyle || 'AI 漫剧项目' }}</p>
            </div>
          </div>
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
            <label class="relative min-w-0 sm:w-56">
              <span class="sr-only">选择单集</span>
              <select :value="creatingEpisode ? 'new' : episode?.id ?? ''" class="h-10 w-full appearance-none rounded-md border border-input bg-background pl-3 pr-9 text-sm outline-none focus:border-primary" :disabled="episodeLoading" @change="selectEpisode">
                <option v-for="item in episodes" :key="item.id" :value="item.id">第 {{ item.episodeNo ?? 1 }} 集 · {{ item.title }}</option>
                <option value="new">+ 新建一集</option>
              </select>
              <ChevronDown class="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            </label>
            <button type="button" class="inline-flex h-10 items-center justify-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary" @click="startNewEpisode"><Plus class="h-4 w-4" />新建一集</button>
          </div>
        </div>
      </header>

      <div class="border-b border-border px-3 py-2 lg:hidden"><ComicProjectStageNav v-model="activeStage" :episode="episode" /></div>

      <div class="mx-auto grid w-full max-w-[1500px] lg:grid-cols-[220px_minmax(0,1fr)]">
        <aside class="hidden border-r border-border px-4 py-6 lg:block">
          <div class="sticky top-5">
            <ComicProjectStageNav v-model="activeStage" :episode="episode" />
            <div class="mt-6 border-t border-border px-3 pt-4 text-xs leading-5 text-muted-foreground">
              <p>{{ episode ? `第 ${episode.episodeNo ?? 1} 集` : `第 ${nextEpisodeNo} 集` }}</p>
              <p class="mt-1">{{ episode ? formatUpdatedAt(episode.updatedAt) : '尚未创建' }}</p>
            </div>
          </div>
        </aside>

        <div class="min-w-0 px-4 py-6 sm:px-6 xl:px-8">
          <div v-if="refreshError" class="mb-4 flex items-center justify-between gap-3 rounded-md border border-warning/25 bg-warning/8 px-3 py-2 text-sm text-warning">
            <span>{{ refreshError }}</span><button type="button" class="shrink-0 underline" @click="reloadEpisode">重试</button>
          </div>
          <div v-if="episodeLoading" class="flex min-h-80 items-center justify-center text-sm text-muted-foreground"><Loader2 class="mr-2 h-4 w-4 animate-spin" />正在切换单集</div>
          <ComicScriptStage
            v-else-if="activeStage === 'script'"
            :project-id="projectId"
            :episode="episode"
            :default-episode-no="nextEpisodeNo"
            @updated="handleEpisodeUpdated"
            @open-storyboard="openStage('storyboard')"
          />
          <ComicStoryboardStage
            v-else-if="activeStage === 'storyboard' && episode"
            :project-id="projectId"
            :episode="episode"
            @updated="handleEpisodeUpdated"
            @open-assets="openStage('assets')"
          />
          <ComicAssetsStage
            v-else-if="activeStage === 'assets' && episode"
            :project-id="projectId"
            :project="project"
            :episode="episode"
            @updated="handleEpisodeUpdated"
            @refresh-project="refreshProject"
            @open-shots="openStage('shots')"
          />
          <ComicShotsStage
            v-else-if="activeStage === 'shots' && episode"
            :project-id="projectId"
            :episode="episode"
            @updated="handleEpisodeUpdated"
            @reload-episode="reloadEpisode"
            @open-delivery="openStage('delivery')"
          />
          <ComicDeliveryStage
            v-else-if="activeStage === 'delivery' && episode"
            :project-id="projectId"
            :episode="episode"
            :aspect-ratio="project.aspectRatio"
          />
          <section v-else class="flex min-h-80 flex-col items-center justify-center text-center">
            <Clapperboard class="h-8 w-8 text-muted-foreground" />
            <h2 class="mt-4 font-semibold">此阶段尚未解锁</h2>
            <p class="mt-2 text-sm text-muted-foreground">完成前一阶段并通过确认门槛后即可继续。</p>
          </section>
        </div>
      </div>
    </template>
  </main>
</template>
