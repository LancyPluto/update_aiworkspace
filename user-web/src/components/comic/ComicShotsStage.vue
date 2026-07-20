<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue"
import {
  Check,
  CircleAlert,
  Clapperboard,
  Loader2,
  Play,
  RefreshCw,
  RotateCcw,
  Video,
} from "lucide-vue-next"
import {
  comicProjectApi,
  type ComicEntityId,
  type ComicEpisodeDetail,
  type ComicGenerationBatch,
  type ComicShot,
  type ComicShotAttempt,
} from "@/api/comicProjectApi"
import { ApiBusinessError } from "@/api/client"
import { useAuthStore } from "@/store/authStore"
import { comicAttemptMedia } from "@/utils/comicProject"
import { randomUUID } from "@/utils/randomUUID"

const props = defineProps<{
  projectId: ComicEntityId
  episode: ComicEpisodeDetail
}>()

const emit = defineEmits<{
  updated: [episode: ComicEpisodeDetail]
  "reload-episode": []
  "open-delivery": []
}>()

const auth = useAuthStore()
const batch = ref<ComicGenerationBatch | null>(null)
const selectedShotIds = ref<number[]>([])
const maxParallelism = ref(4)
const billingConfirmed = ref(false)
const submitting = ref(false)
const refreshing = ref(false)
const actionShotId = ref<number | null>(null)
const error = ref<string | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let latestBatchController: AbortController | null = null

const shots = computed(() => (props.episode.shots ?? []).filter((shot): shot is ComicShot & { id: number } => shot.id != null))
const batchActive = computed(() => ["DRAFT", "QUEUED", "RUNNING"].includes(String(batch.value?.status ?? "").toUpperCase()))
const batchProgress = computed(() => {
  const total = batch.value?.totalCount ?? shots.value.length
  const complete = (batch.value?.succeededCount ?? 0) + (batch.value?.failedCount ?? 0)
  return total > 0 ? Math.round((complete / total) * 100) : 0
})
const batchSucceeded = computed(() => String(batch.value?.status ?? "").toUpperCase() === "SUCCESS")

watch(
  () => props.episode.id,
  (episodeId) => {
    clearPoll()
    latestBatchController?.abort()
    error.value = null
    batch.value = props.episode.latestGenerationBatch ?? null
    selectedShotIds.value = (props.episode.shots ?? []).flatMap((shot) => shot.id == null ? [] : [shot.id])
    if (batchActive.value) schedulePoll(800)
    else if (!batch.value) void loadLatestBatch(episodeId)
  },
  { immediate: true },
)

function attemptsForShot(shotId: number): ComicShotAttempt[] {
  return (batch.value?.attempts ?? [])
    .filter((attempt) => attempt.shotId === shotId)
    .sort((left, right) => right.attemptNo - left.attemptNo)
}

function activeAttempt(shot: ComicShot): ComicShotAttempt | null {
  const attempts = attemptsForShot(shot.id as number)
  return attempts.find((attempt) => attempt.id === shot.selectedAttemptId) ?? attempts[0] ?? null
}

function successfulAttempts(shotId: number): ComicShotAttempt[] {
  return attemptsForShot(shotId).filter((attempt) => String(attempt.status).toUpperCase() === "SUCCESS")
}

function attemptStatusLabel(attempt?: ComicShotAttempt | null): string {
  const status = String(attempt?.status ?? "PENDING").toUpperCase()
  if (status === "SUCCESS") return "已完成"
  if (status === "FAILED") return "生成失败"
  if (status === "RUNNING") return "生成中"
  if (status === "QUEUED") return "排队中"
  if (status === "CANCELLED") return "已取消"
  return "待生成"
}

