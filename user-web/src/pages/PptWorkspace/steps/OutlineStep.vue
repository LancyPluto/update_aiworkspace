<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import {
  ArrowRight,
  Loader2,
  Plus,
  Sparkles,
  Wand2,
} from "lucide-vue-next"
import { ApiBusinessError } from "@/api/client"
import {
  addPptPage,
  deletePptPage,
  fetchPptProject,
  generateOutline,
  refineOutline,
  updatePageOutline,
  updatePptProject,
  type PptPageOutline,
  type PptProjectDetail,
} from "@/api/pptApi"
import OutlineCard from "@/pages/PptWorkspace/components/OutlineCard.vue"
import {
  exportOutlineMarkdown,
  normalizeProjectDetail,
  parseMarkdownPages,
} from "@/utils/pptProjectUtils"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  bindingId: string
}>()

const emit = defineEmits<{
  refreshed: [project: PptProjectDetail]
  "go-descriptions": []
}>()

const auth = useAuthStore()

const loading = ref(true)
const busy = ref(false)
const toast = ref<{ type: "success" | "error"; message: string } | null>(null)
const project = ref<PptProjectDetail | null>(null)
const selectedPageId = ref<string | null>(null)
const refineText = ref("")
const outlineRequirements = ref("")
const sourceText = ref("")
const autoStarted = ref(false)

const creationType = computed(
  () => project.value?.creationType ?? project.value?.creation_type ?? "idea",
)

const pages = computed(() => project.value?.pages ?? [])

const generateLabel = computed(() => {
  if (busy.value) return "生成中…"
  if (pages.value.length === 0) {
    return creationType.value === "outline" ? "解析大纲" : "自动生成大纲"
  }
  return creationType.value === "outline" ? "重新解析" : "重新生成"
})

const sourceField = computed(() => {
  if (creationType.value === "outline") return "outline_text"
  if (creationType.value === "description") return "description_text"
  return "idea_prompt"
})

const inputLabel = computed(() => {
  const map: Record<string, string> = {
    idea: "PPT 构想",
    outline: "原始大纲",
    description: "页面描述",
    ppt_renovation: "原始内容",
  }
  return map[creationType.value] ?? "输入内容"
})

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
  sourceText.value =
    normalized.ideaPrompt ??
    normalized.idea_prompt ??
    normalized.outlineText ??
    normalized.outline_text ??
    normalized.descriptionText ??
    normalized.description_text ??
    ""
  outlineRequirements.value =
    normalized.outlineRequirements ?? normalized.outline_requirements ?? ""
  emit("refreshed", normalized)
  return normalized
}

async function saveSourceText() {
  if (!project.value) return
  await updatePptProject(
    props.bindingId,
    { [sourceField.value]: sourceText.value },
    { token: auth.token },
  )
}

let saveTimer: ReturnType<typeof setTimeout> | null = null
watch(sourceText, () => {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    saveSourceText().catch(() => {})
  }, 800)
})

async function runGenerate(regenerate = false) {
  if (busy.value) return
  if (regenerate && pages.value.length > 0) {
    const ok = window.confirm(
      "重新生成会更新所有页面标题；若新大纲页数减少，多出的页面及内容可能被删除。是否继续？",
    )
    if (!ok) return
  }
  busy.value = true
  try {
    await saveSourceText()
    await generateOutline(
      props.bindingId,
      {
        language: "zh",
        outline_requirements: outlineRequirements.value || undefined,
      },
      { token: auth.token },
    )
    await reload()
    showToast("success", "大纲已生成")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
  }
}

async function tryAutoGenerate() {
  if (autoStarted.value || pages.value.length > 0) return
  if (creationType.value === "ppt_renovation") return
  autoStarted.value = true
  await runGenerate(false)
}

async function addPage() {
  busy.value = true
  try {
    const nextIndex = pages.value.reduce((max, p) => Math.max(max, (p.orderIndex ?? 0) + 1), 0)
    await addPptPage(
      props.bindingId,
      {
        order_index: nextIndex,
        outline_content: { title: "新页面", points: [] },
      },
      { token: auth.token },
    )
    await reload()
    showToast("success", "已添加页面")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
  }
}

async function savePageOutline(pageId: string, outline: PptPageOutline) {
  busy.value = true
  try {
    await updatePageOutline(props.bindingId, pageId, outline, { token: auth.token })
    await reload()
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
  }
}

async function removePage(pageId: string) {
  if (!window.confirm("确定删除这一页？")) return
  busy.value = true
  try {
    await deletePptPage(props.bindingId, pageId, { token: auth.token })
    await reload()
    if (selectedPageId.value === pageId) selectedPageId.value = null
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
  }
}

async function reorderPage(pageId: string, direction: "up" | "down") {
  const list = [...pages.value]
  const idx = list.findIndex((p) => p.id === pageId)
  if (idx < 0) return
  const swap = direction === "up" ? idx - 1 : idx + 1
  if (swap < 0 || swap >= list.length) return
  ;[list[idx], list[swap]] = [list[swap], list[idx]]
  busy.value = true
  try {
    await updatePptProject(
      props.bindingId,
      { pages_order: list.map((p) => p.id) },
      { token: auth.token },
    )
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
    const res = await refineOutline(props.bindingId, text, { token: auth.token })
    refineText.value = ""
    await reload()
    showToast("success", res.message || "大纲已按你的要求修改")
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
  }
}

function handleExport() {
  if (!pages.value.length) return
  const title =
    (project.value?.title as string | undefined) || `PPT项目_${props.bindingId}`
  exportOutlineMarkdown(pages.value, title)
  showToast("success", "大纲已导出")
}

