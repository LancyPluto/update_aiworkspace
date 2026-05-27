<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { ArrowLeft, ArrowRight, Loader2, Sparkles, Wand2 } from "lucide-vue-next"
import { ApiBusinessError } from "@/api/client"
import {
  fetchPptProject,
  generateDescriptions,
  generatePageDescription,
  refineDescriptions,
  updatePageDescription,
  type PptProjectDetail,
} from "@/api/pptApi"
import PptToast from "@/pages/PptWorkspace/components/PptToast.vue"
import DescriptionCard from "@/pages/PptWorkspace/components/DescriptionCard.vue"
import { waitForPptTask } from "@/composables/usePptTaskPoll"
import {
  exportDescriptionsMarkdown,
  getPageDescriptionText,
  normalizeProjectDetail,
  pageHasDescription,
} from "@/utils/pptProjectUtils"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{ bindingId: string }>()

const emit = defineEmits<{
  refreshed: [project: PptProjectDetail]
  "go-outline": []
  "go-preview": []
}>()

const auth = useAuthStore()
const loading = ref(true)
const busy = ref(false)
const taskStatus = ref("")
const toast = ref<{ type: "success" | "error"; message: string } | null>(null)
const project = ref<PptProjectDetail | null>(null)
const selectedPageId = ref<string | null>(null)
const refineText = ref("")
const generatingPageId = ref<string | null>(null)

const pages = computed(() => project.value?.pages ?? [])
const completedCount = computed(() => pages.value.filter((p) => pageHasDescription(p)).length)
const allDone = computed(
  () => pages.value.length > 0 && completedCount.value === pages.value.length,
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
  emit("refreshed", normalized)
  return normalized
}

async function batchGenerate() {
  if (!pages.value.length) {
    showToast("error", "请先有页面大纲")
    return
  }
  if (completedCount.value > 0) {
    const ok = window.confirm("部分页面已有描述，重新生成将覆盖，是否继续？")
    if (!ok) return
  }
  busy.value = true
  taskStatus.value = "提交任务…"
  try {
    const task = await generateDescriptions(props.bindingId, { token: auth.token })
    if (!task.taskId) {
      await reload()
      showToast("success", "描述已生成")
      return
    }
    taskStatus.value = "生成中…"
    await waitForPptTask(props.bindingId, task.taskId, {
      token: auth.token,
      onProgress: (s) => {
        taskStatus.value = `任务状态：${s}`
      },
    })
    await reload()
    showToast("success", "批量描述生成完成")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
    taskStatus.value = ""
  }
}

async function generateOne(pageId: string) {
  generatingPageId.value = pageId
  try {
    await generatePageDescription(props.bindingId, pageId, {
      token: auth.token,
      forceRegenerate: true,
    })
    await reload()
    showToast("success", "页面描述已更新")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    generatingPageId.value = null
  }
}

async function saveDescription(pageId: string, text: string) {
  busy.value = true
  try {
    await updatePageDescription(props.bindingId, pageId, text, { token: auth.token })
    await reload()
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
  }
}

async function submitRefine() {
  const text = refineText.value.trim()
  if (!text) return
  busy.value = true
  try {
    const res = await refineDescriptions(props.bindingId, text, { token: auth.token })
    refineText.value = ""
    await reload()
    showToast("success", res.message || "描述已按你的要求修改")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
  }
}

const selectedPage = computed(() =>
  pages.value.find((p) => p.id === selectedPageId.value) ?? null,
)

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
      <div class="flex flex-col gap-2 rounded-xl border border-border bg-secondary/20 p-4 md:flex-row md:items-center">
        <input
          v-model="refineText"
          type="text"
          class="min-w-0 flex-1 rounded-md border border-input bg-background px-3 py-2 text-sm"
          placeholder="AI 修改全部描述，例如：每页控制在 100 字、增加数据案例…"
          :disabled="busy"
          @keydown.enter.prevent="submitRefine"
        />
        <button
          type="button"
          class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm text-primary-foreground disabled:opacity-50"
          :disabled="busy || !refineText.trim()"
          @click="submitRefine"
        >
          <Wand2 class="mr-2 h-4 w-4" /> AI 修改描述
        </button>
        <button
          type="button"
          class="inline-flex h-10 items-center justify-center rounded-md border border-border bg-background px-4 text-sm disabled:opacity-50"
          :disabled="busy || !allDone"
          :title="allDone ? '' : '请先完成所有页面描述'"
          @click="emit('go-preview')"
        >
          下一步：预览 <ArrowRight class="ml-2 h-4 w-4" />
        </button>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary"
          @click="emit('go-outline')"
        >
          <ArrowLeft class="mr-1.5 h-4 w-4" /> 返回大纲
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md bg-primary/90 px-3 text-sm text-primary-foreground disabled:opacity-50"
          :disabled="busy || !pages.length"
          @click="batchGenerate"
        >
          <Loader2 v-if="busy" class="mr-1.5 h-4 w-4 animate-spin" />
          <Sparkles v-else class="mr-1.5 h-4 w-4" />
          批量生成描述
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50"
          :disabled="!pages.length"
          @click="
            exportDescriptionsMarkdown(
              pages,
              (project?.title as string) || `项目_${bindingId}`,
            )
          "
        >
          导出描述
        </button>
        <span v-if="taskStatus" class="text-xs text-muted-foreground">{{ taskStatus }}</span>
        <span class="ml-auto text-xs text-muted-foreground">
          {{ completedCount }} / {{ pages.length }} 页已完成描述
        </span>
      </div>

      <p v-if="!pages.length" class="rounded-xl border border-dashed py-12 text-center text-sm text-muted-foreground">
        还没有页面，请先在大纲步骤添加或生成大纲。
      </p>

      <div v-else class="grid gap-6 lg:grid-cols-2">
        <div class="space-y-3">
          <DescriptionCard
            v-for="(page, idx) in pages"
            :key="page.id"
            :page="page"
            :index="idx"
            :selected="selectedPageId === page.id"
            :busy="busy"
            :generating="generatingPageId === page.id"
            @select="selectedPageId = page.id"
            @save="(text) => saveDescription(page.id, text)"
            @generate="generateOne(page.id)"
          />
        </div>
        <aside
          v-if="selectedPage"
          class="rounded-xl border border-border bg-card p-4 lg:sticky lg:top-4 lg:self-start"
        >
          <h3 class="text-sm font-medium mb-2">预览 · 第 {{ pages.indexOf(selectedPage) + 1 }} 页</h3>
          <p class="text-xs text-muted-foreground mb-3">{{ selectedPage.outlineContent?.title }}</p>
          <div class="max-h-[480px] overflow-y-auto text-sm whitespace-pre-wrap text-muted-foreground">
            {{ getPageDescriptionText(selectedPage) || "（空）" }}
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>
