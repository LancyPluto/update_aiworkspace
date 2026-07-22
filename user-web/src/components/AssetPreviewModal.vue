<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import {
  CalendarDays,
  Check,
  Copy,
  Download,
  FileText,
  Flag,
  Globe2,
  Image as ImageIcon,
  Music,
  Sparkles,
  Video,
  WandSparkles,
  X,
} from "lucide-vue-next"
import { getApiOrigin } from "@/api/client"
import { reportCommunityPost } from "@/api/communityApi"
import CommunityPublishModal from "@/components/CommunityPublishModal.vue"
import CommunityReportModal from "@/components/CommunityReportModal.vue"
import { useAuthStore } from "@/store/authStore"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import type { CommunityPublishPayload } from "@/utils/publishCommunityAsset"
import { cleanToolDisplayText } from "@/utils/toolDisplayText"

const props = defineProps<{
  asset: AssetPreviewItem | null
  recommendations?: AssetPreviewRecommendation[]
}>()

const emit = defineEmits<{
  close: []
  "use-tool": [tool: AssetPreviewRecommendation, asset: AssetPreviewItem]
  "open-task": [asset: AssetPreviewItem]
  publish: [asset: AssetPreviewItem, payload?: CommunityPublishPayload]
  "unpublish": [asset: AssetPreviewItem]
}>()

const copyHint = ref("")
const selectedUrl = ref("")
const publishModalOpen = ref(false)
const reportModalOpen = ref(false)
const reportSubmitting = ref(false)
const reportHint = ref("")
const modalRoot = ref<HTMLElement | null>(null)
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
let previouslyFocusedElement: HTMLElement | null = null
let previousBodyOverflow: string | null = null

const promptText = computed(() => props.asset?.prompt || props.asset?.rawText || "")

const mediaUrls = computed(() => {
  const urls = props.asset?.urls?.length ? props.asset.urls : props.asset?.url ? [props.asset.url] : []
  const seen = new Set<string>()
  return urls
    .map((url) => normalizeMediaUrl(url))
    .filter((url) => {
      if (!url || seen.has(url)) return false
      seen.add(url)
      return true
    })
})

const mediaUrl = computed(() => selectedUrl.value || normalizeMediaUrl(props.asset?.url) || mediaUrls.value[0] || "")
const activeAsset = computed<AssetPreviewItem | null>(() => (props.asset ? { ...props.asset, url: mediaUrl.value } : null))

const downloadUrl = computed(() => {
  // 优先使用后端返回的downloadUrl（OSS签名URL或CDN签名URL）
  if (props.asset?.downloadUrl) return props.asset.downloadUrl
  return mediaUrl.value
})

const canDownload = computed(() => Boolean(downloadUrl.value))

const canExportEffectAsset = computed(() => props.asset?.kind === "audio" && Boolean(mediaUrl.value))

const canReportCommunity = computed(() => {
  const asset = props.asset
  if (!asset || asset.source !== "community" || !asset.communityPostId) return false
  if (!auth.user?.id) return true
  return asset.authorUserId !== auth.user.id
})

function formatTime(value?: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  })
}

function kindLabel(kind?: string) {
  if (kind === "image") return "图片资产"
  if (kind === "video") return "视频资产"
  if (kind === "audio") return "音频资产"
  if (kind === "text") return "文本资产"
  return "生成资产"
}

function recommendationHint(kind?: string) {
  if (kind === "image") return "支持图片输入的模型"
  if (kind === "video") return "支持视频输入的模型"
  if (kind === "audio") return "支持音频输入的模型"
  return "适合继续创作的模型"
}

function toolDescription(tool: AssetPreviewRecommendation) {
  return (
    cleanToolDisplayText(tool.description) ||
    cleanToolDisplayText(tool.modelDisplayName) ||
    "继续创作"
  )
}

function coverUrl(tool: AssetPreviewRecommendation) {
  return normalizeMediaUrl(tool.coverUrl)
}

