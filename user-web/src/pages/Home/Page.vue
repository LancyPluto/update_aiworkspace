<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from "vue"
import { RouterLink, useRouter } from "vue-router"
import {
  ArrowRight,
  Copy,
  ExternalLink,
  Image as ImageIcon,
  Info,
  Loader2,
  Music,
  Search,
  Sparkles,
  Video,
  WandSparkles,
  X,
} from "lucide-vue-next"
import ToolComparisonCover from "@/components/ToolComparisonCover.vue"
import OptimizedImage from "@/components/OptimizedImage.vue"
import { searchCommunityPosts } from "@/api/communityApi"
import { fetchTools } from "@/api/toolApi"
import { fetchTasks } from "@/api/taskApi"
import type { CommunityPost, PageResult, TaskDetail, ToolSummary } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { userRoutes } from "@/router/userRoutes"
import { communityDisplayTitle } from "@/utils/communityDisplay"
import { resolveCommunityPrompt } from "@/utils/communityPostNormalize"
import {
  normalizeCommunityMediaUrl,
  resolveCommunityImageUrls,
  resolveCommunityPostKind,
  resolveOssVideoPosterUrl,
} from "@/utils/communityPostMedia"
import { useTypingPlaceholder } from "@/composables/useTypingPlaceholder"
import { isVideoPreviewUrl, normalizeMediaUrl, resolveSummaryToolCoverUrl } from "@/utils/toolCoverMedia"

type HomeTab = "ALL" | "IMAGE" | "VIDEO" | "AUDIO"

type RecentToolEntry = {
  tool: ToolSummary
  task: TaskDetail
}

const auth = useAuthStore()
const router = useRouter()

const promptText = ref("")
const promptInputRef = ref<HTMLInputElement | null>(null)
const promptInputFocused = ref(false)

const heroPromptPlaceholders = [
  "一个雨夜现代日式茶馆，温润的烛光，木桌上的铁茶壶...",
  "一个赛博朋克女孩自拍，横向16:9构图，冷蓝胶片色调...",
  "一首轻快欢脱的Lo-Fi电子乐，适合午后工作背景音乐...",
  "超写实产品摄影，磨砂玻璃香水瓶，柔光棚拍，浅景深...",
]

const typingPlaceholder = useTypingPlaceholder({
  placeholders: heroPromptPlaceholders,
  typeDelayMs: 100,
  deleteDelayMs: 50,
  pauseAfterCompleteMs: 3000,
  pauseBeforeNextMs: 500,
})

const promptPlaceholder = computed(() =>
  promptInputFocused.value || promptText.value.trim() ? "" : typingPlaceholder.currentText.value,
)
const tools = ref<ToolSummary[]>([])
const tasks = ref<TaskDetail[]>([])
const communityPosts = ref<CommunityPost[]>([])
const loading = ref(false)
const error = ref("")
const activeTab = ref<HomeTab>("ALL")
const selectedCommunityPost = ref<CommunityPost | null>(null)

const tabs: Array<{ key: HomeTab; label: string }> = [
  { key: "ALL", label: "全部" },
  { key: "IMAGE", label: "图像" },
  { key: "VIDEO", label: "视频" },
  { key: "AUDIO", label: "音频" },
]

const routerActions = [
  { label: "图像生成", modality: "IMAGE", icon: ImageIcon },
  { label: "视频制作", modality: "VIDEO", icon: Video },
  { label: "音乐生成", modality: "AUDIO", icon: Music },
]

const onlineTools = computed(() =>
  [...tools.value]
    .filter((tool) => (tool.status || "").toUpperCase() === "ONLINE")
    .sort((a, b) => a.id - b.id),
)

const toolsByCode = computed(() => new Map(onlineTools.value.map((tool) => [tool.toolCode, tool])))

const recentTools = computed<RecentToolEntry[]>(() => {
  const seen = new Set<string>()
  const sorted = [...tasks.value].sort((a, b) => dateValue(b.createdAt) - dateValue(a.createdAt))
  const result: RecentToolEntry[] = []
  for (const task of sorted) {
    if (!task.toolCode || seen.has(task.toolCode)) continue
    const tool = toolsByCode.value.get(task.toolCode)
    if (!tool) continue
    seen.add(task.toolCode)
    result.push({ tool, task })
    if (result.length >= 5) break
  }
  return result
})

const featuredTools = computed(() => {
  if (activeTab.value === "ALL") return onlineTools.value.slice(0, 12)
  return onlineTools.value.filter((tool) => normalizeModality(tool.outputModality) === activeTab.value).slice(0, 12)
})

const communityWallItems = computed(() =>
  communityPosts.value
    .slice()
    .sort((a, b) => (b.likeCount || 0) - (a.likeCount || 0))
    .filter((post) => Boolean(communityPostMediaUrl(post)))
    .slice(0, 18),
)

