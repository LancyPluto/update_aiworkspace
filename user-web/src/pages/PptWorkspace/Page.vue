<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink, useRouter } from "vue-router"
import {
  ArrowLeft,
  FileText,
  ImagePlus,
  Lightbulb,
  ListTree,
  Loader2,
  Sparkles,
  Upload,
  Zap,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { ApiBusinessError } from "@/api/client"
import { fetchToolByCode } from "@/api/toolApi"
import {
  PPT_TOOL_CODE,
  createPptProject,
  createPptRenovation,
  listPptProjects,
  type PptCreationType,
  type PptProjectSummary,
  type PptWorkflow,
} from "@/api/pptApi"
import type { ToolDetail } from "@/api/types"
import ProjectCard from "@/pages/PptWorkspace/components/ProjectCard.vue"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { cleanToolDisplayText } from "@/utils/toolDisplayText"

const auth = useAuthStore()
const router = useRouter()

const ASPECT_RATIOS = ["16:9", "9:16", "1:1", "4:3", "3:4"] as const

const CREATION_META: Record<
  PptCreationType,
  { label: string; desc: string; placeholder: string; icon: typeof Lightbulb }
> = {
  idea: {
    label: "一句话生成",
    desc: "输入想法，AI 生成完整 PPT 流程（大纲 → 描述 → 配图）",
    placeholder: "例如：生成一份关于 AI 发展史的演讲 PPT",
    icon: Lightbulb,
  },
  outline: {
    label: "从大纲生成",
    desc: "粘贴已有大纲，AI 切分为结构化页面",
    placeholder: "粘贴你的 PPT 大纲…\n\n第一页：标题\n- 要点一\n- 要点二",
    icon: ListTree,
  },
  description: {
    label: "从描述生成",
    desc: "粘贴每页完整描述，跳过大纲步骤直接出图",
    placeholder: "粘贴每页描述，用空行分隔各页…",
    icon: FileText,
  },
  ppt_renovation: {
    label: "PPT 翻新",
    desc: "上传 PDF / PPTX，AI 解析后重新生成",
    placeholder: "",
    icon: Upload,
  },
}

const loading = ref(true)
const submitting = ref(false)
const error = ref<string | null>(null)
const tool = ref<ToolDetail | null>(null)
const projects = ref<PptProjectSummary[]>([])
const activeTab = ref<PptCreationType>("idea")
const content = ref("")
const aspectRatio = ref<(typeof ASPECT_RATIOS)[number]>("16:9")
const templateStyle = ref("")
const renovationFile = ref<File | null>(null)
const renovationInput = ref<HTMLInputElement | null>(null)

const workflow = computed<PptWorkflow | null>(() => {
  const ext = tool.value?.integration?.extension
  if (ext && typeof ext === "object") return ext as PptWorkflow
  return tool.value?.workflow ?? null
})

const enabledCreationTypes = computed<PptCreationType[]>(() => {
  const fromWf = workflow.value?.creationTypes
  if (fromWf?.length) {
    return fromWf.filter((t): t is PptCreationType => t in CREATION_META)
  }
  return ["idea", "outline", "description", "ppt_renovation"]
})

const stepCredits = computed(() => workflow.value?.steps?.filter((s) => s.enabled) ?? [])

const activeMeta = computed(() => CREATION_META[activeTab.value])
const displayToolName = computed(() => cleanToolDisplayText(tool.value?.toolName) || "AI PPT 工作台")

async function load() {
  loading.value = true
  error.value = null
  try {
    const [toolRes, listRes] = await Promise.all([
      fetchToolByCode(PPT_TOOL_CODE, { token: auth.token }),
      listPptProjects({ token: auth.token }),
    ])
    tool.value = toolRes
    projects.value = listRes.projects ?? []
    if (!enabledCreationTypes.value.includes(activeTab.value)) {
      activeTab.value = enabledCreationTypes.value[0] ?? "idea"
    }
  } catch (e) {
    error.value = e instanceof ApiBusinessError ? e.message : (e as Error).message
  } finally {
    loading.value = false
  }
}

function pickRenovationFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) renovationFile.value = file
}

