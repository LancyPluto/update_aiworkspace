<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, ArrowRight, Cpu, Image, LoaderCircle, Presentation } from "lucide-vue-next"
import { pptApi, pptClientRequestId, type PptModelOptions } from "@/api/pptApi"
import { userRoutes } from "@/router/userRoutes"

const router = useRouter()
const submitting = ref(false)
const error = ref("")
const workbenchEnabled = ref(false)
const statusLoading = ref(true)
const statusMessage = ref("")
const modelOptions = ref<PptModelOptions>({ textModels: [], imageModels: [] })
const form = reactive({
  title: "",
  topic: "",
  pageCount: 10,
  aspectRatio: "16:9",
  language: "zh-CN",
  textModelConfigId: null as number | null,
  imageModelConfigId: null as number | null,
})
const canSubmit = computed(() =>
  workbenchEnabled.value
    && modelOptions.value.textModels.length > 0
    && modelOptions.value.imageModels.length > 0
    && form.title.trim().length > 0
    && form.topic.trim().length >= 5,
)

onMounted(async () => {
  try {
    const [status, options] = await Promise.all([pptApi.status(), pptApi.modelOptions()])
    workbenchEnabled.value = status.enabled
    statusMessage.value = status.message
    modelOptions.value = options
    form.textModelConfigId = options.textModels.find(model => model.recommended)?.modelConfigId
      ?? options.textModels[0]?.modelConfigId
      ?? null
    form.imageModelConfigId = options.imageModels.find(model => model.recommended)?.modelConfigId
      ?? options.imageModels[0]?.modelConfigId
      ?? null
  } catch (cause) {
    workbenchEnabled.value = false
    statusMessage.value = cause instanceof Error ? cause.message : "暂时无法确认 PPT 工作台状态"
  } finally {
    statusLoading.value = false
  }
})