const communityWallColumns = computed(() => {
  const items = communityWallItems.value
  const firstColumn = items.filter((_, index) => index % 2 === 0)
  const secondColumn = items.filter((_, index) => index % 2 === 1)
  return [
    repeatWallItems(firstColumn.length ? firstColumn : items),
    repeatWallItems(secondColumn.length ? secondColumn : items),
  ]
})

const selectedCommunityMediaUrl = computed(() =>
  selectedCommunityPost.value ? communityPostMediaUrl(selectedCommunityPost.value) : "",
)

const selectedCommunityTitle = computed(() =>
  selectedCommunityPost.value ? communityPostTitle(selectedCommunityPost.value) : "",
)

const selectedCommunityPrompt = computed(() =>
  selectedCommunityPost.value ? communityPostPrompt(selectedCommunityPost.value) : "",
)

function normalizeModality(value?: string | null) {
  return (value || "TEXT").trim().toUpperCase()
}

function modalityLabel(value?: string | null) {
  const key = normalizeModality(value)
  if (key === "IMAGE") return "图像"
  if (key === "VIDEO") return "视频"
  if (key === "AUDIO") return "音频"
  if (key === "TEXT") return "文本"
  return key
}

function dateValue(value?: string | null) {
  const time = value ? new Date(value).getTime() : 0
  return Number.isFinite(time) ? time : 0
}

function repeatWallItems(items: CommunityPost[]) {
  if (!items.length) return []
  const base: CommunityPost[] = []
  while (base.length < Math.max(4, items.length)) {
    base.push(...items)
  }
  return [...base, ...base]
}

function communityPostMediaUrl(post: CommunityPost) {
  const imageUrl = resolveCommunityImageUrls(post)[0]
  return imageUrl || normalizeCommunityMediaUrl(post.coverUrl || post.mediaUrl)
}

function communityPostKindLabel(post: CommunityPost) {
  const kind = resolveCommunityPostKind(post.modality)
  if (kind === "image") return "图像"
  if (kind === "video") return "视频"
  if (kind === "audio") return "音频"
  return "作品"
}

function communityPostTitle(post: CommunityPost) {
  const kind = resolveCommunityPostKind(post.modality)
  return communityDisplayTitle({
    title: post.title,
    topic: post.topic,
    tags: post.tags,
    toolName: post.toolName,
    toolCode: post.toolCode,
    kind,
    modality: post.modality,
    promptVisible: post.promptVisible,
  })
}

function communityPostPrompt(post: CommunityPost) {
  return resolveCommunityPrompt(post) || post.description?.trim() || ""
}

function toolCover(tool: ToolSummary) {
  return resolveSummaryToolCoverUrl(tool)
}

function usesComparison(tool: ToolSummary): boolean {
  return tool.frontendStyle?.mediaDisplayMode === "comparison"
    && Boolean(tool.frontendStyle?.comparisonOriginalUrl)
    && Boolean(tool.frontendStyle?.comparisonEffectUrl)
}


function toolDescription(tool: ToolSummary) {
  return tool.description?.trim() || tool.frontendStyle?.heroSubtitle?.trim() || "进入工具，使用真实配置开始创作。"
}

function costLabel(tool: ToolSummary) {
  if (tool.variableCreditPricing || tool.estimatedCreditCost == null) return "算力不详"
  if (tool.estimatedCreditCost === 0) return "免费"
  return `约 ${tool.estimatedCreditCost} 算力/次`
}

function modelLabel(tool: ToolSummary) {
  return tool.modelConfigName || tool.modelName || tool.categoryName || modalityLabel(tool.outputModality)
}

function goToAgentWithPrompt(rawPrompt?: string) {
  const prompt = (rawPrompt ?? promptText.value).trim()
  if (!prompt) return

  typingPlaceholder.pause()
  const query = { prompt }
  const destination = router.resolve({ ...userRoutes.agent, query })

  if (!auth.token) {
    void router.push({ name: "Login", query: { redirect: destination.fullPath } })
    return
  }

  void router.push(destination)
}

function launchWorkbench(modality: string) {
  const query: Record<string, string> = { modality }
  const prompt = promptText.value.trim()
  if (prompt) query.prompt = prompt
  void router.push({ path: "/dashboard", query })
}

function onPromptFocus() {
  promptInputFocused.value = true
  typingPlaceholder.pause()
  promptText.value = ""
}

function onPromptBlur() {
  promptInputFocused.value = false
  if (!promptText.value.trim()) {
    typingPlaceholder.resume()
  }
}

function submitHeroPrompt() {
  goToAgentWithPrompt()
}

function quickLaunch(tool: ToolSummary) {
  void router.push({ path: "/dashboard", query: { tool: tool.toolCode } })
}

function openTool(tool: ToolSummary) {
  void router.push(`/tools/${encodeURIComponent(tool.toolCode)}`)
}

function openCommunityPreview(post: CommunityPost) {
  selectedCommunityPost.value = post
}

