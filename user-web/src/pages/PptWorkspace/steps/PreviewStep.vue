<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import {
  ArrowLeft,
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  ImageIcon,
  Loader2,
  RefreshCw,
  Sparkles,
  Trash2,
  Upload,
} from "lucide-vue-next"
import { ApiBusinessError } from "@/api/client"
import {
  deletePptTemplate,
  fetchPptProject,
  generateImages,
  generatePageImage,
  updatePptProject,
  uploadPptTemplate,
  type PptProjectDetail,
} from "@/api/pptApi"
import PptToast from "@/pages/PptWorkspace/components/PptToast.vue"
import { waitForPptTask } from "@/composables/usePptTaskPoll"
import {
  canGeneratePptImages,
  getPageDescriptionText,
  getTemplateImageUrl,
  getTemplateStyle,
  normalizeProjectDetail,
  pageHasImage,
  PPT_IMAGE_PREREQ_HINT,
} from "@/utils/pptProjectUtils"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{ bindingId: string }>()

const emit = defineEmits<{
  refreshed: [project: PptProjectDetail]
  "go-descriptions": []
  "go-export": []
}>()

const auth = useAuthStore()
const loading = ref(true)
const busy = ref(false)
const taskStatus = ref("")
const toast = ref<{ type: "success" | "error"; message: string } | null>(null)
const project = ref<PptProjectDetail | null>(null)
const activeIndex = ref(0)
const templateInput = ref<HTMLInputElement | null>(null)
const templateStyleDraft = ref("")
const savingStyle = ref(false)

const pages = computed(() => project.value?.pages ?? [])
const activePage = computed(() => pages.value[activeIndex.value] ?? null)
const imageReadyCount = computed(() => pages.value.filter((p) => pageHasImage(p)).length)
const allImagesReady = computed(
  () => pages.value.length > 0 && imageReadyCount.value === pages.value.length,
)
const canGenerateImages = computed(() =>
  canGeneratePptImages(project.value, props.bindingId),
)
const templatePreviewUrl = computed(() =>
  getTemplateImageUrl(project.value, props.bindingId),
)

function showToast(type: "success" | "error", message: string) {
  toast.value = { type, message }
  window.setTimeout(() => {
    toast.value = null
  }, 4000)
}

async function reload() {
  const raw = await fetchPptProject(props.bindingId, { token: auth.token })
  const normalized = normalizeProjectDetail(raw)
  project.value = normalized
  templateStyleDraft.value = getTemplateStyle(normalized)
  if (activeIndex.value >= normalized.pages!.length) {
    activeIndex.value = Math.max(0, normalized.pages!.length - 1)
  }
  emit("refreshed", normalized)
}

function ensureImagePrerequisites(): boolean {
  if (canGenerateImages.value) return true
  showToast("error", PPT_IMAGE_PREREQ_HINT)
  return false
}

async function saveTemplateStyle() {
  const text = templateStyleDraft.value.trim()
  if (!text) {
    showToast("error", "请填写风格描述，或改为上传模板图")
    return
  }
  savingStyle.value = true
  try {
    await updatePptProject(props.bindingId, { template_style: text }, { token: auth.token })
    await reload()
    showToast("success", "风格描述已保存")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    savingStyle.value = false
  }
}

async function batchGenerateImages() {
  if (!pages.value.length) return
  if (!ensureImagePrerequisites()) return
  if (imageReadyCount.value > 0) {
    const ok = window.confirm("将重新生成全部页面图片（历史版本会保留），是否继续？")
    if (!ok) return
  }
  busy.value = true
  taskStatus.value = "提交出图任务…"
  try {
    const task = await generateImages(
      props.bindingId,
      { language: "zh", use_template: true },
      { token: auth.token },
    )
    if (!task.taskId) {
      await reload()
      showToast("success", "图片生成完成")
      return
    }
    taskStatus.value = "出图中…"
    await waitForPptTask(props.bindingId, task.taskId, {
      token: auth.token,
      onProgress: (s) => {
        taskStatus.value = `任务：${s}`
      },
    })
    await reload()
    showToast("success", "批量出图完成")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
    taskStatus.value = ""
  }
}

async function generateCurrentPage() {
  const page = activePage.value
  if (!page) return
  if (!ensureImagePrerequisites()) return
  busy.value = true
  taskStatus.value = `生成第 ${activeIndex.value + 1} 页…`
  try {
    const task = await generatePageImage(props.bindingId, page.id, {
      token: auth.token,
      forceRegenerate: true,
      useTemplate: true,
    })
    if (task.taskId) {
      await waitForPptTask(props.bindingId, task.taskId, {
        token: auth.token,
        onProgress: (s) => {
          taskStatus.value = `任务：${s}`
        },
      })
    }
    await reload()
    showToast("success", "本页图片已生成")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
    taskStatus.value = ""
  }
}

