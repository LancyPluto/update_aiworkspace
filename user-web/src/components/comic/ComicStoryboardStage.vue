<script setup lang="ts">
import { computed, ref, watch } from "vue"
import {
  ArrowDown,
  ArrowUp,
  Loader2,
  LockKeyhole,
  Plus,
  Save,
  Sparkles,
  Trash2,
  X,
} from "lucide-vue-next"
import {
  comicProjectApi,
  type ComicEntityId,
  type ComicEpisodeDetail,
  type ComicShot,
} from "@/api/comicProjectApi"
import { useAuthStore } from "@/store/authStore"
import {
  comicEpisodeGenerationStatus,
  formatComicDuration,
  moveComicShot,
  resequenceComicShots,
  validateComicStoryboard,
} from "@/utils/comicProject"

const props = defineProps<{
  projectId: ComicEntityId
  episode: ComicEpisodeDetail
}>()

const emit = defineEmits<{
  updated: [episode: ComicEpisodeDetail]
  "open-assets": []
}>()

const auth = useAuthStore()
const shots = ref<ComicShot[]>([])
const dirty = ref(false)
const submitting = ref(false)
const error = ref<string | null>(null)
const gateOpen = ref(false)

const validation = computed(() => validateComicStoryboard(shots.value))
const storyboardGenerating = computed(() => comicEpisodeGenerationStatus(props.episode.status) === "STORYBOARD_GENERATING")
const storyboardReadOnly = computed(() => Boolean(props.episode.storyboardLocked) || storyboardGenerating.value)

watch(
  () => props.episode,
  (episode) => {
    shots.value = resequenceComicShots((episode.shots ?? []).map((shot) => ({
      ...shot,
      characterVersionIds: [...(shot.characterVersionIds ?? [])],
    })))
    dirty.value = false
    error.value = null
  },
  { immediate: true, deep: true },
)

function markDirty() {
  dirty.value = true
}

function addShot() {
  if (shots.value.length >= 18 || storyboardReadOnly.value) return
  shots.value.push({
    sequenceNo: shots.value.length + 1,
    shotKey: `shot-${shots.value.length + 1}`,
    durationMs: 5_000,
    shotScale: "中景",
    cameraAngle: "平视",
    cameraMovement: "固定",
    emotion: "",
    visualDescription: "",
    dialogue: "",
    narration: "",
    soundEffect: "",
    bgmCue: "",
    firstFramePrompt: "",
    videoPrompt: "",
    negativePrompt: "",
    characterVersionIds: [],
    sceneVersionId: null,
  })
  dirty.value = true
}

function removeShot(index: number) {
  if (storyboardReadOnly.value) return
  shots.value = resequenceComicShots(shots.value.filter((_, shotIndex) => shotIndex !== index))
  dirty.value = true
}

function moveShot(index: number, direction: -1 | 1) {
  if (storyboardReadOnly.value) return
  shots.value = moveComicShot(shots.value, index, index + direction)
  dirty.value = true
}

async function generateStoryboard() {
  if (submitting.value || storyboardReadOnly.value) return
  submitting.value = true
  error.value = null
  try {
    const result = await comicProjectApi.generateStoryboard(
      props.projectId,
      props.episode.id,
      props.episode.revision ?? 0,
      { token: auth.token },
    )
    emit("updated", result)
  } catch (generateError) {
    error.value = generateError instanceof Error ? generateError.message : "AI 拆分分镜失败"
  } finally {
    submitting.value = false
  }
}

async function saveShots(): Promise<ComicEpisodeDetail | null> {
  if (submitting.value || storyboardReadOnly.value) return null
  submitting.value = true
  error.value = null
  try {
    const result = await comicProjectApi.replaceShots(props.projectId, props.episode.id, {
      shots: resequenceComicShots(shots.value),
    }, { token: auth.token })
    dirty.value = false
    emit("updated", result)
    return result
  } catch (saveError) {
    error.value = saveError instanceof Error ? saveError.message : "分镜保存失败"
    return null
  } finally {
    submitting.value = false
  }
}