async function submit() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  error.value = ""
  try {
    const project = await pptApi.createProject({
      title: form.title.trim(),
      topic: form.topic.trim(),
      pageCount: form.pageCount,
      aspectRatio: form.aspectRatio,
      language: form.language,
      creationType: "AI_GENERATED",
      textModelConfigId: form.textModelConfigId,
      imageModelConfigId: form.imageModelConfigId,
    })
    await pptApi.submitJob(project.projectId, {
      jobType: "GENERATE_OUTLINE",
      clientRequestId: pptClientRequestId("outline"),
      payload: { topic: form.topic.trim(), pageCount: form.pageCount, autoContinue: true },
    })
    await router.replace(userRoutes.pptProject(project.projectId))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "创建失败，请稍后重试"
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="grid h-full overflow-y-auto bg-[#070a0f] text-white lg:grid-cols-[minmax(0,1fr)_420px]">
    <section class="mx-auto flex w-full max-w-3xl flex-col justify-center px-6 py-12 lg:px-12">
      <button class="mb-10 flex w-fit items-center gap-2 text-sm text-white/45 hover:text-white" type="button" @click="router.push(userRoutes.ppt)">
        <ArrowLeft class="h-4 w-4" /> 返回项目
      </button>
      <span class="mb-5 grid h-12 w-12 place-items-center rounded-xl bg-cyan-300/10 text-cyan-300">
        <Presentation class="h-6 w-6" />
      </span>
      <h1 class="text-3xl font-semibold">创建一份有观点的演示</h1>
      <p class="mt-3 text-sm leading-6 text-white/45">AI 可以替你排版，但“讲什么”仍然值得你亲自决定。</p>
      <p
        v-if="!statusLoading && !workbenchEnabled"
        class="mt-5 rounded-xl border border-amber-300/20 bg-amber-300/10 px-4 py-3 text-sm text-amber-100"
      >
        {{ statusMessage }}
      </p>

      <form class="mt-10 space-y-6" @submit.prevent="submit">
        <label class="block">
          <span class="mb-2 block text-sm font-medium text-white/70">项目名称</span>
          <input v-model="form.title" maxlength="255" class="h-12 w-full rounded-xl border border-white/10 bg-white/[0.04] px-4 outline-none transition focus:border-cyan-300/60" placeholder="例如：2026 年产品战略发布会" />
        </label>
        <label class="block">
          <span class="mb-2 block text-sm font-medium text-white/70">你想讲清楚什么？</span>
          <textarea v-model="form.topic" maxlength="10000" rows="6" class="w-full resize-none rounded-xl border border-white/10 bg-white/[0.04] p-4 leading-6 outline-none transition focus:border-cyan-300/60" placeholder="给出受众、核心观点、希望达成的效果。信息越具体，AI 越不像在会议上临时被点名。" />
        </label>
        <div class="grid gap-4 sm:grid-cols-3">
          <label>
            <span class="mb-2 block text-xs text-white/45">页数</span>
            <input v-model.number="form.pageCount" type="number" min="1" max="100" class="h-11 w-full rounded-xl border border-white/10 bg-white/[0.04] px-3 outline-none focus:border-cyan-300/60" />
          </label>
          <label>
            <span class="mb-2 block text-xs text-white/45">画幅</span>
            <select v-model="form.aspectRatio" class="h-11 w-full rounded-xl border border-white/10 bg-[#0d121a] px-3 outline-none focus:border-cyan-300/60">
              <option value="16:9">16:9 宽屏</option><option value="4:3">4:3 标准</option>
            </select>
          </label>
          <label>
            <span class="mb-2 block text-xs text-white/45">语言</span>
            <select v-model="form.language" class="h-11 w-full rounded-xl border border-white/10 bg-[#0d121a] px-3 outline-none focus:border-cyan-300/60">
              <option value="zh-CN">简体中文</option><option value="en-US">English</option>
            </select>
          </label>
        </div>
        <section class="rounded-2xl border border-white/10 bg-white/[0.025] p-4">
          <div class="mb-4">
            <p class="text-sm font-medium text-white/75">生成模型</p>
            <p class="mt-1 text-xs leading-5 text-white/35">从平台当前可执行模型中选择；密钥、路由和故障切换仍由平台托管。</p>
          </div>
          <div class="grid gap-4 sm:grid-cols-2">
            <label>
              <span class="mb-2 flex items-center gap-2 text-xs text-white/45"><Cpu class="h-3.5 w-3.5" />文本模型</span>
              <select v-model="form.textModelConfigId" class="h-11 w-full rounded-xl border border-white/10 bg-[#0d121a] px-3 text-sm outline-none focus:border-cyan-300/60">
                <option v-for="model in modelOptions.textModels" :key="model.modelConfigId" :value="model.modelConfigId">
                  {{ model.displayName }}{{ model.recommended ? " · 推荐" : "" }}
                </option>
              </select>
            </label>
            <label>
              <span class="mb-2 flex items-center gap-2 text-xs text-white/45"><Image class="h-3.5 w-3.5" />图像模型</span>
              <select v-model="form.imageModelConfigId" class="h-11 w-full rounded-xl border border-white/10 bg-[#0d121a] px-3 text-sm outline-none focus:border-cyan-300/60">
                <option v-for="model in modelOptions.imageModels" :key="model.modelConfigId" :value="model.modelConfigId">
                  {{ model.displayName }}{{ model.recommended ? " · 推荐" : "" }}
                </option>
              </select>
            </label>
          </div>
        </section>
        <p v-if="error" class="rounded-xl border border-rose-400/25 bg-rose-400/10 px-4 py-3 text-sm text-rose-200">{{ error }}</p>
        <button :disabled="statusLoading || !canSubmit || submitting" class="flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-cyan-400 font-semibold text-slate-950 transition hover:bg-cyan-300 disabled:cursor-not-allowed disabled:opacity-40" type="submit">
          <LoaderCircle v-if="submitting" class="h-4 w-4 animate-spin" />
          <LoaderCircle v-else-if="statusLoading" class="h-4 w-4 animate-spin" />
          <template v-else-if="workbenchEnabled">一键生成整套 PPT <ArrowRight class="h-4 w-4" /></template>
          <template v-else>工作台维护中</template>
        </button>
      </form>
    </section>
    <aside class="hidden border-l border-white/8 bg-[radial-gradient(circle_at_50%_20%,rgba(34,211,238,.15),transparent_45%),#0a0e14] p-10 lg:flex lg:flex-col lg:justify-center">
      <div class="rounded-3xl border border-white/10 bg-black/20 p-6">
        <div class="mb-5 aspect-video rounded-xl border border-white/10 bg-gradient-to-br from-cyan-300/20 via-slate-900 to-slate-950 p-6">
          <div class="h-2 w-16 rounded bg-cyan-300/70" />
          <div class="mt-8 h-5 w-4/5 rounded bg-white/80" />
          <div class="mt-3 h-2 w-3/5 rounded bg-white/25" />
        </div>
        <strong>先结构，后视觉</strong>
        <p class="mt-2 text-sm leading-6 text-white/45">一次提交自动完成大纲、页面描述、视觉生成和导出。每一步独立保存，失败的是一步，不是整份心血。</p>
      </div>
    </aside>
  </main>
</template>
