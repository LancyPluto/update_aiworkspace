<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink, useRouter } from "vue-router"
import {
  ArrowRight,
  Bot,
  Braces,
  CheckCircle2,
  ClipboardList,
  Clock,
  FileText,
  Files,
  Image as ImageIcon,
  Layers3,
  Loader2,
  Music,
  Sparkles,
  Store,
  Video,
  Wallet,
  Zap,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchCreditAccount } from "@/api/creditApi"
import { fetchTasks } from "@/api/taskApi"
import { fetchTools } from "@/api/toolApi"
import type { CreditAccount, TaskDetail, ToolSummary } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

type AgentPlanStep = {
  id: string
  title: string
  intent: string
  modality: string
  preferredToolCode?: string
  dependsOn?: string[]
  suggestedFields: Array<{ label: string; value: string }>
  output: string
  status: "ready" | "waiting" | "optional"
}

type AgentPlan = {
  id: string
  title: string
  goal: string
  source: "mock" | "agent"
  generatedAt: string
  assumptions: string[]
  steps: AgentPlanStep[]
}

const auth = useAuthStore()
const router = useRouter()

const loading = ref(false)
const credit = ref<CreditAccount | null>(null)
const tools = ref<ToolSummary[]>([])
const tasks = ref<TaskDetail[]>([])
const selectedModality = ref("TEXT")
const selectedToolCode = ref<string | null>(null)
const selectedStepId = ref("copy")

const mockAgentPlan: AgentPlan = {
  id: "mock-launch-plan",
  title: "新品香薰蜡烛上新素材方案",
  goal: "用现有 AI 工具快速产出一套可发布的种草文案、商品主图和口播素材。",
  source: "mock",
  generatedAt: "示例方案",
  assumptions: ["目标渠道是小红书和电商详情页", "用户希望先人工确认每一步表单", "初版不让 Agent 自动执行任务"],
  steps: [
    {
      id: "copy",
      title: "生成种草文案",
      intent: "提炼卖点、使用场景和标题，作为后续视觉提示词的基础。",
      modality: "TEXT",
      suggestedFields: [
        { label: "产品主体", value: "新品香薰蜡烛" },
        { label: "重点卖点", value: "助眠、礼盒感、天然香调" },
        { label: "语气", value: "真实体验、轻种草" },
      ],
      output: "3 组标题、正文和卖点短句",
      status: "ready",
    },
    {
      id: "image",
      title: "生成商品主图",
      intent: "把文案里的主体、场景和风格转成图片工具表单参数。",
      modality: "IMAGE",
      dependsOn: ["copy"],
      suggestedFields: [
        { label: "画面描述", value: "香薰蜡烛放在床头柜，暖光，礼盒包装" },
        { label: "画面比例", value: "1:1" },
        { label: "风格", value: "写实 / 电商 / 极简" },
      ],
      output: "1-2 张商品主图候选",
      status: "waiting",
    },
    {
      id: "audio",
      title: "生成短视频口播",
      intent: "把第一步文案改成自然口播，用于短视频或直播切片。",
      modality: "AUDIO",
      dependsOn: ["copy"],
      suggestedFields: [
        { label: "口播内容", value: "从文案结果里挑一版短句" },
        { label: "声音风格", value: "自然、清晰、亲和" },
        { label: "语速", value: "适中" },
      ],
      output: "15-30 秒口播音频",
      status: "optional",
    },
  ],
}

const modalityLabels: Record<string, string> = {
  TEXT: "文本",
  IMAGE: "图片",
  AUDIO: "音频",
  VIDEO: "视频",
  JSON: "数据",
  FILE: "文件",
  MULTIMODAL: "多模态",
}

const statusLabels: Record<AgentPlanStep["status"], string> = {
  ready: "可执行",
  waiting: "等上一步",
  optional: "可选",
}

const modalityIcons = {
  TEXT: FileText,
  IMAGE: ImageIcon,
  AUDIO: Music,
  VIDEO: Video,
  JSON: Braces,
  FILE: Files,
  MULTIMODAL: Layers3,
}

const activePlan = computed(() => mockAgentPlan)
const selectedStep = computed(() => activePlan.value.steps.find((step) => step.id === selectedStepId.value) || activePlan.value.steps[0])
const successCount = computed(() => tasks.value.filter((task) => task.status === "SUCCESS").length)
const runningCount = computed(() =>
  tasks.value.filter((task) => ["CREATED", "QUEUED", "PROCESSING", "RETRYING"].includes(task.status)).length,
)