function closeCommunityPreview() {
  selectedCommunityPost.value = null
}

function quoteCommunityPrompt() {
  if (!selectedCommunityPrompt.value) return
  const prompt = selectedCommunityPrompt.value
  selectedCommunityPost.value = null
  goToAgentWithPrompt(prompt)
}

function openCommunityPost(post: CommunityPost) {
  void router.push(`/community/posts/${post.id}`)
}

async function loadHomeData() {
  loading.value = true
  error.value = ""
  try {
    const [toolPage, taskPage, communityPage] = await Promise.all([
      fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 120 } }),
      fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 80 } }).catch(() => null as PageResult<TaskDetail> | null),
      searchCommunityPosts({
        token: auth.token,
        query: { pageNo: 1, pageSize: 24, sort: "POPULAR" },
      }).catch(() => null as PageResult<CommunityPost> | null),
    ])
    tools.value = toolPage.list
    tasks.value = taskPage?.list || []
    communityPosts.value = communityPage?.list || []
  } catch (err) {
    error.value = err instanceof Error ? err.message : "首页数据加载失败"
    tools.value = []
    tasks.value = []
    communityPosts.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadHomeData)
onActivated(loadHomeData)

watch(
  () => auth.token,
  (token, previous) => {
    if (token !== previous) void loadHomeData()
  },
)
</script>

