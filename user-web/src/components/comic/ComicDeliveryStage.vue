<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue"
import {
  Captions,
  Download,
  Headphones,
  Loader2,
  Play,
  RefreshCw,
  Volume2,
} from "lucide-vue-next"
import {
  comicProjectApi,
  type ComicAssemblyBatch,
  type ComicEntityId,
  type ComicEpisodeDetail,
} from "@/api/comicProjectApi"
import { ApiBusinessError } from "@/api/client"
import { useAuthStore } from "@/store/authStore"
import {
  retainComicClientRequestAttempt,
  type ComicClientRequestAttempt,
} from "@/utils/comicProject"
import { randomUUID } from "@/utils/randomUUID"

const props = defineProps<{
  projectId: ComicEntityId
  aspectRatio?: string | null
  episode: ComicEpisodeDetail
}>()

const auth = useAuthStore()
const assembly = ref<ComicAssemblyBatch | null>(null)
const billingConfirmed = ref(false)
const submitting = ref(false)
const refreshing = ref(false)
const loadingLatest = ref(false)
const error = ref<string | null>(null)
const assemblyLaunchAttempt = ref<ComicClientRequestAttempt | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let latestController: AbortController | null = null

const currentSelectedAttemptIds = computed(() => (props.episode.shots ?? [])
  .filter((shot) => shot.selectedAttemptId != null)
  .sort((left, right) => left.sequenceNo - right.sequenceNo)
  .map((shot) => Number(shot.selectedAttemptId)))
const selectedShotCount = computed(() => currentSelectedAttemptIds.value.length)
const readyToCompose = computed(() => selectedShotCount.value === (props.episode.shots?.length ?? 0) && selectedShotCount.value > 0)
const assemblyStatus = computed(() => String(assembly.value?.status ?? "").toUpperCase())
const assemblyActive = computed(() => [
  "CREATING",
  "DRAFT",
  "QUEUED",
  "DISPATCHING",
  "RUNNING",
  "AWAITING_USER",
  "AWAITING_FUNDS",
  "CANCELLING",
].includes(assemblyStatus.value))
const assemblyFailed = computed(() => ["FAILED", "CANCELLED", "TIMEOUT"].includes(assemblyStatus.value))
const assemblyHasVideo = computed(() => assemblyStatus.value === "SUCCESS" && Boolean(assembly.value?.videoUrl))
const assemblyOutdated = computed(() => {
  if (!assemblyHasVideo.value || !Array.isArray(assembly.value?.selectedAttemptIds)) return false
  const previousIds = assembly.value.selectedAttemptIds.map(Number)
  const currentIds = currentSelectedAttemptIds.value
  return previousIds.length !== currentIds.length || previousIds.some((id, index) => id !== currentIds[index])
})
const assemblySuccess = computed(() => assemblyHasVideo.value && !assemblyOutdated.value)
const progress = computed(() => {
  if (assembly.value?.progress == null) return null
  return Math.max(0, Math.min(100, Math.round(assembly.value.progress)))
})
const assemblyStatusTitle = computed(() => {
  if (assemblyStatus.value === "AWAITING_FUNDS") return "合成任务等待补充算力"
  if (assemblyStatus.value === "AWAITING_USER") return "合成任务等待确认"
  if (assemblyStatus.value === "CANCELLING") return "正在取消合成任务"
  return "正在合成最终成片"
})
const assemblyInputSignature = computed(() => JSON.stringify({
  operation: "comic.assembly.batch",
  projectId: String(props.projectId),
  episodeId: String(props.episode.id),
  episodeRevision: props.episode.revision ?? 0,
  selectedAttemptIds: currentSelectedAttemptIds.value,
}))

function adoptAssembly(nextAssembly: ComicAssemblyBatch | null) {
  assembly.value = nextAssembly
  const loadedClientRequestId = nextAssembly?.clientRequestId
  if (loadedClientRequestId && assemblyLaunchAttempt.value?.clientRequestId === loadedClientRequestId) {
    assemblyLaunchAttempt.value = null
  }
}

watch(
  () => [props.episode.id, props.episode.latestAssemblyBatch] as const,
  ([episodeId, providedBatch], previous) => {
    const episodeChanged = previous?.[0] !== episodeId
    if (episodeChanged) {
      clearPoll()
      latestController?.abort()
      latestController = null
      adoptAssembly(null)
      assemblyLaunchAttempt.value = null
      billingConfirmed.value = false
      error.value = null
    }
    if (providedBatch) {
      latestController?.abort()
      latestController = null
      loadingLatest.value = false
      adoptAssembly(providedBatch)
      if (assemblyActive.value) schedulePoll(800)
      return
    }
    if (episodeChanged) void loadLatestAssembly(episodeId)
  },
  { immediate: true, deep: true },
)

watch(assemblyInputSignature, () => {
  assemblyLaunchAttempt.value = null
})

