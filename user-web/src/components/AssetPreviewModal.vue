<script setup lang="ts">
import { computed, ref, watch } from "vue"
import {
  CalendarDays,
  Check,
  Copy,
  Download,
  FileText,
  Globe2,
  Image as ImageIcon,
  Music,
  Sparkles,
  Video,
  WandSparkles,
  X,
  Zap,
} from "lucide-vue-next"
import { getApiOrigin } from "@/api/client"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import { cleanToolDisplayText } from "@/utils/toolDisplayText"

const props = defineProps<{
  asset: AssetPreviewItem | null
  recommendations?: AssetPreviewRecommendation[]
}>()

const emit = defineEmits<{
  close: []
  "use-tool": [tool: AssetPreviewRecommendation, asset: AssetPreviewItem]
  "open-task": [asset: AssetPreviewItem]
  "publish": [asset: AssetPreviewItem]
  "unpublish": [asset: AssetPreviewItem]
}>()

const copyHint = ref("")

const promptText = computed(() => props.asset?.prompt || props.asset?.rawText || "")

const downloadUrl = computed(() => normalizeMediaUrl(props.asset?.url))

const canDownload = computed(() => Boolean(downloadUrl.value))

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
    cleanToolDisplayText(tool.configNote) ||
    cleanToolDisplayText(tool.modelConfigName) ||
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

watch(
  () => props.asset?.id,
  () => {
    copyHint.value = ""
  },
)

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
</script>