<template>
    <main class="home-page">
      <section class="home-hero">
        <div class="hero-glow hero-glow--pink" />
        <div class="hero-glow hero-glow--blue" />
        <div class="hero-layout">
          <div class="hero-content">
            <p class="hero-kicker">
              <Sparkles class="h-4 w-4" />
              Smart Router · Launchpad
            </p>
            <h1>思维不停，创作不止</h1>
            <p class="hero-lead">输入想法并回车进入科创点AI 对话，或选择下方创作方向进入对应工作台。</p>

            <div class="router-panel">
              <div class="prompt-shell">
                <Search class="h-5 w-5 shrink-0 text-white/36" />
                <input
                  ref="promptInputRef"
                  v-model="promptText"
                  type="text"
                  :placeholder="promptPlaceholder"
                  @focus="onPromptFocus"
                  @blur="onPromptBlur"
                  @keydown.enter.prevent="submitHeroPrompt"
                />
              </div>
              <div class="router-actions" aria-label="创作分流">
                <button
                  v-for="action in routerActions"
                  :key="action.modality"
                  type="button"
                  class="route-button"
                  @click="launchWorkbench(action.modality)"
                >
                  <component :is="action.icon" class="h-4 w-4" />
                  {{ action.label }}
                </button>
              </div>
            </div>
          </div>

          <aside class="hero-wall" aria-label="社区动态作品墙">
            <template v-if="communityWallItems.length">
              <div class="hero-wall-fade hero-wall-fade--top" />
              <div class="hero-wall-fade hero-wall-fade--bottom" />
              <div
                v-for="(column, columnIndex) in communityWallColumns"
                :key="columnIndex"
                class="hero-wall-column"
                :class="{ 'hero-wall-column--down': columnIndex % 2 === 1 }"
              >
                <button
                  v-for="(post, postIndex) in column"
                  :key="`${post.id}-${postIndex}`"
                  type="button"
                  class="hero-wall-card"
                  @click="openCommunityPreview(post)"
                >
                  <video
                    v-if="isVideoPreviewUrl(communityPostMediaUrl(post))"
                    :src="communityPostMediaUrl(post)"
                    :poster="resolveOssVideoPosterUrl(post.coverUrl)"
                    muted
                    loop
                    autoplay
                    playsinline
                    preload="metadata"
                  />
                  <OptimizedImage
                    v-else
                    :src="communityPostMediaUrl(post)"
                    :alt="communityPostTitle(post)"
                    preset="list"
                  />
                  <span class="hero-wall-badge">{{ communityPostKindLabel(post) }}</span>
                </button>
              </div>
            </template>
            <div v-else class="hero-wall-empty">
              <Sparkles class="h-5 w-5" />
              <span>社区作品载入后将在这里流动展示</span>
            </div>
          </aside>
        </div>
      </section>

      <section v-if="recentTools.length" class="home-section">
        <div class="section-heading">
          <div>
            <p class="section-kicker">Recently Used</p>
            <h2>最近使用过</h2>
          </div>
          <RouterLink to="/dashboard" class="section-link">
            查看工作历史
            <ArrowRight class="h-4 w-4" />
          </RouterLink>
        </div>

        <div class="recent-row">
          <article v-for="entry in recentTools" :key="entry.tool.toolCode" class="recent-card">
            <div class="recent-thumb">
              <video
                v-if="isVideoPreviewUrl(toolCover(entry.tool))"
                :src="toolCover(entry.tool)"
                muted
                loop
                autoplay
                playsinline
                preload="metadata"
              />
              <OptimizedImage v-else-if="toolCover(entry.tool)" :src="toolCover(entry.tool)" :alt="entry.tool.toolName" preset="card" />
              <WandSparkles v-else class="h-5 w-5 text-white/48" />
            </div>
            <div class="min-w-0 flex-1">
              <h3>{{ entry.tool.toolName }}</h3>
              <p>{{ modalityLabel(entry.tool.outputModality) }}</p>
            </div>
            <button type="button" class="quick-button" @click="quickLaunch(entry.tool)">快速启动</button>
          </article>
        </div>
      </section>

      <section class="home-section">
        <div class="section-heading">
          <div>
            <p class="section-kicker">Featured Tools</p>
            <h2>真实工具市集</h2>
          </div>
          <div class="tab-bar" aria-label="工具分类">
            <button
              v-for="tab in tabs"
              :key="tab.key"
              type="button"
              :class="{ active: activeTab === tab.key }"
              @click="activeTab = tab.key"
            >
              {{ tab.label }}
            </button>
          </div>
        </div>

        <div v-if="loading" class="state-card">
          <Loader2 class="h-5 w-5 animate-spin" />
          正在加载真实工具...
        </div>
        <div v-else-if="error" class="state-card state-card--error">{{ error }}</div>
        <div v-else-if="featuredTools.length === 0" class="state-card">当前分类暂无上线工具</div>

        <div v-else class="tool-grid">
          <article v-for="tool in featuredTools" :key="tool.toolCode" class="tool-card" @click="quickLaunch(tool)">
            <div class="tool-cover">
              <ToolComparisonCover
                v-if="usesComparison(tool)"
                :before-src="tool.frontendStyle?.comparisonOriginalUrl || ''"
                :after-src="tool.frontendStyle?.comparisonEffectUrl || ''"
                :alt="tool.toolName"
                image-class="tool-cover-comparison-media"
                effect-class="tool-cover-comparison-effect"
                line-class="tool-cover-comparison-line"
              />
              <template v-else>
                <video
                  v-if="isVideoPreviewUrl(toolCover(tool))"
                  :src="toolCover(tool)"
                  muted
                  loop
                  autoplay
                  playsinline
                  preload="metadata"
                />
                <OptimizedImage v-else-if="toolCover(tool)" :src="toolCover(tool)" :alt="tool.toolName" preset="card" />
                <div v-else class="tool-cover-empty">
                  <WandSparkles class="h-10 w-10 text-white/48" />
                </div>
              </template>
              <span class="modality-badge">{{ modalityLabel(tool.outputModality) }}</span>
              <button
                type="button"
                class="detail-icon"
                aria-label="查看详情"
                title="查看详情"
                @click.stop="openTool(tool)"
              >
                <Info class="h-4 w-4" />
              </button>
              <div class="tool-title-strip">
                <h3>{{ tool.toolName }}</h3>
                <p>{{ modelLabel(tool) }}</p>
              </div>
              <div class="tool-hover-panel">
                <p>{{ toolDescription(tool) }}</p>
                <span>{{ modelLabel(tool) }}</span>
                <button type="button" class="primary-cta" @click.stop="quickLaunch(tool)">
                  开始创作
                  <ExternalLink class="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          </article>
        </div>
      </section>

      <Teleport to="body">
        <div
          v-if="selectedCommunityPost"
          class="community-preview-backdrop"
          role="dialog"
          aria-modal="true"
          @click.self="closeCommunityPreview"
        >
          <section class="community-preview-modal">
            <button type="button" class="community-preview-close" aria-label="关闭" @click="closeCommunityPreview">
              <X class="h-5 w-5" />
            </button>
            <div class="community-preview-media">
              <video
                v-if="isVideoPreviewUrl(selectedCommunityMediaUrl)"
                :src="selectedCommunityMediaUrl"
                controls
                autoplay
                loop
                playsinline
              />
              <img v-else :src="selectedCommunityMediaUrl" :alt="selectedCommunityTitle" />
            </div>
            <div class="community-preview-body">
              <p class="community-preview-kicker">Community Prompt</p>
              <h3>{{ selectedCommunityTitle }}</h3>
              <p class="community-preview-prompt">{{ selectedCommunityPrompt || "该作品暂未公开完整提示词。" }}</p>
              <div class="community-preview-actions">
                <button
                  type="button"
                  class="community-preview-primary"
                  :disabled="!selectedCommunityPrompt"
                  @click="quoteCommunityPrompt"
                >
                  <Copy class="h-4 w-4" />
                  引用到输入框
                </button>
                <button type="button" class="community-preview-secondary" @click="openCommunityPost(selectedCommunityPost)">
                  查看作品
                  <ExternalLink class="h-4 w-4" />
                </button>
              </div>
            </div>
          </section>
        </div>
      </Teleport>
    </main>
</template>