function clearPoll() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function schedulePoll(delay = 2_500) {
  clearPoll()
  if (!assembly.value || !assemblyActive.value) return
  pollTimer = setTimeout(() => void refreshAssembly(true), delay)
}

function isNotFound(loadError: unknown): boolean {
  return loadError instanceof ApiBusinessError
    && String(loadError.code).toUpperCase().includes("NOT_FOUND")
}

async function loadLatestAssembly(episodeId: ComicEntityId) {
  latestController?.abort()
  const controller = new AbortController()
  latestController = controller
  loadingLatest.value = true
  try {
    const latest = await comicProjectApi.getLatestAssemblyBatch(
      props.projectId,
      episodeId,
      { token: auth.token, signal: controller.signal },
    )
    if (controller.signal.aborted || props.episode.id !== episodeId) return
    adoptAssembly(latest)
    if (assemblyActive.value) schedulePoll(800)
  } catch (loadError) {
    if (controller.signal.aborted || props.episode.id !== episodeId) return
    if (isNotFound(loadError)) {
      adoptAssembly(null)
    } else {
      error.value = loadError instanceof Error ? loadError.message : "合成记录加载失败"
    }
  } finally {
    if (latestController === controller) {
      latestController = null
      loadingLatest.value = false
    }
  }
}

async function refreshAssembly(silent = false) {
  if (!assembly.value || refreshing.value) return
  refreshing.value = true
  if (!silent) error.value = null
  try {
    adoptAssembly(await comicProjectApi.getAssemblyBatch(
      props.projectId,
      props.episode.id,
      assembly.value.id,
      { token: auth.token },
    ))
    if (assemblyActive.value) schedulePoll()
  } catch (refreshError) {
    if (!silent) error.value = refreshError instanceof Error ? refreshError.message : "合成状态刷新失败"
    schedulePoll(5_000)
  } finally {
    refreshing.value = false
  }
}

async function startAssembly() {
  if (submitting.value || !readyToCompose.value || !billingConfirmed.value) return
  submitting.value = true
  error.value = null
  try {
    assemblyLaunchAttempt.value = retainComicClientRequestAttempt(
      assemblyLaunchAttempt.value,
      assemblyInputSignature.value,
      randomUUID,
    )
    adoptAssembly(await comicProjectApi.createAssemblyBatch(props.projectId, props.episode.id, {
      clientRequestId: assemblyLaunchAttempt.value.clientRequestId,
      confirmed: true,
    }, { token: auth.token }))
    assemblyLaunchAttempt.value = null
    billingConfirmed.value = false
    schedulePoll(800)
  } catch (assemblyError) {
    error.value = assemblyError instanceof Error ? assemblyError.message : "成片合成启动失败"
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  clearPoll()
  latestController?.abort()
})
</script>

