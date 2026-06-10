<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { ChevronRight, Infinity } from "lucide-vue-next"
import WorkspaceShell from "@/components/workspace/WorkspaceShell.vue"
import WorkspaceComposer from "@/components/workspace/WorkspaceComposer.vue"
import WorkspaceUiKit from "@/components/workspace/WorkspaceUiKit.vue"
import WorkspaceToolCard from "@/components/workspace/WorkspaceToolCard.vue"
import { fetchToolByCode, fetchTools } from "@/api/toolApi"
import type { ToolDetail, ToolSummary } from "@/api/types"
import { toWorkspaceToolCards, type ToolCardModel } from "@/adapters/toolPresentationAdapter"
import { workspaceMedia, workspaceTemplates } from "@/data/creativeHub"
import { buildComposerModelOptions, type CreatorMode, type ComposerState } from "@/adapters/creatorAdapter"
import type { WorkspaceMediaItem } from "@/types/workspace"
import { useAuthStore } from "@/store/authStore"

const activeMode = ref<CreatorMode>("video")
const auth = useAuthStore()
const router = useRouter()
const backendTools = ref<ToolSummary[]>([])
const toolsLoading = ref(false)
const toolsError = ref("")
const selectedComposerToolCode = ref("")
const selectedComposerToolDetail = ref<ToolDetail | null>(null)
const toolDetailCache = ref<Record<string, ToolDetail>>({})
const toolDetailLoading = ref(false)

const imageHotCards: WorkspaceMediaItem[] = [
  { title: "图像工具", subtitle: "AI 修图", image: workspaceMedia.editor, to: "/image", tag: "工具" },
  { title: "热门模板", subtitle: "快速出图", image: workspaceMedia.effects, to: "/image", tag: "模板" },
  { title: "商品照片", subtitle: "电商", image: workspaceMedia.product, to: "/image", tag: "营销" },
]

const agentHotCards: WorkspaceMediaItem[] = [
  { title: "营销工作室", subtitle: "广告素材", image: workspaceMedia.marketing, to: "/agent", tag: "智能体" },
  { title: "复刻视频广告", subtitle: "拆解与再创作", image: workspaceMedia.agent, to: "/agent", tag: "热门" },
  { title: "UGC 视频广告", subtitle: "自然口播", image: workspaceMedia.avatar, to: "/agent", tag: "UGC" },
]

const backendCards = computed<ToolCardModel[]>(() => {
  const filter = activeMode.value === "agent" ? "all" : activeMode.value
  return toWorkspaceToolCards(backendTools.value, filter).slice(0, 8)
})

const homeContent = computed(() => {
  if (activeMode.value === "image") {
    return {
      promo: {
        badge: "365 天灵感加速",
        text: "用真实工具配置生成图片、海报和商业素材",
        cta: "打开图片创作",
        to: "/image",
      },
      hot: imageHotCards,
      toolTitle: "图像工具",
      toolLink: "/image",
      exploreTitle: "探索更多图片玩法",
      explore: imageHotCards,
    }
  }

  if (activeMode.value === "agent") {
    return {
      promo: null,
      hot: agentHotCards,
      toolTitle: "面向营销人员",
      toolLink: "/agent",
      exploreTitle: "面向创作者",
      explore: agentHotCards,
    }
  }

  return {
    promo: {
      badge: "10 天创作体验",
      text: "视频、图片、智能体工具统一接入真实任务系统",
      cta: "开始创作",
      to: "/create",
    },
    hot: workspaceTemplates,
    toolTitle: "AI 工具",
    toolLink: "/tool",
    exploreTitle: "探索更多 AI 功能",
    explore: workspaceTemplates,
  }
})

async function loadBackendTools() {
  toolsLoading.value = true
  toolsError.value = ""
  try {
    const page = await fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 120 } })
    backendTools.value = page.list
    selectedComposerToolCode.value = defaultComposerToolCode(activeMode.value)
  } catch (error) {
    backendTools.value = []
    selectedComposerToolCode.value = ""
    toolsError.value = error instanceof Error ? error.message : "工具列表加载失败"
  } finally {
    toolsLoading.value = false
  }
}

function defaultComposerToolCode(mode: CreatorMode): string {
  return buildComposerModelOptions(backendTools.value, mode)[0]?.toolCode || ""
}