const toolsByModality = computed(() => {
  const groups = new Map<string, ToolSummary[]>()
  for (const tool of tools.value) {
    const key = normalizeModality(tool.outputModality)
    groups.set(key, [...(groups.get(key) || []), tool])
  }
  return groups
})

const modalityTabs = computed(() => {
  const order = ["TEXT", "IMAGE", "AUDIO", "VIDEO", "JSON", "FILE", "MULTIMODAL"]
  return [...toolsByModality.value.entries()]
    .sort(([a], [b]) => {
      const ia = order.indexOf(a)
      const ib = order.indexOf(b)
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
    })
    .map(([key, list]) => ({
      key,
      label: modalityLabels[key] || key,
      count: list.length,
      icon: modalityIcons[key as keyof typeof modalityIcons] || Sparkles,
    }))
})

const currentTools = computed(() => toolsByModality.value.get(selectedModality.value) || [])
const selectedTool = computed(() => tools.value.find((tool) => tool.toolCode === selectedToolCode.value) || null)
const stepTool = computed(() => {
  const step = selectedStep.value
  const modalityTools = toolsByModality.value.get(normalizeModality(step.modality)) || []
  if (step.preferredToolCode) {
    return modalityTools.find((tool) => tool.toolCode === step.preferredToolCode) || modalityTools[0] || null
  }
  return selectedTool.value && normalizeModality(selectedTool.value.outputModality) === normalizeModality(step.modality)
    ? selectedTool.value
    : modalityTools[0] || null
})

async function loadDashboard() {
  loading.value = true
  try {
    const [creditRes, toolRes, taskRes] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 100 } }),
      fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 6 } }),
    ])
    credit.value = creditRes
    tools.value = toolRes.list
    tasks.value = taskRes.list
    applyStep(selectedStep.value)
  } finally {
    loading.value = false
  }
}

function normalizeModality(value?: string | null) {
  return (value || "TEXT").trim().toUpperCase()
}

function selectModality(key: string) {
  selectedModality.value = key
  selectedToolCode.value = toolsByModality.value.get(key)?.[0]?.toolCode || null
}

function applyStep(step: AgentPlanStep) {
  selectedStepId.value = step.id
  selectedModality.value = normalizeModality(step.modality)
  const preferredTool = toolsByModality.value
    .get(selectedModality.value)
    ?.find((tool) => !step.preferredToolCode || tool.toolCode === step.preferredToolCode)
  selectedToolCode.value = preferredTool?.toolCode || toolsByModality.value.get(selectedModality.value)?.[0]?.toolCode || null
}

function openStepTool() {
  const tool = stepTool.value
  if (!tool) return
  router.push(userRoutes.toolUse(tool.toolCode))
}

function modalityLabel(value?: string | null) {
  const key = normalizeModality(value)
  return modalityLabels[key] || key
}

onMounted(loadDashboard)
</script>

