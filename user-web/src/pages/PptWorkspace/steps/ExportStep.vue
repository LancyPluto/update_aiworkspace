<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import {
  ArrowLeft,
  Download,
  FileArchive,
  FileImage,
  FilePenLine,
  FileText,
  Loader2,
  X,
} from "lucide-vue-next"
import { ApiBusinessError } from "@/api/client"
import {
  exportEditablePptx,
  exportImages,
  exportPdf,
  exportPptx,
  fetchPptProject,
  listPptExports,
  updatePptProject,
  type PptExportFileItem,
  type PptProjectDetail,
  type PptTaskResponse,
  type PptWorkflowStep,
} from "@/api/pptApi"
import PptToast from "@/pages/PptWorkspace/components/PptToast.vue"
import { waitForPptTask } from "@/composables/usePptTaskPoll"
import {
  downloadPptFile,
  normalizeProjectDetail,
  pageHasImage,
} from "@/utils/pptProjectUtils"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  bindingId: string
  workflowSteps?: PptWorkflowStep[]
}>()

const emit = defineEmits<{
  refreshed: [project: PptProjectDetail]
  "go-preview": []
}>()

const auth = useAuthStore()
const loading = ref(true)
const busy = ref(false)
const taskStatus = ref("")
const toast = ref<{ type: "success" | "error"; message: string } | null>(null)
const project = ref<PptProjectDetail | null>(null)
const filename = ref("")
const exportFiles = ref<PptExportFileItem[]>([])
const exportsLoading = ref(false)

const activeTaskId = ref<string | null>(null)
let exportAbort: AbortController | null = null

const pages = computed(() => project.value?.pages ?? [])
const imageReadyCount = computed(() => pages.value.filter((p) => pageHasImage(p)).length)
const exportInProgress = computed(() => Boolean(activeTaskId.value))

const exportStepCredits = computed(() =>
  (props.workflowSteps ?? []).filter((s) =>
    ["EXPORT_PPTX", "EXPORT_PDF", "EXPORT_EDITABLE", "EXPORT"].some(
      (k) => s.code.includes(k) || s.name.includes("导出"),
    ),
  ),
)

function showToast(type: "success" | "error", message: string) {
  toast.value = { type, message }
  window.setTimeout(() => {
    toast.value = null
  }, 5000)
}

function taskDownloadUrl(task: PptTaskResponse): string | undefined {
  const progress = task.progress as Record<string, unknown> | undefined
  const url = progress?.download_url ?? progress?.downloadUrl
  return typeof url === "string" && url.trim() ? url.trim() : undefined
}

function normalizeExportItem(item: PptExportFileItem) {
  const url = item.downloadUrl ?? item.download_url
  const modified = item.modifiedAt ?? item.modified_at
  return { ...item, downloadUrl: url, modifiedAt: modified }
}

async function loadExportHistory() {
  exportsLoading.value = true
  try {
    const res = await listPptExports(props.bindingId, { token: auth.token })
    exportFiles.value = (res.files ?? []).map(normalizeExportItem)
  } catch {
    exportFiles.value = []
  } finally {
    exportsLoading.value = false
  }
}

async function reload() {
  const raw = await fetchPptProject(props.bindingId, { token: auth.token })
  const normalized = normalizeProjectDetail(raw)
  project.value = normalized
  const title = (normalized.title as string) || `ppt_${props.bindingId}`
  if (!filename.value) filename.value = title.replace(/\s+/g, "_")
  emit("refreshed", normalized)
  await loadExportHistory()
}

function cancelActiveExport() {
  exportAbort?.abort()
  exportAbort = null
  activeTaskId.value = null
  busy.value = false
  taskStatus.value = ""
  showToast("success", "已停止等待；引擎侧任务可能仍在后台运行")
}

async function runExport(
  label: string,
  fn: () => Promise<{ downloadUrl?: string }>,
  downloadName?: string,
) {
  busy.value = true
  taskStatus.value = ""
  try {
    const res = await fn()
    if (!res.downloadUrl) {
      showToast("error", "未获取到下载地址")
      return
    }
    await downloadPptFile(res.downloadUrl, props.bindingId, {
      token: auth.token,
      filename: downloadName,
    })
    showToast("success", `${label} 导出成功，已开始下载`)
    await loadExportHistory()
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
    taskStatus.value = ""
  }
}

async function downloadHistoryFile(item: PptExportFileItem) {
  const url = item.downloadUrl ?? item.download_url
  if (!url) return
  busy.value = true
  try {
    await downloadPptFile(url, props.bindingId, {
      token: auth.token,
      filename: item.filename,
    })
    showToast("success", "已开始下载")
  } catch (e) {
    showToast("error", (e as Error).message)
  } finally {
    busy.value = false
  }
}