async function submitCreate() {
  if (submitting.value) return
  error.value = null

  if (activeTab.value === "ppt_renovation") {
    if (!renovationFile.value) {
      error.value = "请先上传 PDF 或 PPTX 文件"
      return
    }
    submitting.value = true
    try {
      const created = await createPptRenovation(renovationFile.value, { token: auth.token })
      await router.push(userRoutes.pptProjectEditor(created.bindingId))
    } catch (e) {
      error.value = e instanceof ApiBusinessError ? e.message : (e as Error).message
    } finally {
      submitting.value = false
    }
    return
  }

  const text = content.value.trim()
  if (!text) {
    error.value = "请输入内容"
    return
  }

  const body: Record<string, unknown> = {
    creationType: activeTab.value,
    imageAspectRatio: aspectRatio.value,
  }
  if (activeTab.value === "idea") body.ideaPrompt = text
  if (activeTab.value === "outline") body.outlineText = text
  if (activeTab.value === "description") body.descriptionText = text
  const style = templateStyle.value.trim()
  if (style) body.templateStyle = style

  submitting.value = true
  try {
    const created = await createPptProject(body, { token: auth.token })
    await router.push(userRoutes.pptProjectEditor(created.bindingId))
  } catch (e) {
    error.value = e instanceof ApiBusinessError ? e.message : (e as Error).message
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <AppShell
    :title="displayToolName"
    description="多步工作台 · 大纲 · 描述 · 预览 · 导出"
  >
    <div class="mx-auto max-w-4xl space-y-8 px-6 py-6">
      <nav class="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <RouterLink :to="userRoutes.toolList" class="inline-flex items-center gap-1 hover:text-foreground">
          <ArrowLeft class="h-3 w-3" /> 工具超市
        </RouterLink>
        <span>/</span>
        <RouterLink
          :to="userRoutes.toolDetail(PPT_TOOL_CODE)"
          class="hover:text-foreground"
        >
          {{ displayToolName }}
        </RouterLink>
        <span>/</span>
        <span class="text-foreground">工作台</span>
      </nav>

      <header class="space-y-3 text-center">
        <div
          class="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-amber-400/20 to-primary/20"
        >
          <Sparkles class="h-7 w-7 text-amber-500" />
        </div>
        <h1 class="text-2xl font-semibold tracking-tight">
          {{ displayToolName }}
        </h1>
        <div v-if="stepCredits.length" class="flex flex-wrap justify-center gap-2">
          <span
            v-for="step in stepCredits"
            :key="step.code"
            class="inline-flex items-center gap-1 rounded-full border border-border bg-secondary/50 px-2.5 py-0.5 text-xs text-muted-foreground"
          >
            <Zap class="h-3 w-3 text-warning" />
            {{ step.name }} {{ step.credits }} 算力
          </span>
        </div>
      </header>

      <div v-if="loading" class="flex justify-center py-16">
        <Loader2 class="h-8 w-8 animate-spin text-muted-foreground" />
      </div>

      <template v-else>
        <div
          v-if="error"
          class="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive"
        >
          {{ error }}
        </div>

        <section class="rounded-2xl border border-border bg-card shadow-sm overflow-hidden">
          <div class="flex flex-wrap border-b border-border bg-secondary/20 p-1 gap-1">
            <button
              v-for="type in enabledCreationTypes"
              :key="type"
              type="button"
              class="rounded-lg px-4 py-2 text-sm font-medium transition-colors"
              :class="
                activeTab === type
                  ? 'bg-background text-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground'
              "
              @click="activeTab = type"
            >
              {{ CREATION_META[type].label }}
            </button>
          </div>

          <div class="p-6 space-y-4">
            <p class="text-sm text-muted-foreground">{{ activeMeta.desc }}</p>

            <template v-if="activeTab === 'ppt_renovation'">
              <div
                class="flex min-h-[140px] cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed border-border bg-secondary/20 px-4 py-8 text-center transition-colors hover:border-primary/50"
                @click="renovationInput?.click()"
              >
                <Upload class="mb-2 h-8 w-8 text-muted-foreground" />
                <p class="text-sm font-medium">
                  {{ renovationFile ? renovationFile.name : "点击或拖拽上传 PDF / PPTX" }}
                </p>
                <p class="mt-1 text-xs text-muted-foreground">推荐 PDF，单文件最大 200MB</p>
              </div>
              <input
                ref="renovationInput"
                type="file"
                class="hidden"
                accept=".pdf,.pptx,.ppt"
                @change="pickRenovationFile"
              />
            </template>

            <template v-else>
              <textarea
                v-model="content"
                class="min-h-[160px] w-full resize-y rounded-xl border border-input bg-background px-4 py-3 text-sm leading-relaxed focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                :placeholder="activeMeta.placeholder"
              />
            </template>

            <div v-if="activeTab !== 'ppt_renovation'" class="space-y-2">
              <label class="text-sm text-muted-foreground">页面风格（可选，出图前也可在预览步填写）</label>
              <input
                v-model="templateStyle"
                type="text"
                class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                placeholder="例如：现代简洁商务风，蓝白配色"
              />
            </div>

            <div class="flex flex-wrap items-center gap-4">
              <label class="flex items-center gap-2 text-sm text-muted-foreground">
                <ImagePlus class="h-4 w-4" />
                画幅
                <select
                  v-model="aspectRatio"
                  class="rounded-md border border-input bg-background px-2 py-1 text-sm text-foreground"
                >
                  <option v-for="r in ASPECT_RATIOS" :key="r" :value="r">{{ r }}</option>
                </select>
              </label>
            </div>

            <button
              type="button"
              class="inline-flex h-11 w-full items-center justify-center rounded-md bg-primary text-sm font-medium text-primary-foreground hover:opacity-90 disabled:opacity-50 sm:w-auto sm:px-10"
              :disabled="submitting"
              @click="submitCreate"
            >
              <Loader2 v-if="submitting" class="mr-2 h-4 w-4 animate-spin" />
              <Sparkles v-else class="mr-2 h-4 w-4" />
              创建项目
            </button>
          </div>
        </section>

        <section class="space-y-4">
          <div class="flex items-center justify-between">
            <h2 class="text-lg font-medium">历史项目</h2>
            <button
              type="button"
              class="text-xs text-muted-foreground hover:text-foreground"
              @click="load"
            >
              刷新
            </button>
          </div>
          <p v-if="!projects.length" class="rounded-xl border border-dashed border-border py-12 text-center text-sm text-muted-foreground">
            暂无项目，在上方创建第一个 PPT
          </p>
          <div v-else class="space-y-2">
            <RouterLink
              v-for="p in projects"
              :key="p.bindingId"
              :to="userRoutes.pptProjectEditor(p.bindingId)"
              class="block"
            >
              <ProjectCard :project="p" />
            </RouterLink>
          </div>
        </section>
      </template>
    </div>
  </AppShell>
</template>