async function lockStoryboard() {
  if (!validation.value.valid || dirty.value || submitting.value || storyboardReadOnly.value) return
  submitting.value = true
  error.value = null
  try {
    const result = await comicProjectApi.lockStoryboard(
      props.projectId,
      props.episode.id,
      props.episode.revision ?? 0,
      { token: auth.token },
    )
    gateOpen.value = false
    emit("updated", result)
    emit("open-assets")
  } catch (lockError) {
    error.value = lockError instanceof Error ? lockError.message : "分镜锁定失败"
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section>
    <header class="flex flex-col gap-4 border-b border-border pb-5 xl:flex-row xl:items-start xl:justify-between">
      <div>
        <p class="text-xs font-medium text-primary">阶段 2 · 第一道人审门槛</p>
        <h2 class="mt-1 text-xl font-semibold">分镜表</h2>
        <p class="mt-2 text-sm leading-6 text-muted-foreground">逐镜调整画面、运镜、对白和声音提示；确认后锁定正文。</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <button
          v-if="!episode.storyboardLocked"
          type="button"
          class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50"
          :disabled="submitting || storyboardGenerating"
          @click="generateStoryboard"
        >
          <Loader2 v-if="storyboardGenerating" class="h-4 w-4 animate-spin" aria-hidden="true" />
          <Sparkles v-else class="h-4 w-4" aria-hidden="true" />
          {{ storyboardGenerating ? "AI 正在拆分" : "AI 重新拆分" }}
        </button>
        <button
          v-if="!episode.storyboardLocked"
          type="button"
          class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50"
          :disabled="submitting || storyboardGenerating || !dirty"
          @click="saveShots"
        >
          <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" aria-hidden="true" />
          <Save v-else class="h-4 w-4" aria-hidden="true" />
          保存分镜
        </button>
        <button
          v-if="!episode.storyboardLocked"
          type="button"
          class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground disabled:opacity-50"
          :disabled="submitting || storyboardGenerating || dirty || !validation.valid"
          @click="gateOpen = true"
        >
          <LockKeyhole class="h-4 w-4" aria-hidden="true" />
          确认并锁定
        </button>
        <span v-else class="inline-flex h-9 items-center gap-2 rounded-md border border-success/30 bg-success/10 px-3 text-sm text-success">
          <LockKeyhole class="h-4 w-4" aria-hidden="true" />
          分镜已锁定
        </span>
      </div>
    </header>

    <div v-if="storyboardGenerating" class="mt-5 flex items-center gap-3 rounded-md border border-primary/25 bg-primary/8 px-4 py-3 text-sm text-primary" role="status" aria-live="polite">
      <Loader2 class="h-4 w-4 shrink-0 animate-spin" aria-hidden="true" />
      <span>AI 正在把剧本拆成分镜细节，完成后会自动刷新。</span>
    </div>

    <div class="flex flex-wrap items-center gap-x-5 gap-y-2 border-b border-border py-4 text-sm">
      <span><strong>{{ validation.shotCount }}</strong> 个镜头</span>
      <span><strong>{{ formatComicDuration(validation.totalDurationMs) }}</strong> 总时长</span>
      <span v-if="dirty" class="text-warning">有未保存修改</span>
      <span v-else class="text-muted-foreground">已同步</span>
    </div>

    <div v-if="!validation.valid && shots.length" class="border-b border-warning/25 bg-warning/5 px-4 py-3 text-sm text-warning">
      <span v-for="message in validation.errors.slice(0, 4)" :key="message" class="mr-4 inline-block">{{ message }}</span>
    </div>
    <p v-if="error" class="mt-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">{{ error }}</p>

    <div v-if="!shots.length" class="flex min-h-64 flex-col items-center justify-center border-b border-border text-center">
      <Sparkles class="h-8 w-8 text-muted-foreground" aria-hidden="true" />
      <h3 class="mt-4 text-base font-semibold">还没有分镜</h3>
      <p class="mt-2 text-sm text-muted-foreground">让 AI 从完整剧本拆分，或手动添加 6 至 18 个镜头。</p>
      <div class="mt-5 flex flex-wrap justify-center gap-2">
        <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground disabled:opacity-50" :disabled="storyboardGenerating" @click="generateStoryboard">
          <Loader2 v-if="storyboardGenerating" class="h-4 w-4 animate-spin" aria-hidden="true" />
          <Sparkles v-else class="h-4 w-4" aria-hidden="true" /> {{ storyboardGenerating ? "正在拆分" : "AI 拆分" }}
        </button>
        <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50" :disabled="storyboardGenerating" @click="addShot">
          <Plus class="h-4 w-4" aria-hidden="true" /> 手动添加
        </button>
      </div>
    </div>

    <div v-else class="mt-5">
      <div class="hidden grid-cols-[54px_210px_minmax(240px,1.4fr)_minmax(220px,1fr)_96px] gap-3 border-b border-border px-2 pb-2 text-xs font-medium text-muted-foreground xl:grid">
        <span>镜号</span><span>镜头设置</span><span>画面与表演</span><span>台词与声音</span><span class="text-right">操作</span>
      </div>
      <article
        v-for="(shot, index) in shots"
        :key="shot.id ?? shot.shotKey ?? index"
        class="grid gap-4 border-b border-border px-1 py-5 xl:grid-cols-[54px_210px_minmax(240px,1.4fr)_minmax(220px,1fr)_96px] xl:gap-3 xl:px-2"
      >
        <div class="flex items-center justify-between xl:block">
          <span class="text-xs text-muted-foreground xl:hidden">镜号</span>
          <strong class="text-sm">#{{ index + 1 }}</strong>
        </div>
        <div class="grid grid-cols-2 gap-2">
          <label class="text-xs text-muted-foreground">景别
            <select v-model="shot.shotScale" class="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm text-foreground" :disabled="storyboardReadOnly" @change="markDirty">
              <option>特写</option><option>近景</option><option>中景</option><option>全景</option><option>远景</option>
            </select>
          </label>
          <label class="text-xs text-muted-foreground">时长（秒）
            <input :value="shot.durationMs / 1000" type="number" min="1" max="15" step="0.5" class="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm text-foreground" :disabled="storyboardReadOnly" @input="shot.durationMs = Number(($event.target as HTMLInputElement).value) * 1000; markDirty()" />
          </label>
          <label class="text-xs text-muted-foreground">机位
            <select v-model="shot.cameraAngle" class="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm text-foreground" :disabled="storyboardReadOnly" @change="markDirty">
              <option>平视</option><option>俯拍</option><option>仰拍</option><option>侧拍</option><option>过肩</option>
            </select>
          </label>
          <label class="text-xs text-muted-foreground">运镜
            <select v-model="shot.cameraMovement" class="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm text-foreground" :disabled="storyboardReadOnly" @change="markDirty">
              <option>固定</option><option>推进</option><option>拉远</option><option>横移</option><option>跟拍</option><option>环绕</option>
            </select>
          </label>
        </div>
        <div class="space-y-2">
          <label class="block text-xs text-muted-foreground">画面描述
            <textarea v-model="shot.visualDescription" rows="3" class="mt-1 w-full resize-y rounded-md border border-input bg-background px-3 py-2 text-sm leading-5 text-foreground" :disabled="storyboardReadOnly" @input="markDirty" />
          </label>
          <label class="block text-xs text-muted-foreground">情绪与表演
            <input v-model="shot.emotion" class="mt-1 h-9 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground" :disabled="storyboardReadOnly" @input="markDirty" />
          </label>
        </div>
        <div class="space-y-2">
          <label class="block text-xs text-muted-foreground">对白 / 旁白
            <textarea v-model="shot.dialogue" rows="2" class="mt-1 w-full resize-y rounded-md border border-input bg-background px-3 py-2 text-sm leading-5 text-foreground" :disabled="storyboardReadOnly" @input="markDirty" />
          </label>
          <div class="grid grid-cols-2 gap-2">
            <label class="text-xs text-muted-foreground">音效<input v-model="shot.soundEffect" class="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm text-foreground" :disabled="storyboardReadOnly" @input="markDirty" /></label>
            <label class="text-xs text-muted-foreground">BGM<input v-model="shot.bgmCue" class="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm text-foreground" :disabled="storyboardReadOnly" @input="markDirty" /></label>
          </div>
        </div>
        <div v-if="!storyboardReadOnly" class="flex items-start justify-end gap-1 xl:flex-wrap">
          <button type="button" class="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-30" :disabled="index === 0" aria-label="上移镜头" title="上移" @click="moveShot(index, -1)"><ArrowUp class="h-4 w-4" /></button>
          <button type="button" class="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-30" :disabled="index === shots.length - 1" aria-label="下移镜头" title="下移" @click="moveShot(index, 1)"><ArrowDown class="h-4 w-4" /></button>
          <button type="button" class="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-destructive/10 hover:text-destructive" aria-label="删除镜头" title="删除" @click="removeShot(index)"><Trash2 class="h-4 w-4" /></button>
        </div>
      </article>
      <button
        v-if="!storyboardReadOnly && shots.length < 18"
        type="button"
        class="mt-4 inline-flex h-9 items-center gap-2 rounded-md border border-dashed border-border px-3 text-sm text-muted-foreground hover:border-primary/40 hover:text-foreground"
        @click="addShot"
      >
        <Plus class="h-4 w-4" aria-hidden="true" /> 添加镜头
      </button>
    </div>

    <div v-if="gateOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-5" @click.self="gateOpen = false">
      <section class="w-full max-w-md rounded-lg border border-border bg-card p-6" role="dialog" aria-modal="true" aria-labelledby="storyboard-gate-title">
        <div class="flex items-start justify-between gap-4">
          <div>
            <h3 id="storyboard-gate-title" class="text-base font-semibold">确认锁定分镜</h3>
            <p class="mt-2 text-sm leading-6 text-muted-foreground">锁定后不能再修改剧本和分镜正文，接下来选择角色与场景版本。</p>
          </div>
          <button type="button" class="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary" aria-label="关闭" @click="gateOpen = false"><X class="h-4 w-4" /></button>
        </div>
        <dl class="mt-5 grid grid-cols-2 gap-3 border-y border-border py-4 text-sm">
          <div><dt class="text-muted-foreground">镜头数</dt><dd class="mt-1 font-semibold">{{ validation.shotCount }}</dd></div>
          <div><dt class="text-muted-foreground">总时长</dt><dd class="mt-1 font-semibold">{{ formatComicDuration(validation.totalDurationMs) }}</dd></div>
        </dl>
        <div class="mt-5 flex justify-end gap-3">
          <button type="button" class="h-9 rounded-md border border-border px-3 text-sm hover:bg-secondary" @click="gateOpen = false">再检查一下</button>
          <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground" :disabled="submitting" @click="lockStoryboard">
            <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
            <LockKeyhole v-else class="h-4 w-4" />
            确认锁定
          </button>
        </div>
      </section>
    </div>
  </section>
</template>