<style scoped>
.home-page {
  min-height: 100%;
  background:
    radial-gradient(circle at 22% 0%, rgb(var(--brand-primary-rgb) / 0.12), transparent 28%),
    radial-gradient(circle at 78% 10%, rgb(var(--brand-secondary-rgb) / 0.14), transparent 30%),
    #08080a;
  padding: clamp(24px, 4vw, 56px);
  color: #fff;
}

.home-hero {
  position: relative;
  overflow: hidden;
  height: auto;
  min-height: 0;
  border: 1px solid rgb(255 255 255 / 0.06);
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgb(255 255 255 / 0.055), rgb(255 255 255 / 0.025)),
    #121216;
  box-shadow:
    0 34px 90px rgb(0 0 0 / 0.38),
    inset 0 1px 0 rgb(255 255 255 / 0.06);
}

.hero-glow {
  position: absolute;
  width: 360px;
  height: 360px;
  border-radius: 999px;
  filter: blur(34px);
  opacity: 0.28;
  pointer-events: none;
}

.hero-glow--pink {
  right: -90px;
  top: -120px;
  background: var(--brand-primary);
}

.hero-glow--blue {
  bottom: -150px;
  left: 18%;
  background: var(--brand-secondary);
}

.hero-layout {
  position: relative;
  z-index: 1;
  display: grid;
  height: auto;
  min-height: 0;
  grid-template-columns: minmax(0, 3fr) minmax(320px, 2fr);
  gap: 18px;
}

.hero-content {
  position: relative;
  z-index: 1;
  max-width: none;
  padding: clamp(34px, 4.6vw, 56px) clamp(34px, 5vw, 64px) 32px;
  align-self: center;
}

.hero-kicker,
.section-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: rgb(255 255 255 / 0.44);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.hero-content h1 {
  margin: 14px 0 0;
  font-size: clamp(36px, 4.2vw, 58px);
  font-weight: 760;
  line-height: 1.04;
  letter-spacing: 0;
}

.hero-lead {
  max-width: 560px;
  margin: 18px 0 0;
  color: rgb(255 255 255 / 0.58);
  font-size: 16px;
  line-height: 1.75;
}

.router-panel {
  max-width: 760px;
  margin-top: 26px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 24px;
  background: rgb(10 10 14 / 0.62);
  padding: 12px;
  box-shadow: 0 20px 70px rgb(0 0 0 / 0.34);
  backdrop-filter: blur(22px);
}

.prompt-shell {
  position: relative;
  display: flex;
  height: 62px;
  align-items: center;
  gap: 12px;
  border-radius: 18px;
  background: rgb(255 255 255 / 0.055);
  padding: 0 18px;
}

.prompt-shell input {
  min-width: 0;
  flex: 1;
  border: 0;
  background: transparent;
  color: #fff;
  font-size: 16px;
  outline: none;
}

.prompt-shell input::placeholder {
  color: rgb(255 255 255 / 0.3);
}

.router-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}

.route-button {
  display: inline-flex;
  height: 42px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.045);
  padding: 0 16px;
  color: rgb(255 255 255 / 0.72);
  font-size: 14px;
  font-weight: 600;
  transition: transform 160ms ease, background-color 160ms ease, border-color 160ms ease, color 160ms ease;
}

.route-button:hover {
  transform: translateY(-1px);
  border-color: var(--brand-border);
  background: var(--brand-gradient-soft);
  color: #fff;
}