function exportPptxFile() {
  const name = filename.value ? `${filename.value}.pptx` : undefined
  return runExport("图片幻灯片", () =>
    exportPptx(props.bindingId, { token: auth.token, filename: name }),
    name,
  )
}

function exportPdfFile() {
  const name = filename.value ? `${filename.value}.pdf` : undefined
  return runExport("PDF", () =>
    exportPdf(props.bindingId, { token: auth.token, filename: name }),
    name,
  )
}

function exportImageZip() {
  return runExport("图片包", () => exportImages(props.bindingId, { token: auth.token }), `${filename.value || "images"}.zip`)
}

async function exportEditablePptxFile() {
  if (imageReadyCount.value === 0) {
    showToast("error", "请先在预览步骤生成页面图片")
    return
  }
  exportAbort = new AbortController()
  busy.value = true
  taskStatus.value = "提交可编辑导出任务…"
  try {
    const base = filename.value || `presentation_${props.bindingId}`
    const outName = `${base}_editable.pptx`
    await updatePptProject(
      props.bindingId,
      { export_allow_partial: true },
      { token: auth.token },
    )
    const task = await exportEditablePptx(
      props.bindingId,
      { filename: outName, max_depth: 1, max_workers: 4 },
      { token: auth.token },
    )
    if (!task.taskId) {
      showToast("error", "未获取到导出任务 ID")
      return
    }
    activeTaskId.value = task.taskId
    taskStatus.value = "分析版面并生成可编辑 PPTX（耗时较长）…"
    const done = await waitForPptTask(props.bindingId, task.taskId, {
      token: auth.token,
      signal: exportAbort.signal,
      onProgress: (s) => {
        taskStatus.value = `任务：${s}`
      },
    })
    const url = taskDownloadUrl(done)
    if (!url) {
      showToast("error", "任务已完成但未返回下载地址，请从下方「导出记录」重试下载")
      await loadExportHistory()
      return
    }
    await downloadPptFile(url, props.bindingId, { token: auth.token, filename: outName })
    showToast("success", "可编辑 PPTX 已生成，已开始下载")
    await loadExportHistory()
  } catch (e) {
    if ((e as Error).name === "AbortError") return
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    activeTaskId.value = null
    exportAbort = null
    busy.value = false
    taskStatus.value = ""
  }
}

