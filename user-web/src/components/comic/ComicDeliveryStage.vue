<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue"
import {
  Captions,
  Download,
  Headphones,
  Loader2,
  Music2,
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
import { useAuthStore } from "@/store/authStore"
import { randomUUID } from "@/utils/randomUUID"

const props = defineProps<{
  projectId: ComicEntityId
  aspectRatio?: string | null
  episode: ComicEpisodeDetail
}>()

const auth = useAuthStore()
const assembly = ref<ComicAssemblyBatch | null>(null)
const voiceEnabled = ref(true)
const bgmEnabled = ref(true)
const subtitlesEnabled = ref(true)
const billingConfirmed = ref(false)
const assemblyEndpointAvailable = false
const submitting = ref(false)
const refreshing = ref(false)
const error = ref<string | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

const selectedShotCount = computed(() => (props.episode.shots ?? []).filter((shot) => shot.selectedAttemptId != null).length)
const readyToCompose = computed(() => selectedShotCount.value === (props.episode.shots?.length ?? 0) && selectedShotCount.value > 0)
const assemblyActive = computed(() => ["DRAFT", "QUEUED", "RUNNING"].includes(String(assembly.value?.status ?? "").toUpperCase()))
const assemblySuccess = computed(() => String(assembly.value?.status ?? "").toUpperCase() === "SUCCESS" && Boolean(assembly.value?.videoUrl))
const progress = computed(() => Math.max(0, Math.min(100, assembly.value?.progress ?? (assemblySuccess.value ? 100 : 0))))

watch(
  () => props.episode.latestAssemblyBatch,
  (value) => {
    assembly.value = value ?? null
    if (assemblyActive.value) schedulePoll(800)
  },
  { immediate: true, deep: true },
)

function clearPoll() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function schedulePoll(delay = 2_500) {
  clearPoll()
  if (!assembly.value || !assemblyActive.value) return
  pollTimer = setTimeout(() => void refreshAssembly(true), delay)
}

async function refreshAssembly(silent = false) {
  if (!assembly.value || refreshing.value) return
  refreshing.value = true
  if (!silent) error.value = null
  try {
    assembly.value = await comicProjectApi.getAssemblyBatch(
      props.projectId,
      props.episode.id,
      assembly.value.id,
      { token: auth.token },
    )
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
    assembly.value = await comicProjectApi.createAssemblyBatch(props.projectId, props.episode.id, {
      clientRequestId: randomUUID(),
      voiceEnabled: voiceEnabled.value,
      bgmEnabled: bgmEnabled.value,
      subtitlesEnabled: subtitlesEnabled.value,
      aspectRatio: props.aspectRatio ?? undefined,
      confirmed: true,
    }, { token: auth.token })
    schedulePoll(800)
  } catch (assemblyError) {
    error.value = assemblyError instanceof Error ? assemblyError.message : "成片合成启动失败"
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(clearPoll)
</script>

<template>
  <section>
    <header class="flex flex-col gap-4 border-b border-border pb-5 sm:flex-row sm:items-start sm:justify-between">
      <div>
        <p class="text-xs font-medium text-primary">阶段 5</p>
        <h2 class="mt-1 text-xl font-semibold">合成交付</h2>
        <p class="mt-2 text-sm leading-6 text-muted-foreground">使用每个镜头已选版本，生成配音、字幕并合成最终成片。</p>
      </div>
      <button v-if="assembly" type="button" class="inline-flex h-9 shrink-0 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50" :disabled="refreshing" @click="refreshAssembly(false)">
        <Loader2 v-if="refreshing" class="h-4 w-4 animate-spin" /><RefreshCw v-else class="h-4 w-4" />刷新
      </button>
    </header>

    <p v-if="error" class="mt-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">{{ error }}</p>

    <section v-if="assemblySuccess" class="py-6">
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
    </section>

    <template v-else>
      <section v-if="assemblyActive" class="border-b border-border py-8 text-center">
        <Loader2 class="mx-auto h-8 w-8 animate-spin text-primary" />
        <h3 class="mt-4 font-medium">正在合成最终成片</h3>
        <p class="mt-2 text-sm text-muted-foreground">音轨、字幕和镜头会按时间线合并。</p>
        <div class="mx-auto mt-5 h-2 max-w-xl overflow-hidden rounded-full bg-secondary"><div class="h-full bg-primary transition-[width]" :style="{ width: `${progress}%` }" /></div>
        <p class="mt-2 text-xs text-muted-foreground">{{ progress }}%</p>
      </section>

      <div v-else class="grid gap-8 py-6 lg:grid-cols-[minmax(0,1fr)_300px]">
        <section>
          <h3 class="text-base font-semibold">成片设置</h3>
          <div class="mt-4 divide-y divide-border border-y border-border">
            <label class="flex items-center gap-4 py-4">
              <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary"><Volume2 class="h-4 w-4" /></span>
              <span class="min-w-0 flex-1"><span class="block text-sm font-medium">角色配音</span><span class="mt-1 block text-xs text-muted-foreground">按角色声线生成并混合对白音轨</span></span>
              <input v-model="voiceEnabled" type="checkbox" class="h-5 w-5 accent-primary" />
            </label>
            <label class="flex items-center gap-4 py-4">
              <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-success/10 text-success"><Music2 class="h-4 w-4" /></span>
              <span class="min-w-0 flex-1"><span class="block text-sm font-medium">BGM 与音效</span><span class="mt-1 block text-xs text-muted-foreground">按分镜提示控制情绪、响度和转场</span></span>
              <input v-model="bgmEnabled" type="checkbox" class="h-5 w-5 accent-primary" />
            </label>
            <label class="flex items-center gap-4 py-4">
              <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-warning/10 text-warning"><Captions class="h-4 w-4" /></span>
              <span class="min-w-0 flex-1"><span class="block text-sm font-medium">字幕</span><span class="mt-1 block text-xs text-muted-foreground">生成时间轴字幕并烧录到成片</span></span>
              <input v-model="subtitlesEnabled" type="checkbox" class="h-5 w-5 accent-primary" />
            </label>
          </div>
        </section>

        <aside class="border-t border-border pt-6 lg:border-l lg:border-t-0 lg:pl-6 lg:pt-0">
          <h3 class="text-sm font-semibold">合成检查</h3>
          <dl class="mt-4 space-y-3 text-sm">
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">已选镜头版本</dt><dd>{{ selectedShotCount }} / {{ episode.shots?.length ?? 0 }}</dd></div>
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">画面比例</dt><dd>{{ aspectRatio || '默认' }}</dd></div>
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">音频</dt><dd>{{ voiceEnabled || bgmEnabled ? '生成' : '静音' }}</dd></div>
          </dl>
          <p v-if="!readyToCompose" class="mt-4 text-xs leading-5 text-warning">仍有镜头没有选择成功版本，请返回“镜头生成”处理。</p>
          <label class="mt-5 flex items-start gap-2 text-sm leading-5 text-muted-foreground">
            <input v-model="billingConfirmed" type="checkbox" class="mt-1 h-4 w-4 accent-primary" />
            <span>确认使用当前镜头版本和音频设置开始合成。</span>
          </label>
          <button type="button" class="mt-4 inline-flex h-10 w-full items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-50" :disabled="!assemblyEndpointAvailable || submitting || !readyToCompose || !billingConfirmed" @click="startAssembly">
            <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" /><Play v-else class="h-4 w-4" />{{ submitting ? '正在启动' : assemblyEndpointAvailable ? '开始合成' : '合成服务待接入' }}
          </button>
        </aside>
      </div>
    </template>

    <section v-if="!assembly" class="mt-2 border-t border-border pt-5">
      <div class="flex items-center gap-3 text-sm text-muted-foreground"><Headphones class="h-4 w-4" /><span>合成结果会保留视频和独立字幕文件。</span></div>
    </section>
  </section>
</template>
