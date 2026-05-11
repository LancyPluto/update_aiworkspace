<script setup lang="ts">
import { RouterLink } from "vue-router"
import {
  ArrowLeft,
  ArrowRight,
  Pencil,
  Star,
  Zap,
  CheckCircle2,
  ChevronRight,
  Heart,
  Share2,
  Clock,
} from "lucide-vue-next"
import { computed, ref } from "vue"
import AppShell from "@/components/AppShell.vue"
<<<<<<< Updated upstream

=======
import { fetchToolByCode } from "@/api/toolApi"
import type { ToolDetail } from "@/api/types"
>>>>>>> Stashed changes
const props = defineProps<{
  id: string
}>()

<<<<<<< Updated upstream
=======
const tool = ref<ToolDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

>>>>>>> Stashed changes
const tab = ref<"intro" | "cases" | "input" | "output">("intro")

const title = computed(() =>
  props.id === "ec-copy" ? "电商商品文案生成" : `工具 · ${props.id}`,
)

<<<<<<< Updated upstream
const steps = [
  { title: "选择目标平台", desc: "选择投放的电商平台，AI 会根据平台特性调整文案风格与字数" },
  { title: "填写商品信息", desc: "输入商品名称、核心卖点、目标人群等关键信息" },
  { title: "AI 智能生成", desc: "AI 在数秒内生成符合平台规范的标题与详情页文案" },
  { title: "调整与发布", desc: "支持二次润色、一键复制到电商后台" },
]

const inputFields = [
  { name: "商品名称", required: true, type: "文本", desc: "例如：海岸夏季冰丝连衣裙" },
  { name: "核心卖点", required: true, type: "多行文本", desc: "3 - 5 个卖点" },
  { name: "目标人群", required: true, type: "选择", desc: "宝妈 / 白领 / 学生等" },
]
=======
onMounted(async () => {
  try {
    tool.value = await fetchToolByCode(props.id)
  } catch (e) {
    error.value = (e as Error).message || "加载工具详情失败"
  } finally {
    loading.value = false
  }
})
>>>>>>> Stashed changes
</script>