<template>
  <AppShell title="工作台" description="承接 Agent 结构化方案，按工具表单逐步落地">
    <div class="grid min-h-[calc(100vh-4rem)] bg-background lg:grid-cols-[300px_minmax(0,1fr)_300px]">
      <aside class="border-r border-border bg-card/50 p-5">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-xs text-muted-foreground">生成类型</p>
            <h2 class="text-base font-semibold">落地工具</h2>
          </div>
          <RouterLink
            :to="userRoutes.toolList"
            class="inline-flex h-8 items-center gap-1 rounded-md border border-border px-2 text-xs hover:bg-secondary"
          >
            <Store class="h-3.5 w-3.5" /> 超市
          </RouterLink>
        </div>

        <div class="mt-5 grid grid-cols-2 gap-2">
          <button
            v-for="tab in modalityTabs"
            :key="tab.key"
            type="button"
            class="rounded-lg border px-3 py-3 text-left transition"
            :class="selectedModality === tab.key ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-background hover:bg-secondary'"
            @click="selectModality(tab.key)"
          >
            <component :is="tab.icon" class="h-4 w-4" />
            <span class="mt-2 block text-sm font-medium">{{ tab.label }}</span>
            <span class="text-[11px] text-muted-foreground">{{ tab.count }} 个工具</span>
          </button>
        </div>

        <div class="mt-6">
          <div class="mb-2 flex items-center justify-between">
            <p class="text-xs font-medium text-muted-foreground">可用表单工具</p>
            <Loader2 v-if="loading" class="h-3.5 w-3.5 animate-spin text-muted-foreground" />
          </div>
          <div class="space-y-2">
            <button
              v-for="tool in currentTools"
              :key="tool.id"
              type="button"
              class="flex w-full items-center justify-between rounded-lg border px-3 py-3 text-left transition"
              :class="selectedToolCode === tool.toolCode ? 'border-primary bg-primary/10' : 'border-border bg-background hover:bg-secondary'"
              @click="selectedToolCode = tool.toolCode"
            >
              <span class="min-w-0">
                <span class="block truncate text-sm font-medium">{{ tool.toolName }}</span>
                <span class="text-xs text-muted-foreground">{{ modalityLabel(tool.outputModality) }} · {{ tool.estimatedCreditCost }} 算力</span>
              </span>
              <ArrowRight class="h-4 w-4 shrink-0 text-muted-foreground" />
            </button>
            <div v-if="!loading && currentTools.length === 0" class="rounded-lg border border-dashed border-border px-3 py-8 text-center text-sm text-muted-foreground">
              当前类型暂无工具
            </div>
          </div>
        </div>
      </aside>

      <section class="flex min-w-0 flex-col px-6 py-6">
        <div class="mx-auto flex w-full max-w-5xl flex-1 flex-col justify-center">
          <div class="text-center">
            <div class="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10 text-primary">
              <ClipboardList class="h-7 w-7" />
            </div>
            <h2 class="mt-5 text-2xl font-semibold">{{ activePlan.title }}</h2>
            <p class="mx-auto mt-2 max-w-2xl text-sm text-muted-foreground">{{ activePlan.goal }}</p>
          </div>

          <section class="mt-8 rounded-xl border border-border bg-card p-5">
            <div class="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p class="text-xs text-muted-foreground">Agent 结构化方案</p>
                <h3 class="mt-1 text-lg font-semibold">执行步骤</h3>
              </div>
              <RouterLink
                :to="userRoutes.agent"
                class="inline-flex items-center gap-1 rounded-md border border-primary/30 bg-primary/10 px-3 py-2 text-xs font-medium text-primary"
              >
                <Bot class="h-3.5 w-3.5" /> 重新生成方案
              </RouterLink>
            </div>

            <div class="mt-5 grid gap-3 xl:grid-cols-3">
              <button
                v-for="(step, index) in activePlan.steps"
                :key="step.id"
                type="button"
                class="rounded-lg border p-4 text-left transition"
                :class="selectedStepId === step.id ? 'border-primary bg-primary/10' : 'border-border bg-background hover:border-primary/40'"
                @click="applyStep(step)"
              >
                <div class="flex items-start justify-between gap-3">
                  <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">{{ index + 1 }}</span>
                  <span class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-muted-foreground">{{ statusLabels[step.status] }}</span>
                </div>
                <h4 class="mt-3 font-semibold">{{ step.title }}</h4>
                <p class="mt-1 line-clamp-2 text-xs text-muted-foreground">{{ step.intent }}</p>
                <p class="mt-3 text-xs text-primary">{{ modalityLabel(step.modality) }} · {{ step.output }}</p>
              </button>
            </div>
          </section>

          <div class="mt-5 grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
            <section class="rounded-xl border border-border bg-card p-5">
              <div class="flex flex-wrap items-start justify-between gap-4">
                <div class="min-w-0">
                  <p class="text-xs text-muted-foreground">当前步骤</p>
                  <h3 class="mt-1 text-xl font-semibold">{{ selectedStep.title }}</h3>
                  <p class="mt-2 text-sm text-muted-foreground">{{ selectedStep.intent }}</p>
                </div>
                <span class="rounded-full bg-secondary px-2.5 py-1 text-xs text-muted-foreground">
                  {{ modalityLabel(selectedStep.modality) }}
                </span>
              </div>

              <div class="mt-5 grid gap-3 md:grid-cols-3">
                <div
                  v-for="field in selectedStep.suggestedFields"
                  :key="field.label"
                  class="rounded-lg border border-border bg-background px-3 py-3"
                >
                  <p class="text-xs text-muted-foreground">{{ field.label }}</p>
                  <p class="mt-1 text-sm font-medium">{{ field.value }}</p>
                </div>
              </div>

              <div class="mt-5 rounded-lg border border-dashed border-border bg-background px-4 py-3 text-sm text-muted-foreground">
                这些字段暂时只作为填表参考；后续接入表单预填后，可直接带入工具页。
              </div>
            </section>

            <section class="rounded-xl border border-border bg-card p-5">
              <p class="text-xs text-muted-foreground">推荐表单工具</p>
              <h3 class="mt-1 truncate text-xl font-semibold">{{ stepTool?.toolName || "暂无匹配工具" }}</h3>
              <p class="mt-2 line-clamp-3 text-sm text-muted-foreground">
                {{ stepTool?.description || stepTool?.configNote || "当前步骤会按生成模态匹配工具超市中的可用工具。" }}
              </p>
              <div class="mt-5 grid gap-3">
                <div class="rounded-lg border border-border bg-background px-3 py-3">
                  <p class="text-xs text-muted-foreground">预计消耗</p>
                  <p class="mt-1 text-lg font-semibold">{{ stepTool?.estimatedCreditCost ?? "--" }} 算力</p>
                </div>
                <div class="rounded-lg border border-border bg-background px-3 py-3">
                  <p class="text-xs text-muted-foreground">输入/输出</p>
                  <p class="mt-1 text-sm font-medium">
                    {{ stepTool?.inputModality || "FORM" }} → {{ stepTool?.outputModality || selectedStep.modality }}
                  </p>
                </div>
              </div>
              <button
                type="button"
                class="mt-5 inline-flex h-10 w-full items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
                :disabled="!stepTool"
                @click="openStepTool"
              >
                打开此步骤表单
                <ArrowRight class="h-4 w-4" />
              </button>
            </section>
          </div>
        </div>
      </section>

      <aside class="border-l border-border bg-card/40 p-5">
        <div class="grid grid-cols-2 gap-3">
          <div class="rounded-lg border border-border bg-background p-3">
            <div class="flex items-center justify-between text-xs text-muted-foreground">
              <span>可用算力</span>
              <Wallet class="h-3.5 w-3.5 text-primary" />
            </div>
            <p class="mt-2 text-xl font-semibold">{{ credit?.available ?? "--" }}</p>
          </div>
          <div class="rounded-lg border border-border bg-background p-3">
            <div class="flex items-center justify-between text-xs text-muted-foreground">
              <span>进行中</span>
              <Clock class="h-3.5 w-3.5 text-primary" />
            </div>
            <p class="mt-2 text-xl font-semibold">{{ runningCount }}</p>
          </div>
          <div class="rounded-lg border border-border bg-background p-3">
            <div class="flex items-center justify-between text-xs text-muted-foreground">
              <span>成功任务</span>
              <CheckCircle2 class="h-3.5 w-3.5 text-primary" />
            </div>
            <p class="mt-2 text-xl font-semibold">{{ successCount }}</p>
          </div>
          <div class="rounded-lg border border-border bg-background p-3">
            <div class="flex items-center justify-between text-xs text-muted-foreground">
              <span>已用算力</span>
              <Zap class="h-3.5 w-3.5 text-warning" />
            </div>
            <p class="mt-2 text-xl font-semibold">{{ credit?.totalConsumed ?? "--" }}</p>
          </div>
        </div>

        <section class="mt-5 rounded-lg border border-border bg-background">
          <div class="flex items-center justify-between border-b border-border px-4 py-3">
            <h3 class="text-sm font-semibold">最近任务</h3>
            <RouterLink :to="userRoutes.myTasks" class="text-xs text-primary">全部</RouterLink>
          </div>
          <div class="divide-y divide-border">
            <RouterLink
              v-for="task in tasks"
              :key="task.taskId"
              :to="task.status === 'SUCCESS' ? userRoutes.taskResult(String(task.taskId)) : userRoutes.taskStatus(String(task.taskId))"
              class="block px-4 py-3 hover:bg-secondary/60"
            >
              <div class="flex items-center justify-between gap-3">
                <p class="truncate text-sm font-medium">{{ task.toolName }}</p>
                <span class="rounded bg-secondary px-1.5 py-0.5 text-[10px]">{{ task.status }}</span>
              </div>
              <p class="mt-1 text-xs text-muted-foreground">{{ task.taskNo }}</p>
            </RouterLink>
            <div v-if="!loading && tasks.length === 0" class="px-4 py-8 text-center text-sm text-muted-foreground">
              暂无任务记录
            </div>
          </div>
        </section>

        <section class="mt-5 rounded-lg border border-border bg-background p-4">
          <p class="text-xs text-muted-foreground">方案假设</p>
          <ul class="mt-3 space-y-2 text-sm">
            <li v-for="item in activePlan.assumptions" :key="item" class="flex gap-2">
              <span class="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-primary" />
              <span>{{ item }}</span>
            </li>
          </ul>
        </section>
      </aside>
    </div>
  </AppShell>
</template>
