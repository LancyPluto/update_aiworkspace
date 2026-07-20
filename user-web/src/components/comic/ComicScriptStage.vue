<script setup lang="ts">
import { computed, ref, watch } from "vue"
import {
  FileText,
  Loader2,
  Save,
  Sparkles,
  Upload,
} from "lucide-vue-next"
import {
  comicProjectApi,
  type ComicEpisodeDetail,
  type ComicEntityId,
} from "@/api/comicProjectApi"
import { useAuthStore } from "@/store/authStore"
import { comicEpisodeGenerationStatus } from "@/utils/comicProject"

type CreateMode = "ai" | "paste" | "file"

const props = defineProps<{
  projectId: ComicEntityId
  episode?: ComicEpisodeDetail | null
  defaultEpisodeNo?: number
}>()

const emit = defineEmits<{
  updated: [episode: ComicEpisodeDetail]
  "open-storyboard": []
}>()

const auth = useAuthStore()
const mode = ref<CreateMode>("paste")
const title = ref("")
const scriptText = ref("")
const aiPrompt = ref("")
const selectedFile = ref<File | null>(null)
const submitting = ref(false)
const error = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const isExisting = computed(() => Boolean(props.episode?.id))
const canSave = computed(() => title.value.trim().length > 0 && scriptText.value.trim().length >= 20)
const generationStatus = computed(() => comicEpisodeGenerationStatus(props.episode?.status))
const scriptGenerating = computed(() => generationStatus.value === "SCRIPT_GENERATING")
const storyboardGenerating = computed(() => generationStatus.value === "STORYBOARD_GENERATING")
const workflowGenerating = computed(() => generationStatus.value != null)
const editorDisabled = computed(() => Boolean(props.episode?.storyboardLocked) || workflowGenerating.value)

watch(
  () => props.episode,
  (episode) => {
    title.value = episode?.title ?? `第 ${props.defaultEpisodeNo ?? 1} 集`
    scriptText.value = episode?.scriptText ?? ""
    selectedFile.value = null
    error.value = null
  },
  { immediate: true },
)

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  selectedFile.value = file
  if (file && !title.value.trim()) title.value = file.name.replace(/\.(txt|md|markdown|docx)$/i, "")
}

async function submitScript() {
  if (submitting.value || workflowGenerating.value) return
  error.value = null
  submitting.value = true
  try {
    let result: ComicEpisodeDetail
    if (isExisting.value && props.episode) {
      if (!canSave.value) throw new Error("剧本标题和正文尚未填写完整")
      result = await comicProjectApi.updateEpisode(props.projectId, props.episode.id, {
        title: title.value.trim(),
        scriptText: scriptText.value.trim(),
        expectedRevision: props.episode.revision ?? 0,
      }, { token: auth.token })
    } else if (mode.value === "file") {
      if (!selectedFile.value) throw new Error("请选择 TXT、MD 或 DOCX 剧本文件")
      result = await comicProjectApi.importEpisode(
        props.projectId,
        selectedFile.value,
        title.value.trim() || undefined,
        props.defaultEpisodeNo,
        { token: auth.token },
      )
    } else if (mode.value === "ai") {
      if (!title.value.trim() || aiPrompt.value.trim().length < 10) throw new Error("请填写标题和完整的创作要求")
      result = await comicProjectApi.generateEpisode(props.projectId, {
        title: title.value.trim(),
        prompt: aiPrompt.value.trim(),
        episodeNo: props.defaultEpisodeNo,
      }, { token: auth.token })
    } else {
      if (!canSave.value) throw new Error("剧本正文至少需要 20 个字")
      result = await comicProjectApi.createEpisode(props.projectId, {
        title: title.value.trim(),
        scriptText: scriptText.value.trim(),
        sourceType: "PASTE",
        episodeNo: props.defaultEpisodeNo,
      }, { token: auth.token })
    }
    emit("updated", result)
  } catch (submitError) {
    error.value = submitError instanceof Error ? submitError.message : "剧本保存失败"
  } finally {
    submitting.value = false
  }
}