<template>
  <Teleport to="body">
    <div
      v-if="asset"
      class="fixed inset-0 z-[120] overflow-hidden bg-black/96 text-white backdrop-blur-2xl"
      @keydown.esc="emit('close')"
    >
      <div class="absolute inset-0 opacity-45">
        <div class="absolute left-[10%] top-[10%] h-72 w-72 rounded-full bg-fuchsia-500/12 blur-[120px]" />
        <div class="absolute bottom-[14%] left-[42%] h-80 w-80 rounded-full bg-cyan-400/10 blur-[140px]" />
        <div class="absolute right-[12%] top-[22%] h-96 w-96 rounded-full bg-violet-500/12 blur-[150px]" />
      </div>

      <button
        type="button"
        class="absolute right-6 top-6 z-20 grid h-11 w-11 place-items-center rounded-full border border-white/10 bg-white/[0.06] text-white/70 backdrop-blur-xl transition hover:bg-white/12 hover:text-white"
        aria-label="关闭资产预览"
        @click="emit('close')"
      >
        <X class="h-5 w-5" />
      </button>

      <div class="relative z-10 grid h-full grid-cols-[minmax(0,1fr)_420px] gap-0 max-xl:grid-cols-1">
        <main class="flex min-h-0 flex-col px-8 py-8 max-xl:pb-0 sm:px-12">
          <div class="mb-7 max-w-5xl">
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
          </div>

          <section class="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(220px,0.32fr)] gap-5 max-2xl:grid-cols-1">
            <div class="relative flex min-h-[420px] items-center justify-center overflow-visible">
              <img
                v-if="asset.kind === 'image' && asset.url"
                :src="asset.url"
                :alt="asset.title"
                class="relative z-10 max-h-full max-w-full rounded-2xl object-contain shadow-[0_24px_90px_rgb(0_0_0_/_0.62)]"
              />
              <video
                v-else-if="asset.kind === 'video' && asset.url"
                :src="asset.url"
                controls
                playsinline
                preload="metadata"
                class="relative z-10 max-h-full max-w-full rounded-2xl bg-black shadow-[0_24px_90px_rgb(0_0_0_/_0.62)]"
              />
              <div v-else-if="asset.kind === 'audio' && asset.url" class="relative z-10 w-full max-w-xl rounded-[28px] border border-white/10 bg-black/35 p-8">
                <div class="mb-8 flex items-center gap-4">
                  <div class="grid h-16 w-16 place-items-center rounded-3xl bg-primary/15 text-primary shadow-[0_0_40px_rgb(176_92_255_/_0.18)]">
                    <Music class="h-8 w-8" />
                  </div>
                  <div>
                    <p class="text-2xl font-black">{{ asset.title }}</p>
                    <p class="mt-1 text-sm text-white/45">Audio material</p>
                  </div>
                </div>
                <audio :src="asset.url" controls preload="metadata" class="w-full" />
              </div>
              <article v-else class="relative z-10 max-h-full w-full max-w-3xl overflow-auto rounded-[28px] border border-white/10 bg-black/30 p-8">
                <FileText class="mb-8 h-10 w-10 text-white/35" />
                <p class="whitespace-pre-wrap text-lg font-light leading-9 text-white/78">
                  {{ asset.rawText || asset.prompt || "暂无可预览内容" }}
                </p>
              </article>
            </div>

            <aside class="grid content-start gap-0 max-2xl:grid-cols-3 max-lg:grid-cols-1">
              <div class="border-white/10 py-4 max-2xl:border-r max-2xl:pr-5 max-lg:border-b max-lg:border-r-0 max-lg:pr-0">
                <p class="text-[11px] font-semibold uppercase tracking-[0.22em] text-white/30">提示词</p>
                <div class="relative mt-3 min-h-[5.5rem]">
                  <p class="line-clamp-7 pr-2 text-sm font-light leading-7 text-white/76">
                    {{ promptText || "暂无提示词记录" }}
                  </p>
                  <button
                    v-if="promptText"
                    type="button"
                    class="absolute bottom-0 right-0 inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/[0.06] px-3 py-1.5 text-xs font-semibold text-white/72 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.06)] transition hover:border-primary/35 hover:bg-primary/12 hover:text-white"
                    @click="copyPrompt"
                  >
                    <Check v-if="copyHint" class="h-3.5 w-3.5 text-emerald-300" />
                    <Copy v-else class="h-3.5 w-3.5" />
                    {{ copyHint || "复制" }}
                  </button>
                </div>
              </div>
              <div class="border-t border-white/10 py-4 max-2xl:border-l max-2xl:border-t-0 max-2xl:px-5 max-lg:border-b max-lg:border-l-0 max-lg:border-t max-lg:px-0">
                <p class="text-[11px] font-semibold uppercase tracking-[0.22em] text-white/30">来源</p>
                <p class="mt-3 text-lg font-black text-white/90">{{ asset.toolName || "AI 创作" }}</p>
                <p class="mt-2 truncate text-xs text-white/40">{{ asset.taskNo || asset.toolCode }}</p>
              </div>
              <div class="border-t border-white/10 py-4 max-2xl:border-l max-2xl:border-t-0 max-2xl:pl-5 max-lg:border-l-0 max-lg:border-t max-lg:pl-0">
                <p class="text-[11px] font-semibold uppercase tracking-[0.22em] text-white/30">创建时间</p>
                <p class="mt-3 inline-flex items-center gap-2 text-sm text-white/70">
                  <CalendarDays class="h-4 w-4 text-white/35" />
                  {{ formatTime(asset.createdAt) || "未知" }}
                </p>
                <a
                  v-if="canDownload"
                  :href="downloadUrl"
                  download
                  class="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-white/10 bg-[linear-gradient(135deg,rgb(255_255_255_/_0.08),rgb(176_92_255_/_0.12))] px-4 py-2.5 text-sm font-semibold text-white/82 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.08)] transition hover:border-primary/40 hover:text-white"
                >
                  <Download class="h-4 w-4" />
                  下载作品
                </a>
              </div>
            </aside>
          </section>
        </main>

        <aside class="min-h-0 border-l border-white/8 bg-[#0d0e13]/88 px-6 py-8 backdrop-blur-2xl max-xl:max-h-[48vh] max-xl:border-l-0 max-xl:border-t">
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
                @click="emit('publish', asset)"
              >
                <Globe2 class="h-4 w-4" />
                发布到主页
              </button>
              <button
                v-else
                type="button"
                class="inline-flex h-10 flex-1 items-center justify-center gap-2 rounded-full border border-white/10 bg-white/[0.06] px-4 text-sm font-semibold text-white/72 transition hover:bg-white/10"
                @click="emit('unpublish', asset)"
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

          <div class="mt-5 grid gap-3 overflow-y-auto pr-1">
            <button
              v-for="tool in recommendations?.slice(0, 6)"
              :key="tool.id"
              type="button"
              class="group grid grid-cols-[72px_minmax(0,1fr)] gap-4 rounded-[24px] border border-white/8 bg-white/[0.04] p-3 text-left transition hover:-translate-y-0.5 hover:border-primary/45 hover:bg-white/[0.07]"
              @click="emit('use-tool', tool, asset)"
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
                <p class="mt-2 inline-flex items-center gap-1 text-xs text-[#d7b77a]/80">
                  <Zap class="h-3.5 w-3.5 text-[#d7b77a]/70" />
                  {{ tool.estimatedCreditCost }} 算力/次
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
</template>