function onModeChange(mode: CreatorMode) {
  activeMode.value = mode
  selectedComposerToolCode.value = defaultComposerToolCode(mode)
}

function onToolSelect(toolCode: string) {
  selectedComposerToolCode.value = toolCode
}

async function loadComposerToolDetail(toolCode: string) {
  if (!toolCode) {
    selectedComposerToolDetail.value = null
    return
  }
  if (toolDetailCache.value[toolCode]) {
    selectedComposerToolDetail.value = toolDetailCache.value[toolCode]
    return
  }
  toolDetailLoading.value = true
  try {
    const detail = await fetchToolByCode(toolCode, { token: auth.token })
    toolDetailCache.value = { ...toolDetailCache.value, [toolCode]: detail }
    selectedComposerToolDetail.value = detail
  } catch (error) {
    selectedComposerToolDetail.value = null
    toolsError.value = error instanceof Error ? error.message : "读取工具配置失败"
  } finally {
    toolDetailLoading.value = false
  }
}

function openCreateFromComposer(state: ComposerState) {
  const query: Record<string, string> = {
    modality: state.mode,
  }
  const toolCode = state.toolCode || selectedComposerToolCode.value
  if (toolCode) query.tool = toolCode
  if (state.prompt.trim()) query.prompt = state.prompt.trim()
  if (state.ratio) query.ratio = state.ratio
  if (state.durationSeconds) query.duration = String(state.durationSeconds)
  if (state.quality) query.quality = state.quality
  if (state.outputCount) query.count = String(state.outputCount)
  if (state.modelConfigId) query.modelConfigId = String(state.modelConfigId)
  if (state.modelLabel) query.modelLabel = state.modelLabel
  if (state.uploadedAssetUrl) query.asset = state.uploadedAssetUrl
  if (["image", "video"].includes(state.mode)) query.autoSubmit = "1"
  void router.push({ path: "/create", query })
}

watch(selectedComposerToolCode, (toolCode) => {
  void loadComposerToolDetail(toolCode)
})

onMounted(loadBackendTools)
</script>

<template>
  <WorkspaceShell>
    <WorkspaceComposer
      :tools="backendTools"
      :tool-id="selectedComposerToolCode"
      :tool-detail="selectedComposerToolDetail"
      :tools-loading="toolsLoading || toolDetailLoading"
      @mode-change="onModeChange"
      @tool-select="onToolSelect"
      @submit="openCreateFromComposer"
    />

    <div v-if="homeContent.promo" class="workspace-promo-strip">
      <span><Infinity :size="14" />{{ homeContent.promo.badge }}</span>
      <strong>{{ homeContent.promo.text }}</strong>
      <RouterLink :to="homeContent.promo.to">{{ homeContent.promo.cta }} <ChevronRight :size="16" /></RouterLink>
    </div>

    <section class="workspace-section-block workspace-hot-section">
      <h2>热门功能</h2>
      <WorkspaceUiKit kind="mediaRail" :media="homeContent.hot" />
    </section>

    <section class="workspace-section-block">
      <div class="workspace-section-head">
        <h2>{{ homeContent.toolTitle }}</h2>
        <RouterLink :to="homeContent.toolLink">查看更多 <ChevronRight :size="16" /></RouterLink>
      </div>
      <div v-if="toolsLoading" class="workspace-state">正在加载真实工具列表...</div>
      <div v-else-if="backendCards.length > 0" class="workspace-official-tool-grid">
        <WorkspaceToolCard v-for="tool in backendCards" :key="tool.id" :tool="tool" />
      </div>
      <div v-else class="workspace-state">
        <div>
          <p>{{ toolsError || "当前模式暂无后台启用的工具" }}</p>
          <button class="workspace-pink-button" type="button" @click="loadBackendTools">重试加载真实工具</button>
        </div>
      </div>
    </section>

    <section class="workspace-section-block">
      <div class="workspace-section-head">
        <h2>{{ homeContent.exploreTitle }}</h2>
        <RouterLink to="/tool">查看更多 <ChevronRight :size="16" /></RouterLink>
      </div>
      <WorkspaceUiKit kind="mediaRail" :media="homeContent.explore" />
    </section>
  </WorkspaceShell>
</template>