.hero-wall {
  position: relative;
  display: grid;
  height: clamp(360px, 28vw, 440px);
  min-height: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  overflow: hidden;
  padding: 20px 22px 20px 0;
  mask-image: linear-gradient(to bottom, transparent, #000 15%, #000 85%, transparent);
  -webkit-mask-image: linear-gradient(to bottom, transparent, #000 15%, #000 85%, transparent);
}

.hero-wall::before {
  position: absolute;
  inset: 0;
  border-left: 1px solid rgb(255 255 255 / 0.055);
  background:
    linear-gradient(90deg, rgb(18 18 22 / 0.08), rgb(18 18 22 / 0.56)),
    radial-gradient(circle at 66% 12%, rgb(var(--brand-primary-rgb) / 0.12), transparent 34%);
  content: "";
  pointer-events: none;
}

.hero-wall-fade {
  position: absolute;
  right: 0;
  left: 0;
  z-index: 6;
  height: 112px;
  pointer-events: none;
}

.hero-wall-fade--top {
  top: 0;
  background: linear-gradient(180deg, #121216 0%, rgb(18 18 22 / 0.86) 34%, rgb(18 18 22 / 0) 100%);
}

.hero-wall-fade--bottom {
  bottom: 0;
  background: linear-gradient(0deg, #121216 0%, rgb(18 18 22 / 0.86) 34%, rgb(18 18 22 / 0) 100%);
}

.hero-wall-column {
  position: relative;
  z-index: 1;
  display: flex;
  height: max-content;
  min-height: max-content;
  flex-direction: column;
  gap: 12px;
  animation: hero-wall-scroll-up 46s linear infinite;
  will-change: transform;
}

.hero-wall-column--down {
  animation-name: hero-wall-scroll-down;
  animation-duration: 52s;
}

.hero-wall-column:hover {
  animation-play-state: paused;
}

.hero-wall-card {
  position: relative;
  min-height: 156px;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.07);
  border-radius: 18px;
  background: rgb(255 255 255 / 0.04);
  box-shadow: 0 18px 42px rgb(0 0 0 / 0.28);
  color: #fff;
  text-align: left;
  transition: border-color 200ms ease, box-shadow 200ms ease, transform 200ms ease;
}

.hero-wall-card:hover {
  z-index: 3;
  transform: scale(1.035);
  border-color: var(--brand-border);
  box-shadow: 0 22px 58px rgb(0 0 0 / 0.48), 0 0 0 1px rgb(255 255 255 / 0.04);
}

.hero-wall-card img,
.hero-wall-card video {
  width: 100%;
  height: 100%;
  min-height: 156px;
  object-fit: cover;
  transition: transform 420ms ease;
}

.hero-wall-card:hover img,
.hero-wall-card:hover video {
  transform: scale(1.06);
}

.hero-wall-badge {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 2;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 999px;
  background: rgb(0 0 0 / 0.4);
  padding: 4px 8px;
  color: rgb(255 255 255 / 0.78);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  backdrop-filter: blur(8px);
}

.hero-wall-empty {
  position: relative;
  z-index: 1;
  display: flex;
  min-height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  grid-column: 1 / -1;
  border: 1px dashed rgb(255 255 255 / 0.08);
  border-radius: 24px;
  background:
    radial-gradient(circle at 42% 34%, rgb(var(--brand-primary-rgb) / 0.2), transparent 30%),
    radial-gradient(circle at 64% 62%, rgb(var(--brand-secondary-rgb) / 0.18), transparent 34%),
    rgb(255 255 255 / 0.025);
  color: rgb(255 255 255 / 0.42);
  font-size: 13px;
}

@keyframes hero-wall-scroll-up {
  from {
    transform: translateY(0);
  }

  to {
    transform: translateY(-50%);
  }
}

@keyframes hero-wall-scroll-down {
  from {
    transform: translateY(-50%);
  }

  to {
    transform: translateY(0);
  }
}

.home-section {
  margin-top: 34px;
}

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.section-heading h2 {
  margin: 6px 0 0;
  font-size: 24px;
  font-weight: 680;
  letter-spacing: 0;
}

.section-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: rgb(255 255 255 / 0.54);
  font-size: 13px;
  font-weight: 600;
  transition: color 160ms ease;
}

.section-link:hover {
  color: #fff;
}

.recent-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}

.recent-card {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  border: 1px solid rgb(255 255 255 / 0.055);
  border-radius: 18px;
  background: #121216;
  padding: 12px;
}

.recent-thumb {
  display: grid;
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  place-items: center;
  overflow: hidden;
  border-radius: 14px;
  background: rgb(255 255 255 / 0.055);
}

.recent-thumb img,
.recent-thumb video,
.tool-cover img,
.tool-cover video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.recent-card h3 {
  overflow: hidden;
  margin: 0;
  color: rgb(255 255 255 / 0.9);
  font-size: 14px;
  font-weight: 640;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-card p {
  margin: 4px 0 0;
  color: rgb(255 255 255 / 0.38);
  font-size: 12px;
}

.quick-button {
  height: 32px;
  flex-shrink: 0;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  padding: 0 12px;
  color: rgb(255 255 255 / 0.68);
  font-size: 12px;
  font-weight: 600;
}

.quick-button:hover {
  background: var(--brand-soft);
  color: #fff;
}

.tab-bar {
  display: inline-flex;
  gap: 4px;
  border: 1px solid rgb(255 255 255 / 0.065);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.035);
  padding: 4px;
}

.tab-bar button {
  height: 34px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  padding: 0 14px;
  color: rgb(255 255 255 / 0.48);
  font-size: 13px;
  font-weight: 600;
}

.tab-bar button.active {
  background: var(--brand-gradient-soft);
  color: #fff;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.06);
}

.tool-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.tool-card {
  position: relative;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.055);
  border-radius: 22px;
  background: #121216;
  box-shadow: 0 24px 60px rgb(0 0 0 / 0.24);
  cursor: pointer;
  transition: transform 180ms ease, border-color 180ms ease, background-color 180ms ease;
}

.tool-card:hover {
  transform: translateY(-3px);
  border-color: var(--brand-border);
  background: #15151b;
  box-shadow: 0 26px 70px rgb(0 0 0 / 0.5);
}

.tool-cover {
  position: relative;
  display: grid;
  aspect-ratio: 1 / 1;
  min-height: 0;
  place-items: center;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 14%, rgb(var(--brand-primary-rgb) / 0.32), transparent 34%),
    radial-gradient(circle at 74% 34%, rgb(var(--brand-secondary-rgb) / 0.28), transparent 36%),
    radial-gradient(circle at 48% 100%, rgb(var(--brand-tertiary-rgb) / 0.16), transparent 42%),
    #0d0d12;
}

