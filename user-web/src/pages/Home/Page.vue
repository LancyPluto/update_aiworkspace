<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from "vue"
import { RouterLink, useRouter } from "vue-router"
import {
  ArrowRight,
  ExternalLink,
  Image as ImageIcon,
  Info,
  Loader2,
  Music,
  Search,
  Sparkles,
  Video,
  WandSparkles,
  Zap,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchTools } from "@/api/toolApi"
import { fetchTasks } from "@/api/taskApi"
import type { PageResult, TaskDetail, ToolSummary } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { getApiOrigin } from "@/api/client"

type HomeTab = "ALL" | "IMAGE" | "VIDEO" | "AUDIO"

type RecentToolEntry = {
  tool: ToolSummary
  task: TaskDetail
}

const auth = useAuthStore()
const router = useRouter()

const promptText = ref("")
const tools = ref<ToolSummary[]>([])
const tasks = ref<TaskDetail[]>([])
const loading = ref(false)
const error = ref("")
const activeTab = ref<HomeTab>("ALL")

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

function normalizeMediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (/^(https?:)?\/\//i.test(raw) || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function isVideoUrl(value?: string | null) {
  return /\.(mp4|webm|mov|m4v)(?:[?#].*)?$/i.test(value?.trim() || "")
}

function toolCover(tool: ToolSummary) {
  return normalizeMediaUrl(
    tool.frontendStyle?.comparisonEffectUrl ||
      tool.frontendStyle?.demoThumbnails?.[0] ||
      tool.coverUrl,
  )
}

function toolDescription(tool: ToolSummary) {
  return tool.description?.trim() || tool.frontendStyle?.heroSubtitle?.trim() || "进入工具，使用真实配置开始创作。"
}

function costLabel(tool: ToolSummary) {
  if (tool.estimatedCreditCost === 0) return "免费"
  return `约 ${tool.estimatedCreditCost} 算力/次`
}

function modelLabel(tool: ToolSummary) {
  return tool.modelConfigName || tool.modelName || tool.categoryName || modalityLabel(tool.outputModality)
}

function launchRouter(modality: string) {
  const query: Record<string, string> = { modality }
  const prompt = promptText.value.trim()
  if (prompt) query.prompt = prompt
  void router.push({ path: "/dashboard", query })
}

function quickLaunch(tool: ToolSummary) {
  void router.push({ path: "/dashboard", query: { tool: tool.toolCode } })
}

function openTool(tool: ToolSummary) {
  void router.push(`/tools/${encodeURIComponent(tool.toolCode)}`)
}

async function loadHomeData() {
  loading.value = true
  error.value = ""
  try {
    const [toolPage, taskPage] = await Promise.all([
      fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 120 } }),
      fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 80 } }).catch(() => null as PageResult<TaskDetail> | null),
    ])
    tools.value = toolPage.list
    tasks.value = taskPage?.list || []
  } catch (err) {
    error.value = err instanceof Error ? err.message : "首页数据加载失败"
    tools.value = []
    tasks.value = []
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
  <AppShell title="首页" description="科创点AI 创作启动台">
    <main class="home-page">
      <section class="home-hero">
        <div class="hero-glow hero-glow--pink" />
        <div class="hero-glow hero-glow--blue" />
        <div class="hero-content">
          <p class="hero-kicker">
            <Sparkles class="h-4 w-4" />
            Smart Router · Launchpad
          </p>
          <h1>思维不停，创作不止</h1>
          <p class="hero-lead">输入一个想法，选择创作方向，科创点AI 会把你带到对应工作台继续完成专业配置。</p>

          <div class="router-panel">
            <div class="prompt-shell">
              <Search class="h-5 w-5 text-white/36" />
              <input
                v-model="promptText"
                type="text"
                placeholder="输入灵感，即刻创作！"
                @keydown.enter.prevent="launchRouter('IMAGE')"
              />
            </div>
            <div class="router-actions" aria-label="创作分流">
              <button
                v-for="action in routerActions"
                :key="action.modality"
                type="button"
                class="route-button"
                @click="launchRouter(action.modality)"
              >
                <component :is="action.icon" class="h-4 w-4" />
                {{ action.label }}
              </button>
            </div>
          </div>
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
                v-if="isVideoUrl(toolCover(entry.tool))"
                :src="toolCover(entry.tool)"
                muted
                loop
                playsinline
                preload="metadata"
              />
              <img v-else-if="toolCover(entry.tool)" :src="toolCover(entry.tool)" :alt="entry.tool.toolName" loading="lazy" />
              <WandSparkles v-else class="h-5 w-5 text-white/48" />
            </div>
            <div class="min-w-0 flex-1">
              <h3>{{ entry.tool.toolName }}</h3>
              <p>{{ modalityLabel(entry.tool.outputModality) }} · {{ costLabel(entry.tool) }}</p>
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
              <video
                v-if="isVideoUrl(toolCover(tool))"
                :src="toolCover(tool)"
                muted
                loop
                playsinline
                preload="metadata"
              />
              <img v-else-if="toolCover(tool)" :src="toolCover(tool)" :alt="tool.toolName" loading="lazy" />
              <div v-else class="tool-cover-empty">
                <WandSparkles class="h-10 w-10 text-white/48" />
              </div>
              <span class="modality-badge">{{ modalityLabel(tool.outputModality) }}</span>
              <span class="cost-badge"><Zap class="h-3.5 w-3.5" />{{ costLabel(tool) }}</span>
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
    </main>
  </AppShell>
</template>

<style scoped>
.home-page {
  min-height: 100%;
  background:
    radial-gradient(circle at 22% 0%, rgb(255 63 121 / 0.12), transparent 28%),
    radial-gradient(circle at 78% 10%, rgb(124 92 255 / 0.14), transparent 30%),
    #08080a;
  padding: clamp(24px, 4vw, 56px);
  color: #fff;
}

.home-hero {
  position: relative;
  overflow: hidden;
  min-height: 420px;
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
  background: #ff3f79;
}

.hero-glow--blue {
  bottom: -150px;
  left: 18%;
  background: #7c5cff;
}

.hero-content {
  position: relative;
  z-index: 1;
  max-width: 920px;
  padding: clamp(36px, 6vw, 74px);
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
  margin: 18px 0 0;
  font-size: clamp(48px, 6.2vw, 88px);
  font-weight: 760;
  line-height: 0.96;
  letter-spacing: 0;
}

.hero-lead {
  max-width: 560px;
  margin: 24px 0 0;
  color: rgb(255 255 255 / 0.58);
  font-size: 16px;
  line-height: 1.9;
}

.router-panel {
  max-width: 760px;
  margin-top: 34px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 24px;
  background: rgb(10 10 14 / 0.62);
  padding: 12px;
  box-shadow: 0 20px 70px rgb(0 0 0 / 0.34);
  backdrop-filter: blur(22px);
}

.prompt-shell {
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
  color: rgb(255 255 255 / 0.34);
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
  border-color: rgb(255 63 121 / 0.34);
  background: linear-gradient(135deg, rgb(255 63 121 / 0.2), rgb(124 92 255 / 0.18));
  color: #fff;
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
  background: rgb(255 63 121 / 0.18);
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
  background: linear-gradient(135deg, rgb(255 63 121 / 0.22), rgb(124 92 255 / 0.2));
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
  border-color: rgb(168 85 247 / 0.34);
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
    radial-gradient(circle at 18% 14%, rgb(255 63 121 / 0.32), transparent 34%),
    radial-gradient(circle at 74% 34%, rgb(124 92 255 / 0.28), transparent 36%),
    radial-gradient(circle at 48% 100%, rgb(18 215 178 / 0.16), transparent 42%),
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
    radial-gradient(circle at 25% 35%, rgb(255 63 121 / 0.34), transparent 26%),
    radial-gradient(circle at 70% 52%, rgb(124 92 255 / 0.34), transparent 30%),
    radial-gradient(circle at 48% 82%, rgb(24 198 174 / 0.18), transparent 32%);
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
  background: rgb(255 63 121 / 0.2);
  color: #fff;
}

.tool-cover img,
.tool-cover video {
  position: absolute;
  inset: 0;
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
  background: linear-gradient(135deg, #ff3f79, #8f5cff);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  margin-top: 14px;
  box-shadow: 0 12px 32px rgb(255 63 121 / 0.18);
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

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .recent-row,
  .tool-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
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
}
</style>