function attemptStatusClass(attempt?: ComicShotAttempt | null): string {
  const status = String(attempt?.status ?? "PENDING").toUpperCase()
  if (status === "SUCCESS") return "bg-success/10 text-success"
  if (status === "FAILED") return "bg-destructive/10 text-destructive"
  if (status === "RUNNING" || status === "QUEUED") return "bg-primary/10 text-primary"
  return "bg-secondary text-muted-foreground"
}

function toggleShot(shotId: number) {
  if (batchActive.value) return
  selectedShotIds.value = selectedShotIds.value.includes(shotId)
    ? selectedShotIds.value.filter((id) => id !== shotId)
    : [...selectedShotIds.value, shotId]
}

function selectAll() {
  selectedShotIds.value = selectedShotIds.value.length === shots.value.length ? [] : shots.value.map((shot) => shot.id)
}

function clearPoll() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function schedulePoll(delay = 2_500) {
  clearPoll()
  if (!batchActive.value || !batch.value) return
  pollTimer = setTimeout(() => void refreshBatch(true), delay)
}

function isMissingBatch(loadError: unknown): boolean {
  return loadError instanceof ApiBusinessError && String(loadError.code).toUpperCase().includes("NOT_FOUND")
}

async function loadLatestBatch(episodeId: number) {
  latestBatchController?.abort()
  const controller = new AbortController()
  latestBatchController = controller
  try {
    const latest = await comicProjectApi.getLatestGenerationBatch(props.projectId, episodeId, {
      token: auth.token,
      signal: controller.signal,
    })
    if (controller.signal.aborted || props.episode.id !== episodeId) return
    batch.value = latest
    if (batchActive.value) schedulePoll(800)
  } catch (loadError) {
    if (controller.signal.aborted || props.episode.id !== episodeId || isMissingBatch(loadError)) return
    error.value = loadError instanceof Error ? loadError.message : "最新生成批次加载失败"
  } finally {
    if (latestBatchController === controller) latestBatchController = null
  }
}

async function refreshBatch(silent = false) {
  if (!batch.value || refreshing.value) return
  refreshing.value = true
  if (!silent) error.value = null
  try {
    let refreshed = await comicProjectApi.getGenerationBatch(props.projectId, props.episode.id, batch.value.id, { token: auth.token })
    const pending = refreshed.pendingCount ?? 0
    const running = refreshed.runningCount ?? 0
    if (pending > 0 && running < (refreshed.maxParallelism ?? maxParallelism.value)) {
      refreshed = await comicProjectApi.dispatchGenerationBatch(props.projectId, props.episode.id, refreshed.id, { token: auth.token })
    }
    batch.value = refreshed
    if (String(refreshed.status).toUpperCase() === "SUCCESS") {
      emit("reload-episode")
      clearPoll()
    } else {
      schedulePoll()
    }
  } catch (refreshError) {
    if (!silent) error.value = refreshError instanceof Error ? refreshError.message : "批次状态刷新失败"
    schedulePoll(5_000)
  } finally {
    refreshing.value = false
  }
}

async function startBatch() {
  if (submitting.value || !billingConfirmed.value || !selectedShotIds.value.length) return
  submitting.value = true
  error.value = null
  try {
    batch.value = await comicProjectApi.createGenerationBatch(props.projectId, props.episode.id, {
      clientRequestId: randomUUID(),
      toolCode: "ai_comic_drama_agent",
      maxParallelism: maxParallelism.value,
      confirmed: true,
      shotIds: selectedShotIds.value,
    }, { token: auth.token })
    schedulePoll()
  } catch (startError) {
    error.value = startError instanceof Error ? startError.message : "镜头批次启动失败"
  } finally {
    submitting.value = false
  }
}

async function retryShot(shotId: number) {
  if (actionShotId.value != null) return
  actionShotId.value = shotId
  error.value = null
  try {
    batch.value = await comicProjectApi.retryShot(props.projectId, props.episode.id, shotId, {
      clientRequestId: randomUUID(),
      toolCode: "ai_comic_drama_agent",
    }, { token: auth.token })
    schedulePoll(800)
  } catch (retryError) {
    error.value = retryError instanceof Error ? retryError.message : "镜头重试失败"
  } finally {
    actionShotId.value = null
  }
}

