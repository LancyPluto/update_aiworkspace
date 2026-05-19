<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { Download } from 'lucide-vue-next'
import { getRequestBaseUrl } from '@/api/client'

const props = defineProps({
  message: { type: String, default: '' },
  isUser: { type: Boolean, default: false },
  streaming: { type: Boolean, default: false },
})

const displayed = ref('')
const fullText = computed(() => props.message ?? '')
const videoItems = computed(() => extractVideoUrls(fullText.value).map((url, index) => {
  const normalizedUrl = normalizeMediaUrl(url)
  return {
    url: normalizedUrl,
    downloadName: downloadNameFromUrl(normalizedUrl, index),
  }
}))

// 流式打字动画
async function type() {
  displayed.value = ''
  const text = fullText.value
  for (let i = 0; i < text.length; i++) {
    if (!props.streaming) break
    displayed.value += text[i]
    await new Promise(r => setTimeout(r, 4))
  }
  displayed.value = text
}

// XSS 安全过滤
function cleanXss(str: string) {
  return str
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

// 渲染：先展示 → 后高亮（懒处理）
function renderContent() {
  let html = cleanXss(displayed.value)
  html = html.replace(/```([\s\S]*?)```/g, (_, code) => {
    return `<pre class="code-block"><code>${cleanXss(code)}</code></pre>`
  })
  return html
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
  return value.trim().replace(/[)\]，。,.、；;]+$/g, '')
}

function normalizeMediaUrl(value: string) {
  if (/^(https?:\/\/|data:video\/)/i.test(value)) return value
  const path = value.startsWith('/') ? value : `/${value}`
  const base = getRequestBaseUrl()
  return new URL(path, base.endsWith('/') ? base : `${base}/`).toString()
}

function downloadNameFromUrl(value: string, index: number) {
  try {
    const pathname = new URL(value).pathname
    const name = pathname.split('/').filter(Boolean).at(-1)
    return name && name.includes('.') ? name : `agent-video-${index + 1}.mp4`
  } catch {
    return `agent-video-${index + 1}.mp4`
  }
}

onMounted(() => {
  if (props.streaming) type()
  else displayed.value = fullText.value
})

watch(() => props.message, async () => {
  if (props.streaming) {
    await nextTick()
    type()
  } else {
    displayed.value = props.message
  }
})
</script>

<template>
  <div class="message-content">
    <div class="text" v-html="renderContent()"></div>
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
    <span v-if="streaming" class="stream-cursor"></span>
  </div>
</template>

<style scoped>
.message-content {
  line-height: 1.6;
  position: relative;
}
.text {
  white-space: pre-wrap;
  word-break: break-word;
}
.code-block {
  background: #f1f5f9;
  padding: 10px;
  border-radius: 8px;
  margin: 8px 0;
  overflow-x: auto;
  font-size: 13px;
}
.video-stack {
  display: grid;
  gap: 12px;
  margin-top: 12px;
}
.video-card {
  overflow: hidden;
  border: 1px solid #dbe4ef;
  border-radius: 10px;
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
  background: #666;
  margin-left: 2px;
  animation: blink 1s infinite;
}
@keyframes blink {
  0%,100% { opacity:1; }
  50% { opacity:0; }
}
</style>