<template>
  <section>
    <header class="flex flex-col gap-4 border-b border-border pb-5 sm:flex-row sm:items-start sm:justify-between">
      <div>
        <p class="text-xs font-medium text-primary">阶段 5</p>
        <h2 class="mt-1 text-xl font-semibold">合成交付</h2>
        <p class="mt-2 text-sm leading-6 text-muted-foreground">使用每个镜头已选版本，合并画面、音轨和字幕，输出最终成片。</p>
      </div>
      <button v-if="assembly" type="button" class="inline-flex h-9 shrink-0 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50" :disabled="refreshing" @click="refreshAssembly(false)">
        <Loader2 v-if="refreshing" class="h-4 w-4 animate-spin" /><RefreshCw v-else class="h-4 w-4" />刷新
      </button>
    </header>

    <p v-if="error" class="mt-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">{{ error }}</p>

    <section v-if="loadingLatest && !assembly" class="py-12 text-center">
      <Loader2 class="mx-auto h-7 w-7 animate-spin text-primary" />
      <p class="mt-3 text-sm text-muted-foreground">正在读取最近的合成记录</p>
    </section>

    <template v-else>
      <section v-if="assemblyHasVideo" class="py-6">
        <div class="mx-auto max-w-4xl overflow-hidden rounded-lg border border-border bg-black">
          <video :src="assembly?.videoUrl ?? ''" controls preload="metadata" class="aspect-video w-full object-contain" />
        </div>
        <div class="mt-5 flex flex-col gap-3 border-y border-border py-4 sm:flex-row sm:items-center sm:justify-between">
          <div><h3 class="font-medium">{{ episode.title }}</h3><p class="mt-1 text-xs text-muted-foreground">{{ episode.shots?.length ?? 0 }} 个镜头 · {{ aspectRatio || '项目默认比例' }}</p></div>
          <div class="flex flex-wrap gap-2">
            <a v-if="assembly?.subtitleUrl" :href="assembly.subtitleUrl" target="_blank" rel="noopener noreferrer" class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary"><Captions class="h-4 w-4" />字幕文件</a>
            <a :href="assembly?.videoUrl ?? ''" target="_blank" rel="noopener noreferrer" download class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground"><Download class="h-4 w-4" />下载成片</a>
          </div>
        </div>
        <p v-if="assemblyOutdated" class="mt-4 rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-sm leading-6 text-warning">
          当前镜头版本已经变化，这个成片已过期。旧文件仍可查看或下载，重新合成后才会包含最新镜头。
        </p>
      </section>

      <template v-if="!assemblySuccess">
      <section v-if="assemblyActive" class="border-b border-border py-8 text-center">
        <Loader2 class="mx-auto h-8 w-8 animate-spin text-primary" />
        <h3 class="mt-4 font-medium">{{ assemblyStatusTitle }}</h3>
        <p class="mt-2 text-sm text-muted-foreground">音轨、字幕和镜头会按时间线合并。</p>
        <div class="mx-auto mt-5 h-2 max-w-xl overflow-hidden rounded-full bg-secondary">
          <div v-if="progress != null" class="h-full bg-primary transition-[width]" :style="{ width: `${progress}%` }" />
          <div v-else class="h-full w-1/2 animate-pulse rounded-full bg-primary" />
        </div>
        <p class="mt-2 text-xs text-muted-foreground">{{ progress == null ? '正在处理，请稍候' : `${progress}%` }}</p>
      </section>

      <div v-else class="py-6">
        <div v-if="assemblyFailed" class="mb-6 rounded-md border border-destructive/30 bg-destructive/10 px-4 py-3">
          <h3 class="text-sm font-medium text-destructive">上次合成未完成</h3>
          <p class="mt-1 text-sm leading-6 text-destructive">{{ assembly?.errorMessage || '可以检查镜头版本后重新合成。' }}</p>
        </div>

        <div class="grid gap-8 lg:grid-cols-[minmax(0,1fr)_300px]">
        <section>
          <h3 class="text-base font-semibold">本次合成内容</h3>
          <div class="mt-4 divide-y divide-border border-y border-border">
            <div class="flex items-center gap-4 py-4">
              <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary"><Volume2 class="h-4 w-4" /></span>
              <span class="min-w-0 flex-1"><span class="block text-sm font-medium">镜头视频</span><span class="mt-1 block text-xs leading-5 text-muted-foreground">按分镜顺序使用当前选中的成功版本</span></span>
              <span class="shrink-0 text-xs text-muted-foreground">自动</span>
            </div>
            <div class="flex items-center gap-4 py-4">
              <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-success/10 text-success"><Headphones class="h-4 w-4" /></span>
              <span class="min-w-0 flex-1"><span class="block text-sm font-medium">配音与音轨</span><span class="mt-1 block text-xs leading-5 text-muted-foreground">使用镜头生成阶段已有的音频结果</span></span>
              <span class="shrink-0 text-xs text-muted-foreground">自动</span>
            </div>
            <div class="flex items-center gap-4 py-4">
              <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-warning/10 text-warning"><Captions class="h-4 w-4" /></span>
              <span class="min-w-0 flex-1"><span class="block text-sm font-medium">独立字幕</span><span class="mt-1 block text-xs leading-5 text-muted-foreground">随成片输出可单独下载的 SRT 文件</span></span>
              <span class="shrink-0 text-xs text-muted-foreground">自动</span>
            </div>
          </div>
        </section>

        <aside class="border-t border-border pt-6 lg:border-l lg:border-t-0 lg:pl-6 lg:pt-0">
          <h3 class="text-sm font-semibold">合成检查</h3>
          <dl class="mt-4 space-y-3 text-sm">
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">已选镜头版本</dt><dd>{{ selectedShotCount }} / {{ episode.shots?.length ?? 0 }}</dd></div>
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">画面比例</dt><dd>{{ aspectRatio || '默认' }}</dd></div>
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">音频与字幕</dt><dd>按工作流默认配置</dd></div>
          </dl>
          <p v-if="!readyToCompose" class="mt-4 text-xs leading-5 text-warning">仍有镜头没有选择成功版本，请返回“镜头生成”处理。</p>
          <label class="mt-5 flex items-start gap-2 text-sm leading-5 text-muted-foreground">
            <input v-model="billingConfirmed" type="checkbox" class="mt-1 h-4 w-4 accent-primary" />
            <span>确认使用当前镜头版本并承担本次合成费用。</span>
          </label>
          <button type="button" class="mt-4 inline-flex h-10 w-full items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-50" :disabled="submitting || !readyToCompose || !billingConfirmed" @click="startAssembly">
            <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" /><Play v-else class="h-4 w-4" />{{ submitting ? '正在启动' : assemblyOutdated || assemblyFailed ? '重新合成' : '开始合成' }}
          </button>
        </aside>
        </div>
      </div>
      </template>

      <section v-if="!assembly" class="mt-2 border-t border-border pt-5">
        <div class="flex items-center gap-3 text-sm text-muted-foreground"><Headphones class="h-4 w-4" /><span>合成结果会保留视频和独立字幕文件。</span></div>
      </section>
    </template>
  </section>
</template>