async function selectAttempt(shot: ComicShot & { id: number }, attemptId: number) {
  if (actionShotId.value != null || shot.selectedAttemptId === attemptId) return
  actionShotId.value = shot.id
  error.value = null
  try {
    const result = await comicProjectApi.selectShotAttempt(
      props.projectId,
      props.episode.id,
      shot.id,
      attemptId,
      props.episode.revision ?? 0,
      { token: auth.token },
    )
    emit("updated", result)
  } catch (selectError) {
    error.value = selectError instanceof Error ? selectError.message : "镜头版本选择失败"
  } finally {
    actionShotId.value = null
  }
}

onBeforeUnmount(() => {
  clearPoll()
  latestBatchController?.abort()
})
</script>

<template>
  <section>
    <header class="flex flex-col gap-4 border-b border-border pb-5 xl:flex-row xl:items-start xl:justify-between">
      <div>
        <p class="text-xs font-medium text-primary">阶段 4</p>
        <h2 class="mt-1 text-xl font-semibold">镜头生成</h2>
        <p class="mt-2 text-sm leading-6 text-muted-foreground">按并发上限分批生成；失败镜头可单独重试，成功版本由你选择。</p>
      </div>
      <button v-if="batch" type="button" class="inline-flex h-9 shrink-0 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50" :disabled="refreshing" @click="refreshBatch(false)">
        <Loader2 v-if="refreshing" class="h-4 w-4 animate-spin" /><RefreshCw v-else class="h-4 w-4" /> 刷新状态
      </button>
    </header>

    <p v-if="error" class="mt-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">{{ error }}</p>

    <section v-if="!batch || !batchActive" class="border-b border-border py-5">
      <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_280px]">
        <div>
          <div class="flex items-center justify-between gap-3"><h3 class="text-sm font-semibold">本批镜头</h3><button type="button" class="text-xs text-primary hover:underline" @click="selectAll">{{ selectedShotIds.length === shots.length ? '取消全选' : '全部选择' }}</button></div>
          <div class="mt-3 flex flex-wrap gap-2">
            <button v-for="shot in shots" :key="shot.id" type="button" class="inline-flex h-9 items-center gap-2 rounded-md border px-3 text-sm transition" :class="selectedShotIds.includes(shot.id) ? 'border-primary/40 bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-secondary'" @click="toggleShot(shot.id)">
              <Check v-if="selectedShotIds.includes(shot.id)" class="h-3.5 w-3.5" />镜头 {{ shot.sequenceNo }}
            </button>
          </div>
        </div>
        <div class="border-t border-border pt-4 lg:border-l lg:border-t-0 lg:pl-5 lg:pt-0">
          <label class="block text-sm font-medium">同时生成 {{ maxParallelism }} 个</label>
          <input v-model.number="maxParallelism" type="range" min="1" max="8" step="1" class="mt-3 w-full accent-primary" :disabled="batchActive" />
          <label class="mt-4 flex items-start gap-2 text-sm leading-5 text-muted-foreground">
            <input v-model="billingConfirmed" type="checkbox" class="mt-1 h-4 w-4 accent-primary" />
            <span>确认生成所选镜头，并按实际模型调用结算算力。</span>
          </label>
          <button type="button" class="mt-4 inline-flex h-10 w-full items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-50" :disabled="submitting || !billingConfirmed || !selectedShotIds.length" @click="startBatch">
            <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" /><Play v-else class="h-4 w-4" />{{ submitting ? '正在创建批次' : '开始批量生成' }}
          </button>
        </div>
      </div>
    </section>

    <section v-if="batch" class="border-b border-border py-5">
      <div class="flex flex-wrap items-center justify-between gap-3 text-sm">
        <div class="flex flex-wrap gap-x-5 gap-y-2"><span>成功 <strong class="text-success">{{ batch.succeededCount ?? 0 }}</strong></span><span>生成中 <strong>{{ batch.runningCount ?? 0 }}</strong></span><span>待处理 <strong>{{ batch.pendingCount ?? 0 }}</strong></span><span>失败 <strong class="text-destructive">{{ batch.failedCount ?? 0 }}</strong></span></div>
        <span class="text-xs text-muted-foreground">并发上限 {{ batch.maxParallelism ?? maxParallelism }}</span>
      </div>
      <div class="mt-4 h-2 overflow-hidden rounded-full bg-secondary"><div class="h-full bg-primary transition-[width]" :style="{ width: `${batchProgress}%` }" /></div>
      <p class="mt-2 text-right text-xs text-muted-foreground">{{ batchProgress }}%</p>
    </section>

    <div class="divide-y divide-border">
      <article v-for="shot in shots" :key="shot.id" class="grid gap-4 py-5 lg:grid-cols-[180px_minmax(0,1fr)_220px] lg:items-start">
        <div class="aspect-video overflow-hidden rounded-lg border border-border bg-black">
          <video v-if="comicAttemptMedia(activeAttempt(shot)).videoUrl" :src="comicAttemptMedia(activeAttempt(shot)).videoUrl ?? ''" controls preload="metadata" class="h-full w-full object-contain" />
          <div v-else class="flex h-full flex-col items-center justify-center gap-2 text-xs text-muted-foreground"><Video class="h-5 w-5" />镜头 {{ shot.sequenceNo }}</div>
        </div>
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2"><h3 class="font-medium">镜头 {{ shot.sequenceNo }}</h3><span class="rounded-md px-2 py-1 text-[11px]" :class="attemptStatusClass(activeAttempt(shot))">{{ attemptStatusLabel(activeAttempt(shot)) }}</span></div>
          <p class="mt-2 line-clamp-2 text-sm leading-6 text-muted-foreground">{{ shot.visualDescription }}</p>
          <p v-if="activeAttempt(shot)?.errorMessage" class="mt-2 flex items-start gap-2 text-xs leading-5 text-destructive"><CircleAlert class="mt-0.5 h-3.5 w-3.5 shrink-0" />{{ activeAttempt(shot)?.errorMessage }}</p>
        </div>
        <div>
          <label class="block text-xs text-muted-foreground">采用版本
            <select :value="shot.selectedAttemptId ?? ''" class="mt-2 h-9 w-full rounded-md border border-input bg-background px-2 text-sm text-foreground" :disabled="actionShotId === shot.id || !successfulAttempts(shot.id).length" @change="selectAttempt(shot, Number(($event.target as HTMLSelectElement).value))">
              <option value="">自动选择首个成功版本</option><option v-for="attempt in successfulAttempts(shot.id)" :key="attempt.id" :value="attempt.id">V{{ attempt.attemptNo }} · 已完成</option>
            </select>
          </label>
          <button type="button" class="mt-3 inline-flex h-9 w-full items-center justify-center gap-2 rounded-md border border-border text-sm hover:bg-secondary disabled:opacity-50" :disabled="actionShotId != null || batchActive" @click="retryShot(shot.id)">
            <Loader2 v-if="actionShotId === shot.id" class="h-4 w-4 animate-spin" /><RotateCcw v-else class="h-4 w-4" />单独重试
          </button>
        </div>
      </article>
    </div>

    <div v-if="batchSucceeded" class="mt-5 flex flex-col gap-3 border-t border-border pt-5 sm:flex-row sm:items-center sm:justify-between">
      <p class="text-sm text-success">全部镜头已生成，可以进入合成交付。</p>
      <button type="button" class="inline-flex h-10 items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground" @click="emit('open-delivery')"><Clapperboard class="h-4 w-4" />进入合成</button>
    </div>
  </section>
</template>