async function handleImportMarkdown() {
  const text = window.prompt("粘贴 Markdown 大纲（将追加到项目末尾）")
  if (!text?.trim()) return
  const parsed = parseMarkdownPages(text)
  if (!parsed.length) {
    showToast("error", "未解析到有效页面")
    return
  }
  busy.value = true
  try {
    let start = pages.value.reduce((max, p) => Math.max(max, (p.orderIndex ?? 0) + 1), 0)
    for (const block of parsed) {
      await addPptPage(
        props.bindingId,
        {
          order_index: start++,
          outline_content: { title: block.title, points: block.points },
          ...(block.descriptionText
            ? { description_content: { text: block.descriptionText } }
            : {}),
          ...(block.part ? { part: block.part } : {}),
        },
        { token: auth.token },
      )
    }
    await reload()
    showToast("success", `已导入 ${parsed.length} 页`)
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await reload()
    await tryAutoGenerate()
  } catch (e) {
    showToast("error", e instanceof ApiBusinessError ? e.message : (e as Error).message)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="space-y-4">
    <div
      v-if="toast"
      class="rounded-lg px-4 py-2 text-sm"
      :class="
        toast.type === 'success'
          ? 'border border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'
          : 'border border-destructive/30 bg-destructive/5 text-destructive'
      "
    >
      {{ toast.message }}
    </div>

    <div v-if="loading" class="flex justify-center py-16">
      <Loader2 class="h-8 w-8 animate-spin text-muted-foreground" />
    </div>

    <template v-else>
      <!-- AI 润色条 -->
      <div class="flex flex-col gap-2 rounded-xl border border-border bg-secondary/20 p-4 md:flex-row">
        <input
          v-model="refineText"
          type="text"
          class="min-w-0 flex-1 rounded-md border border-input bg-background px-3 py-2 text-sm"
          placeholder="用自然语言修改大纲，例如：增加一页关于市场分析、删除第 3 页…"
          :disabled="busy"
          @keydown.enter.prevent="submitRefine"
        />
        <button
          type="button"
          class="inline-flex h-10 shrink-0 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:opacity-90 disabled:opacity-50"
          :disabled="busy || !refineText.trim()"
          @click="submitRefine"
        >
          <Wand2 class="mr-2 h-4 w-4" />
          AI 修改大纲
        </button>
        <button
          type="button"
          class="inline-flex h-10 shrink-0 items-center justify-center rounded-md border border-border bg-background px-4 text-sm hover:bg-secondary disabled:opacity-50"
          :disabled="busy || !pages.length"
          @click="emit('go-descriptions')"
        >
          下一步：描述
          <ArrowRight class="ml-2 h-4 w-4" />
        </button>
      </div>

      <!-- 操作栏 -->
      <div class="flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border bg-background px-3 text-sm hover:bg-secondary disabled:opacity-50"
          :disabled="busy"
          @click="addPage"
        >
          <Plus class="mr-1.5 h-4 w-4" />
          添加页面
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md bg-primary/90 px-3 text-sm font-medium text-primary-foreground hover:opacity-90 disabled:opacity-50"
          :disabled="busy"
          @click="runGenerate(pages.length > 0)"
        >
          <Loader2 v-if="busy" class="mr-1.5 h-4 w-4 animate-spin" />
          <Sparkles v-else class="mr-1.5 h-4 w-4" />
          {{ generateLabel }}
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50"
          :disabled="busy"
          @click="handleImportMarkdown"
        >
          导入 Markdown
        </button>
        <button
          type="button"
          class="inline-flex h-9 items-center rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50"
          :disabled="!pages.length"
          @click="handleExport"
        >
          导出大纲
        </button>
        <span class="ml-auto text-xs text-muted-foreground">共 {{ pages.length }} 页</span>
      </div>

      <div class="grid gap-6 lg:grid-cols-[minmax(0,340px)_1fr]">
        <!-- 左侧：原始输入（蕉作左栏） -->
        <aside
          v-if="creationType !== 'ppt_renovation'"
          class="space-y-3 rounded-xl border border-border bg-card p-4"
        >
          <label class="text-sm font-medium">{{ inputLabel }}</label>
          <textarea
            v-model="sourceText"
            class="min-h-[200px] w-full resize-y rounded-md border border-input bg-background px-3 py-2 text-sm leading-relaxed"
            :disabled="busy"
          />
          <div>
            <label class="text-xs font-medium text-muted-foreground">大纲生成要求（可选）</label>
            <textarea
              v-model="outlineRequirements"
              class="mt-1 min-h-[72px] w-full resize-y rounded-md border border-input bg-background px-3 py-2 text-xs"
              placeholder="例如：不超过 10 页、每页不超过 3 个要点…"
              :disabled="busy"
            />
          </div>
        </aside>

        <!-- 右侧：页面卡片列表 -->
        <div class="space-y-3">
          <p v-if="!pages.length && !busy" class="rounded-xl border border-dashed border-border py-12 text-center text-sm text-muted-foreground">
            暂无页面。点击「{{ generateLabel }}」或「添加页面」开始。
          </p>
          <OutlineCard
            v-for="(page, idx) in pages"
            :key="page.id"
            :page="page"
            :index="idx"
            :is-last="idx === pages.length - 1"
            :selected="selectedPageId === page.id"
            :busy="busy"
            @select="selectedPageId = page.id"
            @save="(outline) => savePageOutline(page.id, outline)"
            @delete="removePage(page.id)"
            @move-up="reorderPage(page.id, 'up')"
            @move-down="reorderPage(page.id, 'down')"
          />
        </div>
      </div>
    </template>
  </div>
</template>