function isVideoUrl(value?: string | null) {
  const raw = value?.split(/[?#]/)[0]?.toLowerCase() || ""
  return [".mp4", ".webm", ".mov", ".m4v"].some((ext) => raw.endsWith(ext))
}

function normalizeMediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function openPublishModal() {
  if (!activeAsset.value) return
  publishModalOpen.value = true
}

function closePublishModal() {
  publishModalOpen.value = false
}

function confirmPublish(payload: CommunityPublishPayload) {
  if (!activeAsset.value) return
  emit("publish", activeAsset.value, payload)
  publishModalOpen.value = false
}

function openReportModal() {
  if (!canReportCommunity.value || !props.asset?.communityPostId) return
  if (!auth.token) {
    emit("close")
    void router.push({ name: "Login", query: { redirect: route.fullPath } })
    return
  }
  reportHint.value = ""
  reportModalOpen.value = true
}

async function submitReport(payload: { reason?: string }) {
  if (!props.asset?.communityPostId || !auth.token || reportSubmitting.value) return
  reportSubmitting.value = true
  reportHint.value = ""
  try {
    await reportCommunityPost(props.asset.communityPostId, payload, { token: auth.token })
    reportModalOpen.value = false
    reportHint.value = "举报已提交，感谢你的反馈"
  } catch (err) {
    reportHint.value = err instanceof Error ? err.message : "举报提交失败"
  } finally {
    reportSubmitting.value = false
  }
}

watch(
  () => [props.asset?.id, props.asset?.url, props.asset?.urls?.join("|")],
  () => {
    copyHint.value = ""
    selectedUrl.value = normalizeMediaUrl(props.asset?.url) || mediaUrls.value[0] || ""
  },
  { immediate: true },
)

watch(
  () => Boolean(props.asset),
  async (open) => {
    if (!open) {
      releaseDialogFocus()
      return
    }

    if (previousBodyOverflow === null) {
      previouslyFocusedElement = document.activeElement instanceof HTMLElement ? document.activeElement : null
      previousBodyOverflow = document.body.style.overflow
      document.body.style.overflow = "hidden"
    }

    await nextTick()
    modalRoot.value?.focus({ preventScroll: true })
  },
  { immediate: true },
)

onBeforeUnmount(releaseDialogFocus)

function trapDialogFocus(event: KeyboardEvent) {
  if (publishModalOpen.value || reportModalOpen.value) return

  const root = modalRoot.value
  if (!root) return

  const focusableElements = Array.from(
    root.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  ).filter((element) => element.getClientRects().length > 0 && element.getAttribute("aria-hidden") !== "true")

  if (!focusableElements.length) {
    event.preventDefault()
    root.focus({ preventScroll: true })
    return
  }

  const first = focusableElements[0]
  const last = focusableElements[focusableElements.length - 1]
  const activeElement = document.activeElement

  if (event.shiftKey && (activeElement === first || activeElement === root)) {
    event.preventDefault()
    last.focus({ preventScroll: true })
  } else if (!event.shiftKey && activeElement === last) {
    event.preventDefault()
    first.focus({ preventScroll: true })
  }
}

function handleDialogEscape() {
  if (publishModalOpen.value || reportModalOpen.value) return
  emit("close")
}

function releaseDialogFocus() {
  if (previousBodyOverflow === null) return

  document.body.style.overflow = previousBodyOverflow
  previousBodyOverflow = null

  const focusTarget = previouslyFocusedElement
  previouslyFocusedElement = null
  if (focusTarget?.isConnected) {
    void nextTick(() => focusTarget.focus({ preventScroll: true }))
  }
}

async function copyPrompt() {
  const text = promptText.value.trim()
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copyHint.value = "已复制"
    window.setTimeout(() => {
      copyHint.value = ""
    }, 1800)
  } catch {
    copyHint.value = "复制失败"
    window.setTimeout(() => {
      copyHint.value = ""
    }, 1800)
  }
}

function exportEffectAsset() {
  const asset = activeAsset.value
  if (!asset || asset.kind !== "audio" || !asset.url) return
  const payload = {
    format: "ai-tool-market-effect-asset",
    version: 1,
    exportedAt: new Date().toISOString(),
    title: asset.title,
    coverUrl: asset.coverUrl || "",
    audioUrl: asset.url,
    media: {
      coverUrl: asset.coverUrl || "",
      audioUrl: asset.url,
    },
    suggestedFrontendStyle: {
      mediaDisplayMode: "effect",
      audioPreviewUrl: asset.url,
      demoThumbnails: asset.coverUrl ? [asset.coverUrl] : [],
    },
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json;charset=utf-8" })
  downloadBlob(blob, `${sanitizeDownloadName(asset.title)}-effect-asset.json`)
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement("a")
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}



function fetchDownload() {
  if (!downloadUrl.value) return
  const a = document.createElement("a")
  a.href = downloadUrl.value
  a.download = sanitizeDownloadName(activeAsset.value?.title || "download")
  document.body.appendChild(a)
  a.click()
  a.remove()
}

function sanitizeDownloadName(value: string) {
  return value.trim().replace(/[\\/:*?"<>|]+/g, "-").slice(0, 80) || "audio"
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="asset"
      ref="modalRoot"
      class="fixed inset-0 z-[120] overflow-x-hidden overflow-y-auto bg-black/96 text-white xl:overflow-hidden"
      role="dialog"
      aria-modal="true"
      :aria-label="asset.title"
      tabindex="-1"
      @keydown.esc.stop="handleDialogEscape"
      @keydown.tab="trapDialogFocus"
    >
      <div class="absolute inset-0 opacity-45">
        <div class="absolute left-[10%] top-[10%] h-72 w-72 rounded-full bg-fuchsia-500/12 blur-[120px]" />
        <div class="absolute bottom-[14%] left-[42%] h-80 w-80 rounded-full bg-cyan-400/10 blur-[140px]" />
        <div class="absolute right-[12%] top-[22%] h-96 w-96 rounded-full bg-violet-500/12 blur-[150px]" />
      </div>

      <div class="fixed right-4 top-4 z-20 flex items-center gap-2 sm:right-6 sm:top-6">
        <button
          v-if="canReportCommunity"
          type="button"
          class="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/[0.06] px-3 py-2 text-xs font-semibold text-white/55 backdrop-blur-xl transition hover:border-red-400/30 hover:bg-red-500/10 hover:text-red-100"
          aria-label="举报作品"
          @click="openReportModal"
        >
          <Flag class="h-3.5 w-3.5" />
          举报
        </button>
        <button
          type="button"
          class="grid h-11 w-11 place-items-center rounded-full border border-white/10 bg-white/[0.06] text-white/70 backdrop-blur-xl transition hover:bg-white/12 hover:text-white"
          aria-label="关闭资产预览"
          @click="emit('close')"
        >
          <X class="h-5 w-5" />
        </button>
      </div>

      <CommunityReportModal
        :open="reportModalOpen"
        :submitting="reportSubmitting"
        @close="reportModalOpen = false"
        @confirm="submitReport"
      />

      <div class="relative z-10 min-h-full xl:grid xl:h-full xl:grid-cols-[minmax(0,1fr)_clamp(340px,27vw,420px)] xl:grid-rows-[minmax(0,1fr)]">
        <main class="flex min-w-0 flex-col px-4 pb-8 pt-20 sm:px-8 sm:pt-8 xl:min-h-0 xl:overflow-y-auto xl:px-10 xl:py-8 2xl:px-12">
          <div class="mb-6 max-w-5xl pr-16 sm:pr-20 xl:pr-12">
            <p class="mb-3 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/[0.05] px-3 py-1 text-xs uppercase tracking-[0.24em] text-white/45 backdrop-blur-xl">
              <Sparkles class="h-3.5 w-3.5 text-primary" />
              {{ kindLabel(asset.kind) }}
            </p>
            <h2 class="max-w-4xl text-3xl font-black leading-tight tracking-normal text-white/95 sm:text-4xl">
              {{ asset.title }}
            </h2>
            <p v-if="asset.subtitle" class="mt-4 max-w-2xl text-sm leading-6 text-white/45">
              {{ asset.subtitle }}
            </p>
            <p v-if="reportHint" class="mt-3 text-sm text-white/55">{{ reportHint }}</p>
          </div>

          <section class="grid min-w-0 grid-cols-1 gap-5 xl:min-h-[360px] xl:flex-1 xl:grid-cols-[minmax(0,1fr)_minmax(220px,0.32fr)]">
            <div class="flex min-w-0 flex-col xl:min-h-0">
              <div
                data-testid="asset-preview-stage"
                class="relative flex h-[clamp(280px,58svh,680px)] min-h-0 min-w-0 items-center justify-center overflow-hidden p-2 sm:p-4 xl:h-auto xl:flex-1"
              >
                <img
                  v-if="asset.kind === 'image' && mediaUrl"
                  :src="mediaUrl"
                  :alt="asset.title"
                  class="relative z-10 block h-auto w-auto max-h-full max-w-full rounded-xl object-contain shadow-[0_24px_90px_rgb(0_0_0_/_0.62)] sm:rounded-2xl"
                />
                <video
                  v-else-if="asset.kind === 'video' && mediaUrl"
                  :src="mediaUrl"
                  controls
                  playsinline
                  preload="metadata"
                  class="relative z-10 block h-auto w-auto max-h-full max-w-full rounded-xl bg-black object-contain shadow-[0_24px_90px_rgb(0_0_0_/_0.62)] sm:rounded-2xl"
                />
                <div v-else-if="asset.kind === 'audio' && mediaUrl" class="relative z-10 max-h-full w-full max-w-xl overflow-y-auto rounded-[28px] border border-white/10 bg-black/35 p-4 sm:p-6 xl:p-8">
                  <img
                    v-if="asset.coverUrl"
                    :src="normalizeMediaUrl(asset.coverUrl)"
                    :alt="asset.title"
                    class="mb-6 aspect-[4/3] w-full rounded-2xl object-cover shadow-[0_20px_70px_rgb(0_0_0_/_0.42)]"
                  />
                  <div class="mb-8 flex items-center gap-4">
                    <div class="grid h-16 w-16 place-items-center rounded-3xl bg-primary/15 text-primary shadow-[0_0_40px_rgb(176_92_255_/_0.18)]">
                      <Music class="h-8 w-8" />
                    </div>
                    <div>
                      <p class="text-2xl font-black">{{ asset.title }}</p>
                      <p class="mt-1 text-sm text-white/45">Audio material</p>
                    </div>
                  </div>
                  <audio :src="mediaUrl" controls preload="metadata" class="w-full" />
                </div>
                <article v-else class="relative z-10 max-h-full w-full max-w-3xl overflow-auto rounded-[28px] border border-white/10 bg-black/30 p-5 sm:p-8">
                  <FileText class="mb-8 h-10 w-10 text-white/35" />
                  <p class="whitespace-pre-wrap text-lg font-light leading-9 text-white/78">
                    {{ asset.rawText || asset.prompt || "暂无可预览内容" }}
                  </p>
                </article>
              </div>

              <div
                v-if="asset.kind === 'image' && mediaUrls.length > 1"
                data-testid="asset-preview-thumbnails"
                class="mt-4 w-full max-w-full overflow-x-auto pb-2"
              >
                <div class="flex w-max min-w-full justify-center gap-3">
                  <button
                    v-for="(url, index) in mediaUrls"
                    :key="url"
                    type="button"
                    :aria-label="`查看第 ${index + 1} 张图片`"
                    :aria-pressed="url === mediaUrl"
                    class="relative h-20 w-20 shrink-0 overflow-hidden rounded-2xl border transition"
                    :class="url === mediaUrl ? 'border-primary shadow-[0_0_0_2px_rgb(176_92_255_/_0.22)]' : 'border-white/10 opacity-70 hover:opacity-100'"
                    @click="selectedUrl = url"
                  >
                    <img :src="url" :alt="`${asset.title}-${index + 1}`" class="h-full w-full object-cover" />
                    <span class="absolute bottom-1 right-1 rounded-full bg-black/65 px-1.5 py-0.5 text-[10px] text-white">
                      {{ index + 1 }}
                    </span>
                  </button>
                </div>
              </div>
            </div>

            <aside
              data-testid="asset-preview-metadata"
              class="grid content-start grid-cols-1 border-y border-white/10 sm:grid-cols-3 xl:min-h-0 xl:grid-cols-1 xl:overflow-y-auto xl:border-y-0 xl:pr-1"
            >
              <div class="py-4 sm:pr-5 xl:pr-0">
                <p class="text-[11px] font-semibold uppercase tracking-[0.22em] text-white/30">提示词</p>
                <div class="mt-3">
                  <p class="line-clamp-7 text-sm font-light leading-7 text-white/76">
                    {{ promptText || "暂无提示词记录" }}
                  </p>
                  <button
                    v-if="promptText"
                    type="button"
                    class="mt-3 inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/[0.06] px-3 py-1.5 text-xs font-semibold text-white/72 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.06)] transition hover:border-primary/35 hover:bg-primary/12 hover:text-white"
                    @click="copyPrompt"
                  >
                    <Check v-if="copyHint" class="h-3.5 w-3.5 text-sky-300" />
                    <Copy v-else class="h-3.5 w-3.5" />
                    {{ copyHint || "复制" }}
                  </button>
                </div>
              </div>
              <div class="border-t border-white/10 py-4 sm:border-l sm:border-t-0 sm:px-5 xl:border-l-0 xl:border-t xl:px-0">
                <p class="text-[11px] font-semibold uppercase tracking-[0.22em] text-white/30">来源</p>
                <p class="mt-3 text-lg font-black text-white/90">{{ asset.toolName || "AI 创作" }}</p>
                <p class="mt-2 truncate text-xs text-white/40">{{ asset.taskNo || asset.toolCode }}</p>
              </div>
              <div class="border-t border-white/10 py-4 sm:border-l sm:border-t-0 sm:pl-5 xl:border-l-0 xl:border-t xl:pl-0">
                <p class="text-[11px] font-semibold uppercase tracking-[0.22em] text-white/30">创建时间</p>
                <p class="mt-3 inline-flex items-center gap-2 text-sm text-white/70">
                  <CalendarDays class="h-4 w-4 text-white/35" />
                  {{ formatTime(asset.createdAt) || "未知" }}
                </p>
                <button
                  v-if="canDownload"
                  type="button"
                  @click="fetchDownload"
                  class="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-white/10 bg-[linear-gradient(135deg,rgb(255_255_255_/_0.08),rgb(176_92_255_/_0.12))] px-4 py-2.5 text-sm font-semibold text-white/82 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.08)] transition hover:border-primary/40 hover:text-white"
                >
                  <Download class="h-4 w-4" />
                  下载作品
                </button>
                <button
                  v-if="canExportEffectAsset"
                  type="button"
                  class="mt-3 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-white/10 bg-white/[0.06] px-4 py-2.5 text-sm font-semibold text-white/82 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.08)] transition hover:border-primary/40 hover:text-white"
                  @click="exportEffectAsset"
                >
                  <Download class="h-4 w-4" />
                  导出展示素材
                </button>
              </div>
            </aside>
          </section>
        </main>

        <aside class="min-w-0 border-t border-white/8 bg-[#0d0e13]/88 px-4 py-6 backdrop-blur-2xl sm:px-8 xl:min-h-0 xl:overflow-y-auto xl:border-l xl:border-t-0 xl:px-6 xl:py-8">
          <div v-if="asset.taskId" class="mb-5 rounded-[24px] border border-white/8 bg-white/[0.035] p-4">
            <p class="text-xs font-semibold text-white/35">公开主页</p>
            <p class="mt-2 text-sm leading-6 text-white/52">
              {{ asset.communityPostId ? "这个作品已经展示在你的公开主页中。" : "发布后会展示在你的公开个人主页，可随时撤回。" }}
            </p>
            <div class="mt-4 flex gap-2">
              <button
                v-if="!asset.communityPostId"
                type="button"
                class="inline-flex h-10 flex-1 items-center justify-center gap-2 rounded-full border border-primary/35 bg-primary/18 px-4 text-sm font-semibold text-white transition hover:bg-primary/25"
                @click="openPublishModal"
              >
                <Globe2 class="h-4 w-4" />
                发布到主页
              </button>
              <button
                v-else
                type="button"
                class="inline-flex h-10 flex-1 items-center justify-center gap-2 rounded-full border border-white/10 bg-white/[0.06] px-4 text-sm font-semibold text-white/72 transition hover:bg-white/10"
                @click="activeAsset && emit('unpublish', activeAsset)"
              >
                撤回公开
              </button>
            </div>
          </div>

          <div class="rounded-[26px] border border-white/8 bg-white/[0.035] p-5 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.035)]">
            <p class="text-xs font-semibold text-white/35">推荐</p>
            <h3 class="mt-2 text-2xl font-black">{{ recommendationHint(asset.kind) }}</h3>
            <p class="mt-2 text-sm leading-6 text-white/42">
              用当前资产继续改图、转视频、做风格迁移或二次创作。
            </p>
          </div>

          <div class="mt-5 grid gap-3 pr-1">
            <button
              v-for="tool in recommendations?.slice(0, 6)"
              :key="tool.toolCode"
              type="button"
              class="group grid grid-cols-[72px_minmax(0,1fr)] gap-4 rounded-[24px] border border-white/8 bg-white/[0.04] p-3 text-left transition hover:-translate-y-0.5 hover:border-primary/45 hover:bg-white/[0.07]"
              @click="activeAsset && emit('use-tool', tool, activeAsset)"
            >
              <div class="relative h-20 overflow-hidden rounded-[18px] bg-white/[0.06]">
                <video
                  v-if="isVideoUrl(coverUrl(tool))"
                  :src="coverUrl(tool)"
                  muted
                  loop
                  autoplay
                  playsinline
                  preload="metadata"
                  class="h-full w-full object-cover"
                />
                <img
                  v-else-if="coverUrl(tool)"
                  :src="coverUrl(tool)"
                  :alt="tool.toolName"
                  class="h-full w-full object-cover"
                />
                <div v-else class="grid h-full place-items-center">
                  <WandSparkles class="h-7 w-7 text-primary" />
                </div>
              </div>
              <div class="min-w-0 self-center">
                <p class="line-clamp-2 text-sm font-black text-white">{{ tool.toolName }}</p>
                <p class="mt-1 line-clamp-2 text-xs leading-5 text-white/42">
                  {{ toolDescription(tool) }}
                </p>
              </div>
            </button>

            <div
              v-if="!recommendations?.length"
              class="rounded-[24px] border border-dashed border-white/10 p-8 text-center text-sm text-white/40"
            >
              暂无匹配模型
            </div>
          </div>

          <div class="mt-6 flex flex-wrap gap-3">
            <button class="inline-flex h-11 flex-1 items-center justify-center gap-2 rounded-full border border-white/10 bg-[linear-gradient(135deg,rgb(255_255_255_/_0.10),rgb(176_92_255_/_0.13))] px-5 text-sm font-semibold text-white/78 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.08)] transition hover:border-primary/45 hover:text-white">
              <ImageIcon class="h-4 w-4" />
              改图
            </button>
            <button class="inline-flex h-11 flex-1 items-center justify-center gap-2 rounded-full border border-white/10 bg-[linear-gradient(135deg,rgb(255_255_255_/_0.10),rgb(34_211_238_/_0.10))] px-5 text-sm font-semibold text-white/78 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.08)] transition hover:border-cyan-300/35 hover:text-white">
              <Video class="h-4 w-4" />
              视频
            </button>
          </div>
        </aside>
      </div>
    </div>
  </Teleport>

  <CommunityPublishModal
    :open="publishModalOpen"
    :asset="activeAsset"
    @close="closePublishModal"
    @confirm="confirmPublish"
  />
</template>