function formatSize(bytes?: number) {
  if (bytes == null) return ""
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
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

onUnmounted(() => {
  exportAbort?.abort()
})
</script>

<template>
  <div class="space-y-4">
    <PptToast v-if="toast" :type="toast.type" :message="toast.message" />

    <div v-if="loading" class="flex justify-center py-16">
      <Loader2 class="h-8 w-8 animate-spin text-muted-foreground" />
    </div>

    <template v-else>
      <button
        type="button"
        class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary"
        @click="emit('go-preview')"
      >
        <ArrowLeft class="mr-1.5 h-4 w-4" /> 返回预览
      </button>

      <div
        v-if="exportInProgress"
        class="flex flex-wrap items-center gap-2 rounded-lg border border-primary/30 bg-primary/5 px-4 py-3 text-sm"
      >
        <Loader2 class="h-4 w-4 animate-spin text-primary" />
        <span class="text-muted-foreground">{{ taskStatus }}</span>
        <button
          type="button"
          class="ml-auto inline-flex items-center rounded-md border border-border px-2 py-1 text-xs hover:bg-secondary"
          @click="cancelActiveExport"
        >
          <X class="mr-1 h-3.5 w-3.5" /> 取消等待
        </button>
      </div>

      <div class="rounded-2xl border border-border bg-card p-6 space-y-6 max-w-xl">
        <div>
          <h2 class="text-lg font-medium">导出成品</h2>
          <p class="text-sm text-muted-foreground mt-1">
            已出图 {{ imageReadyCount }} / {{ pages.length }} 页。文件保存在引擎项目目录，可在下方「导出记录」重复下载。
          </p>
        </div>

        <div class="rounded-lg border border-border bg-secondary/20 px-3 py-2 text-xs text-muted-foreground space-y-1">
          <p>
            <strong class="text-foreground">可编辑 PPTX</strong>：识别版面后生成可在 PowerPoint 中编辑文字与元素的文件（异步，耗时较长）。
          </p>
          <p>
            <strong class="text-foreground">图片幻灯片 PPTX</strong>：每页一张图，不能直接改字。
          </p>
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium">文件名前缀（可选）</label>
          <input
            v-model="filename"
            class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
            placeholder="presentation"
            :disabled="busy"
          />
        </div>

        <div class="grid gap-3">
          <button
            type="button"
            class="flex items-center gap-3 rounded-xl border border-border px-4 py-4 text-left hover:border-primary/50 hover:bg-secondary/30 disabled:opacity-50"
            :disabled="busy || imageReadyCount === 0"
            @click="exportEditablePptxFile"
          >
            <FilePenLine class="h-8 w-8 text-violet-600 shrink-0" />
            <div>
              <p class="font-medium">导出可编辑 PPTX</p>
              <p class="text-xs text-muted-foreground">推荐 · 可改文字与元素</p>
            </div>
            <Download class="ml-auto h-5 w-5 text-muted-foreground" />
          </button>

          <button
            type="button"
            class="flex items-center gap-3 rounded-xl border border-border px-4 py-4 text-left hover:border-primary/50 hover:bg-secondary/30 disabled:opacity-50"
            :disabled="busy || !pages.length"
            @click="exportPptxFile"
          >
            <FileText class="h-8 w-8 text-primary shrink-0" />
            <div>
              <p class="font-medium">导出图片幻灯片 PPTX</p>
              <p class="text-xs text-muted-foreground">每页整图，适合播放</p>
            </div>
            <Download class="ml-auto h-5 w-5 text-muted-foreground" />
          </button>

          <button
            type="button"
            class="flex items-center gap-3 rounded-xl border border-border px-4 py-4 text-left hover:border-primary/50 hover:bg-secondary/30 disabled:opacity-50"
            :disabled="busy || !pages.length"
            @click="exportPdfFile"
          >
            <FileArchive class="h-8 w-8 text-orange-500 shrink-0" />
            <div>
              <p class="font-medium">导出 PDF</p>
              <p class="text-xs text-muted-foreground">分享与打印</p>
            </div>
            <Download class="ml-auto h-5 w-5 text-muted-foreground" />
          </button>

          <button
            type="button"
            class="flex items-center gap-3 rounded-xl border border-border px-4 py-4 text-left hover:border-primary/50 hover:bg-secondary/30 disabled:opacity-50"
            :disabled="busy || imageReadyCount === 0"
            @click="exportImageZip"
          >
            <FileImage class="h-8 w-8 text-emerald-600 shrink-0" />
            <div>
              <p class="font-medium">导出图片包</p>
              <p class="text-xs text-muted-foreground">ZIP 打包全部页面图</p>
            </div>
            <Download class="ml-auto h-5 w-5 text-muted-foreground" />
          </button>
        </div>

        <section class="border-t border-border pt-4 space-y-2">
          <div class="flex items-center justify-between">
            <h3 class="text-sm font-medium">导出记录</h3>
            <button
              type="button"
              class="text-xs text-primary hover:underline disabled:opacity-50"
              :disabled="exportsLoading"
              @click="loadExportHistory"
            >
              刷新
            </button>
          </div>
          <p v-if="exportsLoading" class="text-xs text-muted-foreground">加载中…</p>
          <p v-else-if="!exportFiles.length" class="text-xs text-muted-foreground">
            暂无导出文件；完成一次导出后会出现在这里，可多次下载。
          </p>
          <ul v-else class="max-h-48 space-y-2 overflow-y-auto">
            <li
              v-for="file in exportFiles"
              :key="file.filename"
              class="flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm"
            >
              <div class="min-w-0 flex-1">
                <p class="truncate font-medium">{{ file.filename }}</p>
                <p class="text-xs text-muted-foreground">
                  {{ file.type || "file" }}
                  <template v-if="file.size"> · {{ formatSize(file.size) }}</template>
                  <template v-if="file.modifiedAt"> · {{ file.modifiedAt }}</template>
                </p>
              </div>
              <button
                type="button"
                class="shrink-0 rounded-md p-2 hover:bg-secondary"
                :disabled="busy"
                title="再次下载"
                @click="downloadHistoryFile(file)"
              >
                <Download class="h-4 w-4" />
              </button>
            </li>
          </ul>
        </section>

        <ul
          v-if="exportStepCredits.length"
          class="text-xs text-muted-foreground space-y-1"
        >
          <li v-for="s in exportStepCredits" :key="s.code">
            {{ s.name }}：约 {{ s.credits }} 算力 / 次
          </li>
        </ul>
      </div>
    </template>
  </div>
</template>