.tool-cover::after {
  position: absolute;
  inset: 0;
  z-index: 1;
  background: linear-gradient(180deg, rgb(0 0 0 / 0.06), transparent 36%, rgb(0 0 0 / 0.82));
  content: "";
  pointer-events: none;
}

.tool-cover-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background:
    linear-gradient(135deg, rgb(255 255 255 / 0.06), transparent 42%),
    radial-gradient(circle at 25% 35%, rgb(var(--brand-primary-rgb) / 0.34), transparent 26%),
    radial-gradient(circle at 70% 52%, rgb(var(--brand-secondary-rgb) / 0.34), transparent 30%),
    radial-gradient(circle at 48% 82%, rgb(var(--brand-tertiary-rgb) / 0.18), transparent 32%);
}

.tool-cover-comparison-media {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

@keyframes home-comparison-wipe {
  0% { clip-path: inset(0 100% 0 0); }
  82%,
  100% { clip-path: inset(0 0 0 0); }
}

@keyframes home-comparison-pos {
  0% { left: 0%; }
  82%,
  100% { left: 100%; }
}

.tool-cover-comparison-effect {
  z-index: 1;
  animation: home-comparison-wipe 3s ease-in-out infinite;
}

.tool-cover-comparison-line {
  position: absolute;
  inset-block: 0;
  z-index: 5;
  width: 3px;
  background: white;
  box-shadow: 0 0 8px rgb(0 0 0 / 0.5), 0 0 20px rgb(255 255 255 / 0.3);
  pointer-events: none;
  animation: home-comparison-pos 3s ease-in-out infinite;
}

.modality-badge,
.cost-badge,
.detail-icon {
  position: absolute;
  z-index: 4;
  display: inline-flex;
  align-items: center;
  border: 1px solid rgb(255 255 255 / 0.08);
  box-shadow: 0 10px 28px rgb(0 0 0 / 0.28);
  backdrop-filter: blur(14px);
}

.modality-badge {
  left: 12px;
  top: 12px;
  border-radius: 8px;
  background: rgb(0 0 0 / 0.46);
  padding: 5px 9px;
  color: rgb(255 255 255 / 0.78);
  font-size: 11px;
  font-weight: 650;
}

.cost-badge {
  right: 12px;
  top: 12px;
  gap: 4px;
  border-color: rgb(168 85 247 / 0.28);
  border-radius: 8px;
  background: rgb(168 85 247 / 0.2);
  padding: 5px 9px;
  color: rgb(216 180 254);
  font-size: 11px;
  font-weight: 680;
}

.detail-icon {
  right: 12px;
  top: 48px;
  width: 34px;
  height: 34px;
  justify-content: center;
  border-radius: 12px;
  background: rgb(255 255 255 / 0.08);
  color: rgb(255 255 255 / 0.72);
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 160ms ease, transform 160ms ease, background-color 160ms ease, color 160ms ease;
}

.tool-card:hover .detail-icon {
  opacity: 1;
  transform: translateY(0);
}

.detail-icon:hover {
  background: var(--brand-soft);
  color: #fff;
}

.tool-cover img,
.tool-cover video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1);
  transition: transform 520ms ease;
}

.tool-card:hover .tool-cover img,
.tool-card:hover .tool-cover video {
  transform: scale(1.055);
}

.tool-title-strip {
  position: absolute;
  inset: auto 0 0;
  z-index: 2;
  padding: 18px;
  transition: transform 260ms ease;
}

.tool-card:hover .tool-title-strip {
  transform: translateY(-8px);
}

.tool-title-strip h3 {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: rgb(255 255 255 / 0.92);
  font-size: 16px;
  font-weight: 720;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
}