<template>
  <AppShell title="工具详情" :description="title">
    <div class="px-6 py-6 max-w-6xl mx-auto space-y-6">
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="{ name: 'ToolList' }" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> AI 工具超市
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span>电商运营</span>
        <ChevronRight class="h-3 w-3" />
        <span class="text-foreground">{{ title }}</span>
      </nav>

      <div class="rounded-xl border border-border bg-card p-6 shadow-sm">
        <div class="flex flex-col md:flex-row md:items-start gap-5">
          <div
            class="flex h-16 w-16 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-primary to-chart-2 text-primary-foreground"
          >
            <Pencil class="h-8 w-8" />
          </div>
          <div class="flex-1 min-w-0">
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="text-xl font-semibold">{{ title }}</h2>
              <span class="rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium text-destructive">
                HOT
              </span>
              <span class="rounded bg-secondary px-1.5 py-0.5 text-[10px] text-muted-foreground">电商运营</span>
            </div>
            <p class="mt-2 text-sm text-muted-foreground leading-relaxed">
              基于大模型与行业样本训练，可针对不同电商平台自动调整标题字数与风格。
            </p>
            <div class="mt-3 flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
              <span class="inline-flex items-center gap-1">
                <Star class="h-3.5 w-3.5 fill-warning text-warning" /> 4.9 / 5.0
              </span>
              <span>已使用 12,408 次</span>
              <span>平均生成耗时 12 秒</span>
            </div>
          </div>
          <div class="flex flex-col items-stretch md:items-end gap-2 shrink-0">
            <div class="flex items-center gap-2 text-sm">
              <Zap class="h-4 w-4 text-warning" />
              <span class="text-2xl font-semibold">12</span>
              <span class="text-xs text-muted-foreground">算力 / 次</span>
            </div>
            <RouterLink
              :to="'/tools/' + id + '/use'"
              class="inline-flex h-11 items-center justify-center rounded-md bg-primary px-8 text-sm font-medium text-primary-foreground hover:opacity-90"
            >
              开始使用 <ArrowRight class="ml-1.5 h-4 w-4" />
            </RouterLink>
            <div class="flex items-center gap-1 justify-end">
              <button
                type="button"
                class="inline-flex h-8 items-center rounded-md px-2 text-xs text-muted-foreground hover:bg-secondary"
              >
                <Heart class="h-3.5 w-3.5 mr-1" /> 收藏
              </button>
              <button
                type="button"
                class="inline-flex h-8 items-center rounded-md px-2 text-xs text-muted-foreground hover:bg-secondary"
              >
                <Share2 class="h-3.5 w-3.5 mr-1" /> 分享
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="grid gap-6 lg:grid-cols-3">
        <div class="lg:col-span-2 space-y-6">
          <div class="rounded-xl border border-border bg-card p-1 shadow-sm">
            <div class="flex flex-wrap gap-1">
              <button
                v-for="t in (['intro', 'cases', 'input', 'output'] as const)"
                :key="t"
                type="button"
                class="rounded-md px-3 py-2 text-xs font-medium transition"
                :class="tab === t ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-secondary'"
                @click="tab = t"
              >
                {{
                  t === "intro"
                    ? "工具介绍"
                    : t === "cases"
                      ? "适用场景"
                      : t === "input"
                        ? "输入说明"
                        : "输出示例"
                }}
              </button>
            </div>
          </div>

          <div v-show="tab === 'intro'" class="rounded-xl border border-border bg-card p-6 space-y-4 text-sm">
            <h4 class="text-sm font-semibold">核心能力</h4>
            <ul class="space-y-2 text-muted-foreground">
              <li
                v-for="c in ['多平台标题规范', '嵌入高权重关键词', '差异化人群风格', '一键生成多条候选']"
                :key="c"
                class="flex gap-2"
              >
                <CheckCircle2 class="h-4 w-4 shrink-0 text-success mt-0.5" />
                <span>{{ c }}</span>
              </li>
            </ul>
          </div>

          <div v-show="tab === 'cases'" class="rounded-xl border border-border bg-card p-6">
            <div class="grid sm:grid-cols-2 gap-3">
              <div
                v-for="c in [
                  { t: '大促活动', d: '合规活动文案' },
                  { t: '新品上架', d: '首发转化文案' },
                ]"
                :key="c.t"
                class="rounded-lg border border-border p-4"
              >
                <p class="text-sm font-semibold">{{ c.t }}</p>
                <p class="mt-1 text-xs text-muted-foreground">{{ c.d }}</p>
              </div>
            </div>
          </div>

          <div v-show="tab === 'input'" class="rounded-xl border border-border bg-card overflow-hidden">
            <table class="w-full text-sm">
              <thead class="bg-secondary/60">
                <tr class="text-xs text-muted-foreground">
                  <th class="px-4 py-2.5 text-left font-medium">字段</th>
                  <th class="px-4 py-2.5 text-left font-medium">类型</th>
                  <th class="px-4 py-2.5 text-left font-medium">必填</th>
                  <th class="px-4 py-2.5 text-left font-medium">说明</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-border">
                <tr v-for="f in inputFields" :key="f.name">
                  <td class="px-4 py-3 font-medium">{{ f.name }}</td>
                  <td class="px-4 py-3 text-muted-foreground">{{ f.type }}</td>
                  <td class="px-4 py-3">
                    <span
                      v-if="f.required"
                      class="rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] text-destructive"
                      >必填</span
                    >
                    <span v-else class="text-xs text-muted-foreground">可选</span>
                  </td>
                  <td class="px-4 py-3 text-muted-foreground">{{ f.desc }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-show="tab === 'output'" class="rounded-xl border border-border bg-card p-6 space-y-4">
            <div class="rounded-lg border border-border bg-secondary/40 p-4">
              <p class="text-xs text-muted-foreground mb-1">标题示例</p>
              <p class="text-sm font-medium">【海岸夏季新品】冰丝凉感连衣裙 …</p>
            </div>
          </div>
        </div>

        <div class="space-y-6">
          <div class="rounded-xl border border-border bg-card p-5 shadow-sm">
            <h3 class="text-sm font-semibold mb-4 flex items-center gap-2">
              <Clock class="h-4 w-4 text-primary" /> 使用步骤
            </h3>
            <ol class="space-y-4">
              <li v-for="(s, i) in steps" :key="s.title" class="flex gap-3">
                <span
                  class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary"
                >
                  {{ i + 1 }}
                </span>
                <div>
                  <p class="text-sm font-medium">{{ s.title }}</p>
                  <p class="text-xs text-muted-foreground mt-0.5">{{ s.desc }}</p>
                </div>
              </li>
            </ol>
          </div>

          <div class="rounded-xl border border-primary/20 bg-accent/40 p-5">
            <p class="text-xs text-muted-foreground">本次开始使用</p>
            <p class="mt-2 text-3xl font-semibold text-primary inline-flex items-baseline gap-1">
              12 <span class="text-sm font-normal text-muted-foreground">算力</span>
            </p>
            <RouterLink
              :to="'/tools/' + id + '/use'"
              class="mt-4 flex h-11 w-full items-center justify-center rounded-md bg-primary text-sm font-medium text-primary-foreground hover:opacity-90"
            >
              开始使用 <ArrowRight class="ml-1.5 h-4 w-4" />
            </RouterLink>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>
