<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from "vue"
import { Download } from "lucide-vue-next"
import { getRequestBaseUrl } from "@/api/client"
import ResultRenderer from "@/components/ResultRenderer/ResultRenderer.vue"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"
import { renderMarkdown } from "@/utils/markdownRender"
import type { ResultBlock } from "@/types/result"

const props = defineProps({
  message: { type: String, default: "" },
  isUser: { type: Boolean, default: false },
  streaming: { type: Boolean, default: false },
})

const displayed = ref("")
const fullText = computed(() => props.message ?? "")
const resultBlocks = computed(() => buildStructuredResultBlocks(fullText.value))
const renderedText = computed(() => (resultBlocks.value.length > 0 ? "" : displayed.value))
const renderedHtml = computed(() => renderMarkdown(renderedText.value))
const videoItems = computed(() =>
  resultBlocks.value.length > 0
    ? []
    : extractVideoUrls(fullText.value).map((url, index) => {
        const normalizedUrl = normalizeMediaUrl(url)
        return {
          url: normalizedUrl,
          downloadName: downloadNameFromUrl(normalizedUrl, index),
        }
      }),
)

// 流式打字动画
async function type() {
  displayed.value = ""
  const text = fullText.value
  for (let i = 0; i < text.length; i++) {
    if (!props.streaming) break
    displayed.value += text[i]
    await new Promise((r) => setTimeout(r, 4))
  }
  displayed.value = text
}

function extractVideoUrls(value: string) {
  const urls = new Set<string>()
  const patterns = [
    /(?:最终成片|成片|视频链接|视频地址|视频|final video)\s*[:：]\s*(\S+?\.mp4(?:\?\S*)?)/gi,
    /\[[^\]]*?(?:视频|成片|video)[^\]]*?\]\((\S+?\.mp4(?:\?\S*)?)\)/gi,
    /(https?:\/\/\S+?\.mp4(?:\?\S*)?)/gi,
    /(\/generated\/\S+?\.mp4(?:\?\S*)?)/gi,
  ]
  for (const pattern of patterns) {
    for (const match of value.matchAll(pattern)) {
      const raw = match[1]
      if (raw) urls.add(sanitizeUrl(raw))
    }
  }
  return Array.from(urls)
}

function sanitizeUrl(value: string) {
  return value.trim().replace(/[)\]，。,.、；;]+$/g, "")
}

function normalizeMediaUrl(value: string) {
  if (/^(https?:\/\/|data:video\/)/i.test(value)) return value
  const path = value.startsWith("/") ? value : `/${value}`
  const base = getRequestBaseUrl()
  return new URL(path, base.endsWith("/") ? base : `${base}/`).toString()
}

function downloadNameFromUrl(value: string, index: number) {
  try {
    const pathname = new URL(value).pathname
    const name = pathname.split("/").filter(Boolean).at(-1)
    return name && name.includes(".") ? name : `agent-video-${index + 1}.mp4`
  } catch {
    return `agent-video-${index + 1}.mp4`
  }
}

function buildStructuredResultBlocks(value: string): ResultBlock[] {
  const trimmed = value.trim()
  if (!trimmed || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) return []
  const blocks = buildTaskResultBlocks(trimmed)
  if (blocks.length === 1 && (blocks[0].type === "text" || blocks[0].type === "json")) return []
  return blocks
}

onMounted(() => {
  if (props.streaming) void type()
  else displayed.value = fullText.value
})

watch(
  () => props.message,
  async () => {
    if (props.streaming) {
      await nextTick()
      void type()
    } else {
      displayed.value = props.message ?? ""
    }
  },
)
</script>

<template>
  <div class="message-content" :class="{ 'message-content--user': isUser }">
    <div
      v-if="renderedHtml"
      class="markdown-body"
      :class="{ 'markdown-body--user': isUser }"
      v-html="renderedHtml"
    />
    <div v-if="videoItems.length" class="video-stack">
      <section v-for="video in videoItems" :key="video.url" class="video-card">
        <div class="video-card__bar">
          <span>视频结果</span>
          <a :href="video.url" :download="video.downloadName" class="video-download">
            <Download class="download-icon" />
            下载
          </a>
        </div>
        <video :src="video.url" controls playsinline preload="metadata" class="video-player">
          当前浏览器不支持视频播放。
        </video>
      </section>
    </div>
    <ResultRenderer v-if="resultBlocks.length" class="agent-result-renderer" :blocks="resultBlocks" />
    <span v-if="streaming && !renderedHtml && !resultBlocks.length" class="stream-placeholder" />
    <span v-if="streaming" class="stream-cursor" />
  </div>
</template>

<style scoped>
.message-content {
  line-height: 1.65;
  position: relative;
}

.markdown-body {
  word-break: break-word;
}

.markdown-body :deep(p) {
  margin: 0 0 0.75em;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 1em 0 0.5em;
  font-weight: 600;
  line-height: 1.35;
}

.markdown-body :deep(h1) {
  font-size: 1.35em;
}

.markdown-body :deep(h2) {
  font-size: 1.2em;
}

.markdown-body :deep(h3) {
  font-size: 1.08em;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0.5em 0 0.75em;
  padding-left: 1.4em;
}

.markdown-body :deep(li + li) {
  margin-top: 0.25em;
}

.markdown-body :deep(blockquote) {
  margin: 0.75em 0;
  padding: 0.35em 0.9em;
  border-left: 3px solid color-mix(in srgb, var(--primary) 55%, var(--border));
  background: color-mix(in srgb, var(--muted) 65%, transparent);
  color: var(--muted-foreground);
}

.markdown-body :deep(a) {
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.markdown-body :deep(hr) {
  margin: 1em 0;
  border: none;
  border-top: 1px solid var(--border);
}

.markdown-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
  padding: 0.15em 0.35em;
  border-radius: 4px;
  background: color-mix(in srgb, var(--muted) 80%, transparent);
}

.markdown-body :deep(pre.code-block) {
  margin: 0.75em 0;
  padding: 12px 14px;
  border-radius: 8px;
  overflow-x: auto;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 13px;
  line-height: 1.55;
}

.markdown-body :deep(pre.code-block code) {
  padding: 0;
  background: transparent;
  color: inherit;
  font-size: inherit;
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 0.75em 0;
  border-collapse: collapse;
  font-size: 0.92em;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--border);
  padding: 0.45em 0.65em;
  text-align: left;
}

.markdown-body :deep(th) {
  background: color-mix(in srgb, var(--muted) 70%, transparent);
}

.markdown-body--user :deep(a) {
  color: color-mix(in srgb, #fff 92%, var(--primary));
}

.markdown-body--user :deep(blockquote) {
  border-left-color: color-mix(in srgb, #fff 45%, transparent);
  background: color-mix(in srgb, #fff 12%, transparent);
  color: color-mix(in srgb, #fff 85%, transparent);
}

.markdown-body--user :deep(code) {
  background: color-mix(in srgb, #fff 18%, transparent);
  color: inherit;
}

.video-stack {
  display: grid;
  gap: 12px;
  margin-top: 12px;
}

.video-card {
  overflow: hidden;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
}

.video-card__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid #e5edf5;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}

.video-download {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #0f172a;
  text-decoration: none;
  white-space: nowrap;
}

.video-download:hover {
  color: #2563eb;
}

.download-icon {
  width: 15px;
  height: 15px;
}

.video-player {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #000000;
}

.stream-cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  background: currentColor;
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 1s infinite;
}

.stream-placeholder {
  display: inline-block;
  width: 0.5em;
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>