.tool-title-strip p {
  overflow: hidden;
  margin: 3px 0 0;
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-hover-panel {
  position: absolute;
  inset: auto 0 0;
  z-index: 3;
  display: flex;
  min-height: 52%;
  flex-direction: column;
  justify-content: flex-end;
  border-top: 1px solid rgb(255 255 255 / 0.07);
  background: linear-gradient(180deg, rgb(18 18 22 / 0.62), rgb(18 18 22 / 0.96) 54%, #121216);
  padding: 18px;
  opacity: 0;
  transform: translateY(16px);
  transition: opacity 260ms ease, transform 260ms ease;
  backdrop-filter: blur(18px);
}

.tool-card:hover .tool-hover-panel {
  opacity: 1;
  transform: translateY(0);
}

.tool-hover-panel p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: rgb(255 255 255 / 0.7);
  font-size: 12px;
  line-height: 1.75;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.tool-hover-panel span {
  align-self: flex-start;
  max-width: 100%;
  overflow: hidden;
  margin-top: 10px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 7px;
  background: rgb(255 255 255 / 0.055);
  padding: 4px 7px;
  color: rgb(255 255 255 / 0.42);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.primary-cta {
  display: inline-flex;
  width: 100%;
  height: 40px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 0;
  border-radius: 10px;
  background: var(--brand-gradient);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  margin-top: 14px;
  box-shadow: 0 12px 32px var(--brand-glow);
  transition: filter 160ms ease, transform 160ms ease;
}

.primary-cta:hover {
  filter: brightness(1.06);
  transform: translateY(-1px);
}

.state-card {
  display: flex;
  min-height: 180px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 1px dashed rgb(255 255 255 / 0.08);
  border-radius: 22px;
  background: rgb(255 255 255 / 0.025);
  color: rgb(255 255 255 / 0.48);
  font-size: 14px;
}

.state-card--error {
  color: rgb(254 202 202);
}

.community-preview-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  background: rgb(0 0 0 / 0.68);
  padding: 24px;
  backdrop-filter: blur(18px);
}

.community-preview-modal {
  position: relative;
  display: grid;
  width: min(920px, 100%);
  max-height: min(760px, calc(100vh - 48px));
  grid-template-columns: minmax(0, 1.05fr) minmax(320px, 0.95fr);
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 28px;
  background: #121216;
  box-shadow: 0 36px 120px rgb(0 0 0 / 0.66);
}

.community-preview-close {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 3;
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(0 0 0 / 0.36);
  color: rgb(255 255 255 / 0.74);
  backdrop-filter: blur(16px);
  transition: background-color 160ms ease, color 160ms ease;
}

.community-preview-close:hover {
  background: var(--brand-soft);
  color: #fff;
}

.community-preview-media {
  display: grid;
  min-height: 460px;
  place-items: center;
  overflow: hidden;
  background:
    radial-gradient(circle at 25% 18%, rgb(var(--brand-primary-rgb) / 0.12), transparent 28%),
    radial-gradient(circle at 68% 76%, rgb(var(--brand-secondary-rgb) / 0.14), transparent 32%),
    #09090c;
}

.community-preview-media img,
.community-preview-media video {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.community-preview-body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
  border-left: 1px solid rgb(255 255 255 / 0.06);
  padding: 40px;
}

.community-preview-kicker {
  margin: 0;
  color: var(--brand-active-text);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.community-preview-body h3 {
  margin: 12px 0 0;
  color: rgb(255 255 255 / 0.94);
  font-size: 26px;
  font-weight: 740;
  line-height: 1.35;
}

.community-preview-prompt {
  max-height: 260px;
  overflow: auto;
  margin: 18px 0 0;
  color: rgb(255 255 255 / 0.62);
  font-size: 14px;
  line-height: 1.9;
}

.community-preview-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 26px;
}

.community-preview-primary,
.community-preview-secondary {
  display: inline-flex;
  height: 42px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 999px;
  padding: 0 16px;
  font-size: 13px;
  font-weight: 650;
  transition: filter 160ms ease, transform 160ms ease, background-color 160ms ease, color 160ms ease;
}

.community-preview-primary {
  border: 0;
  background: var(--brand-gradient);
  color: #fff;
  box-shadow: 0 14px 34px var(--brand-glow);
}

.community-preview-primary:disabled {
  cursor: not-allowed;
  filter: grayscale(0.7);
  opacity: 0.45;
}

.community-preview-primary:not(:disabled):hover,
.community-preview-secondary:hover {
  filter: brightness(1.06);
  transform: translateY(-1px);
}

.community-preview-secondary {
  border: 1px solid rgb(255 255 255 / 0.08);
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.68);
}

@media (max-width: 1280px) {
  .recent-row,
  .tool-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .home-page {
    padding: 18px;
  }

  .home-hero {
    height: auto;
    min-height: 0;
  }

  .hero-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .hero-wall {
    min-height: 320px;
    padding: 0 22px 24px;
  }

  .hero-wall::before {
    border-top: 1px solid rgb(255 255 255 / 0.055);
    border-left: 0;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .recent-row,
  .tool-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .community-preview-modal {
    grid-template-columns: minmax(0, 1fr);
    overflow-y: auto;
  }

  .community-preview-media {
    min-height: 340px;
  }

  .community-preview-body {
    border-top: 1px solid rgb(255 255 255 / 0.06);
    border-left: 0;
    padding: 28px;
  }
}

@media (max-width: 620px) {
  .recent-row,
  .tool-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .router-actions {
    flex-direction: column;
  }

  .route-button {
    width: 100%;
  }

  .hero-content {
    padding: 30px 22px;
  }

  .hero-wall {
    grid-template-columns: minmax(0, 1fr);
  }

  .hero-wall-column--down {
    display: none;
  }

  .community-preview-backdrop {
    padding: 12px;
  }

  .community-preview-media {
    min-height: 280px;
  }

  .community-preview-actions {
    flex-direction: column;
  }
}
</style>