async function generateStoryboard() {
  if (!props.episode || submitting.value || workflowGenerating.value) return
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
    emit("open-storyboard")
  } catch (generateError) {
    error.value = generateError instanceof Error ? generateError.message : "AI 拆分分镜失败"
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section>
    <header class="flex flex-col gap-4 border-b border-border pb-5 sm:flex-row sm:items-start sm:justify-between">
      <div>
        <p class="text-xs font-medium text-primary">阶段 1</p>
        <h2 class="mt-1 text-xl font-semibold">剧本</h2>
        <p class="mt-2 text-sm leading-6 text-muted-foreground">先完成一集的完整剧本，再进入分镜拆解。</p>
      </div>
      <button
        v-if="episode?.scriptText"
        type="button"
        class="inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-50"
        :disabled="submitting || workflowGenerating"
        @click="generateStoryboard"
      >
        <Loader2 v-if="submitting || storyboardGenerating" class="h-4 w-4 animate-spin" aria-hidden="true" />
        <Sparkles v-else class="h-4 w-4" aria-hidden="true" />
        {{ storyboardGenerating ? "AI 正在拆分" : "AI 拆分分镜" }}
      </button>
    </header>

    <div v-if="workflowGenerating" class="mt-5 flex items-center gap-3 rounded-md border border-primary/25 bg-primary/8 px-4 py-3 text-sm text-primary" role="status" aria-live="polite">
      <Loader2 class="h-4 w-4 shrink-0 animate-spin" aria-hidden="true" />
      <span>{{ scriptGenerating ? "AI 正在创作完整剧本，完成后会自动刷新。" : "AI 正在拆分分镜，完成后会自动刷新。" }}</span>
    </div>

    <div v-if="!isExisting" class="mt-6 inline-flex w-full rounded-md border border-border bg-secondary/30 p-1 sm:w-auto" role="tablist" aria-label="剧本来源">
      <button
        v-for="item in [
          { id: 'ai', label: 'AI 创作', icon: Sparkles },
          { id: 'paste', label: '粘贴剧本', icon: FileText },
          { id: 'file', label: '导入文件', icon: Upload },
        ]"
        :key="item.id"
        type="button"
        role="tab"
        class="inline-flex h-9 flex-1 items-center justify-center gap-2 rounded px-3 text-sm transition sm:flex-none"
        :class="mode === item.id ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'"
        :aria-selected="mode === item.id"
        @click="mode = item.id as CreateMode"
      >
        <component :is="item.icon" class="h-4 w-4" aria-hidden="true" />
        {{ item.label }}
      </button>
    </div>

    <form class="mt-6 space-y-5" @submit.prevent="submitScript">
      <label class="block text-sm">
        <span class="mb-2 block font-medium">本集标题</span>
        <input v-model="title" maxlength="120" class="h-10 w-full rounded-md border border-input bg-background px-3 outline-none focus:border-primary focus:ring-2 focus:ring-primary/20" placeholder="例如：雨夜来客" :disabled="editorDisabled" />
      </label>

      <label v-if="!isExisting && mode === 'ai'" class="block text-sm">
        <span class="mb-2 block font-medium">创作要求</span>
        <textarea v-model="aiPrompt" rows="12" class="min-h-64 w-full resize-y rounded-md border border-input bg-background px-4 py-3 leading-7 outline-none focus:border-primary focus:ring-2 focus:ring-primary/20" placeholder="说明故事主题、人物关系、核心冲突、目标时长和希望保留的情节。" />
      </label>

      <div v-else-if="!isExisting && mode === 'file'" class="border-y border-border py-8 text-center">
        <input ref="fileInput" type="file" accept=".txt,.md,.markdown,.docx,text/plain,text/markdown,application/vnd.openxmlformats-officedocument.wordprocessingml.document" class="sr-only" @change="selectFile" />
        <Upload class="mx-auto h-8 w-8 text-muted-foreground" aria-hidden="true" />
        <p class="mt-3 text-sm font-medium">{{ selectedFile?.name || "选择一个剧本文件" }}</p>
        <p class="mt-1 text-xs text-muted-foreground">支持 TXT、MD、Markdown 和 DOCX</p>
        <button type="button" class="mt-4 h-9 rounded-md border border-border px-3 text-sm hover:bg-secondary" @click="fileInput?.click()">选择文件</button>
      </div>

      <label v-else class="block text-sm">
        <span class="mb-2 flex items-center justify-between gap-3 font-medium">
          <span>完整剧本</span>
          <span class="text-xs font-normal text-muted-foreground">{{ scriptText.length.toLocaleString() }} 字</span>
        </span>
        <textarea
          v-model="scriptText"
          rows="18"
          class="min-h-80 w-full resize-y rounded-md border border-input bg-background px-4 py-3 font-mono text-sm leading-7 outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 disabled:opacity-70"
          placeholder="粘贴完整剧本，包含场景、动作、对白和旁白。"
          :disabled="editorDisabled"
        />
      </label>

      <p v-if="episode?.storyboardLocked" class="rounded-md border border-warning/25 bg-warning/8 px-3 py-2 text-sm text-warning">分镜已锁定，剧本不能继续修改。</p>
      <p v-if="error" class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">{{ error }}</p>

      <div v-if="!episode?.storyboardLocked" class="flex justify-end border-t border-border pt-5">
        <button
          type="submit"
          class="inline-flex h-10 items-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-50"
          :disabled="submitting || workflowGenerating"
        >
          <Loader2 v-if="submitting || workflowGenerating" class="h-4 w-4 animate-spin" aria-hidden="true" />
          <Save v-else class="h-4 w-4" aria-hidden="true" />
          {{ scriptGenerating ? "AI 正在创作" : storyboardGenerating ? "AI 正在拆分" : submitting ? "正在处理" : isExisting ? "保存剧本" : mode === "file" ? "导入剧本" : mode === "ai" ? "开始创作" : "保存剧本" }}
        </button>
      </div>
    </form>
  </section>
</template>