const hasTemplateImage = computed(() => Boolean(templatePreviewUrl.value))

function pickTemplate(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  busy.value = true
  uploadPptTemplate(props.bindingId, file, { token: auth.token })
    .then(() => reload())
    .then(() => showToast("success", "模板图已保存"))
    .catch((e) =>
      showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message),
    )
    .finally(() => {
      busy.value = false
      if (templateInput.value) templateInput.value.value = ""
    })
}

async function removeTemplate() {
  if (!hasTemplateImage.value) return
  busy.value = true
  try {
    await deletePptTemplate(props.bindingId, { token: auth.token })
    await reload()
    showToast("success", "模板图已删除，可重新上传")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
    if (templateInput.value) templateInput.value.value = ""
  }
}

function prevPage() {
  if (activeIndex.value > 0) activeIndex.value--
}

function nextPage() {
  if (activeIndex.value < pages.value.length - 1) activeIndex.value++
}

onMounted(async () => {
  loading.value = true
  try {
    await reload()
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="space-y-4">
    <PptToast v-if="toast" :type="toast.type" :message="toast.message" />

    <div v-if="loading" class="flex justify-center py-16">
      <Loader2 class="h-8 w-8 animate-spin text-muted-foreground" />
    </div>

    <template v-else>
      <section
        class="rounded-xl border p-4 space-y-3"
        :class="
          canGenerateImages
            ? 'border-border bg-secondary/15'
            : 'border-amber-500/40 bg-amber-500/5'
        "
      >
        <p class="text-sm font-medium">
          {{ canGenerateImages ? "出图素材已就绪" : "出图前请先配置风格（必填其一）" }}
        </p>
        <p v-if="!canGenerateImages" class="text-xs text-muted-foreground">
          {{ PPT_IMAGE_PREREQ_HINT }}
        </p>
        <div class="flex flex-wrap gap-4 items-start">
          <div class="flex-1 min-w-[200px] space-y-2">
            <label class="text-xs text-muted-foreground">页面风格描述（无模板图时使用）</label>
            <textarea
              v-model="templateStyleDraft"
              class="min-h-[72px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              placeholder="例如：现代简洁商务风，蓝白主色，适合高校介绍 PPT"
              :disabled="busy || savingStyle"
            />
            <button
              type="button"
              class="inline-flex h-8 items-center rounded-md border border-border px-3 text-xs hover:bg-secondary disabled:opacity-50"
              :disabled="busy || savingStyle || !templateStyleDraft.trim()"
              @click="saveTemplateStyle"
            >
              <Loader2 v-if="savingStyle" class="mr-1 h-3.5 w-3.5 animate-spin" />
              保存风格描述
            </button>
          </div>
          <div v-if="templatePreviewUrl" class="shrink-0 space-y-1">
            <p class="text-xs text-muted-foreground">已上传模板图</p>
            <div class="relative inline-block">
              <img
                :src="templatePreviewUrl"
                alt="模板图"
                class="h-20 w-32 rounded-md border border-border object-cover"
              />
              <button
                type="button"
                class="absolute -right-2 -top-2 inline-flex h-7 w-7 items-center justify-center rounded-full border border-border bg-background text-muted-foreground shadow-sm hover:bg-destructive/10 hover:text-destructive disabled:opacity-50"
                :disabled="busy"
                title="删除模板图"
                @click="removeTemplate"
              >
                <Trash2 class="h-3.5 w-3.5" />
              </button>
            </div>
            <button
              type="button"
              class="block text-xs text-muted-foreground underline-offset-2 hover:underline disabled:opacity-50"
              :disabled="busy"
              @click="removeTemplate"
            >
              删除后重新上传
            </button>
          </div>
        </div>
      </section>

      <div class="flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary"
          @click="emit('go-descriptions')"
        >
          <ArrowLeft class="mr-1.5 h-4 w-4" /> 返回描述
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md bg-primary/90 px-3 text-sm text-primary-foreground disabled:opacity-50"
          :disabled="busy || !pages.length || !canGenerateImages"
          :title="canGenerateImages ? '' : PPT_IMAGE_PREREQ_HINT"
          @click="batchGenerateImages"
        >
          <Loader2 v-if="busy" class="mr-1.5 h-4 w-4 animate-spin" />
          <Sparkles v-else class="mr-1.5 h-4 w-4" />
          批量生成图片
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50"
          :disabled="busy || !activePage || !canGenerateImages"
          :title="canGenerateImages ? '' : PPT_IMAGE_PREREQ_HINT"
          @click="generateCurrentPage"
        >
          <ImageIcon class="mr-1.5 h-4 w-4" /> 生成当前页
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary"
          :disabled="busy"
          @click="templateInput?.click()"
        >
          <Upload class="mr-1.5 h-4 w-4" />
          {{ hasTemplateImage ? "更换模板图" : "上传模板图" }}
        </button>
        <input
          ref="templateInput"
          type="file"
          accept="image/*"
          class="hidden"
          @change="pickTemplate"
        />
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary"
          :disabled="busy"
          @click="reload"
        >
          <RefreshCw class="mr-1.5 h-4 w-4" /> 刷新
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm disabled:opacity-50"
          :disabled="!allImagesReady"
          @click="emit('go-export')"
        >
          下一步：导出 <ArrowRight class="ml-1.5 h-4 w-4" />
        </button>
        <span v-if="taskStatus" class="text-xs text-muted-foreground">{{ taskStatus }}</span>
        <span class="ml-auto text-xs text-muted-foreground">
          {{ imageReadyCount }} / {{ pages.length }} 页已出图
        </span>
      </div>

      <p v-if="!pages.length" class="rounded-xl border border-dashed py-12 text-center text-sm text-muted-foreground">
        暂无页面可预览
      </p>

      <template v-else-if="activePage">
        <div class="grid gap-6 lg:grid-cols-[1fr_280px]">
          <div class="space-y-4">
            <div class="flex items-center justify-between">
              <h2 class="text-lg font-medium">
                第 {{ activeIndex + 1 }} 页 / {{ pages.length }}
                <span class="ml-2 text-sm font-normal text-muted-foreground">
                  {{ activePage.outlineContent?.title }}
                </span>
              </h2>
              <div class="flex items-center gap-1">
                <button
                  type="button"
                  class="rounded-md border border-border p-2 hover:bg-secondary disabled:opacity-40"
                  :disabled="activeIndex === 0"
                  @click="prevPage"
                >
                  <ChevronLeft class="h-5 w-5" />
                </button>
                <button
                  type="button"
                  class="rounded-md border border-border p-2 hover:bg-secondary disabled:opacity-40"
                  :disabled="activeIndex >= pages.length - 1"
                  @click="nextPage"
                >
                  <ChevronRight class="h-5 w-5" />
                </button>
              </div>
            </div>

            <div
              class="relative overflow-hidden rounded-2xl border border-border bg-secondary/30 aspect-video flex items-center justify-center"
            >
              <img
                v-if="activePage.generatedImageUrl"
                :src="activePage.generatedImageUrl"
                :alt="activePage.outlineContent?.title"
                class="h-full w-full object-contain"
              />
              <div v-else class="text-center text-sm text-muted-foreground p-8">
                <ImageIcon class="mx-auto h-12 w-12 opacity-30 mb-2" />
                尚未生成图片
              </div>
              <div
                v-if="busy"
                class="absolute inset-0 flex items-center justify-center bg-background/70"
              >
                <Loader2 class="h-10 w-10 animate-spin text-primary" />
              </div>
            </div>

            <p class="text-sm text-muted-foreground whitespace-pre-wrap line-clamp-4">
              {{ getPageDescriptionText(activePage) }}
            </p>
          </div>

          <aside class="space-y-2">
            <p class="text-xs font-medium text-muted-foreground mb-2">全部页面</p>
            <button
              v-for="(page, idx) in pages"
              :key="page.id"
              type="button"
              class="w-full rounded-lg border overflow-hidden text-left transition-colors"
              :class="
                idx === activeIndex
                  ? 'border-primary ring-2 ring-primary/30'
                  : 'border-border hover:border-primary/40'
              "
              @click="activeIndex = idx"
            >
              <div class="aspect-video bg-secondary/40 flex items-center justify-center text-[10px] text-muted-foreground">
                <img
                  v-if="page.generatedImageUrl"
                  :src="page.generatedImageUrl"
                  class="h-full w-full object-cover"
                  :alt="`第 ${idx + 1} 页`"
                />
                <span v-else>待生成</span>
              </div>
              <p class="truncate px-2 py-1 text-xs">{{ page.outlineContent?.title || idx + 1 }}</p>
            </button>
          </aside>
        </div>
      </template>
    </template>
  </div>
</template>
