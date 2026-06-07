<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue"
import { Download, FileDown, Music, Printer } from "lucide-vue-next"
import type { AssetPreviewItem } from "@/types/assetPreview"
import type { AudioTrackItem, ResultBlock } from "@/types/result"
import { formatAudioDuration, resolveAudioTracks } from "@/utils/taskResultBlocks"
import type { ChatAssetRef } from "@/utils/agentChatAssetRefs"
import { bindLongPressReference, writeAssetDragData } from "@/utils/agentChatAssetRefs"

const props = withDefaults(defineProps<{
  blocks: ResultBlock[]
  mode?: "default" | "compact"
  resolveChatAsset?: (url: string) => ChatAssetRef | undefined
  enableAssetDrag?: boolean
}>(), {
  mode: "default",
  enableAssetDrag: false,
})

const emit = defineEmits<{
  preview: [asset: AssetPreviewItem]
  reference: [payload: import("@/utils/agentChatAssetRefs").ChatAssetDragPayload]
}>()

const longPressCleanups = ref<Array<() => void>>([])

function cleanupLongPressBindings() {
  for (const cleanup of longPressCleanups.value) cleanup()
  longPressCleanups.value = []
}

function registerDraggableAsset(element: HTMLElement | null, url: string) {
  if (!element || !props.enableAssetDrag) return
  const asset = props.resolveChatAsset?.(url)
  if (!asset) return
  const cleanup = bindLongPressReference(element, asset, (payload) => emit("reference", payload))
  longPressCleanups.value.push(cleanup)
}

function setDraggableAssetRef(element: Element | null, url: string) {
  registerDraggableAsset(element as HTMLElement | null, url)
}

function onAssetDragStart(event: DragEvent, url: string) {
  if (!props.enableAssetDrag) return
  const asset = props.resolveChatAsset?.(url)
  if (!asset) return
  writeAssetDragData(event, asset)
}

onBeforeUnmount(() => {
  cleanupLongPressBindings()
})

function isMediaBlock(block: ResultBlock) {
  return block.type === "image" || block.type === "audio" || block.type === "video"
}

function isCompactMediaBlock(block: ResultBlock) {
  return props.mode === "compact" && isMediaBlock(block)
}

function rendererClass() {
  return props.mode === "compact" ? "space-y-3" : "space-y-4"
}

function blockShellClass(block: ResultBlock) {
  if (isCompactMediaBlock(block)) {
    return "p-0 shadow-none"
  }
  return "rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6"
}

function mediaGridClass(count = 0) {
  if (props.mode !== "compact") return "grid gap-3 sm:grid-cols-2"
  return count > 1 ? "grid gap-3 sm:grid-cols-2" : "grid gap-3"
}

function figureClass() {
  return props.mode === "compact"
    ? "group relative overflow-hidden rounded-xl bg-transparent shadow-[0_20px_72px_rgb(0_0_0_/_0.34)]"
    : "overflow-hidden rounded-lg border border-border bg-background"
}

function imageFrameClass() {
  return props.mode === "compact"
    ? "flex max-h-[560px] items-center justify-center bg-transparent"
    : "flex aspect-square items-center justify-center bg-secondary/30"
}

function imageClass() {
  return props.mode === "compact" ? "max-h-[560px] w-full rounded-xl object-contain" : "h-full w-full object-contain"
}

function captionClass() {
  return props.mode === "compact"
    ? "flex items-center justify-between gap-3 px-3 py-2 text-xs text-white/55"
    : "flex items-center justify-between gap-3 border-t border-border px-3 py-2 text-xs text-muted-foreground"
}

function downloadLinkClass() {
  return props.mode === "compact"
    ? "inline-flex items-center gap-1 text-white/70 hover:text-white"
    : "inline-flex items-center gap-1 text-foreground hover:text-primary"
}

function floatingDownloadClass() {
  return "absolute right-3 top-3 inline-flex h-9 items-center gap-1.5 rounded-full border border-white/12 bg-black/42 px-3 text-xs font-medium text-white/72 opacity-0 shadow-[0_10px_30px_rgb(0_0_0_/_0.28)] backdrop-blur-xl transition group-hover:opacity-100 hover:bg-white/14 hover:text-white"
}

function mediaActionClass() {
  return props.mode === "compact"
    ? "inline-flex h-8 items-center justify-center gap-2 rounded-full border border-white/10 bg-white/8 px-3 text-xs font-medium text-white/70 hover:bg-white/12 hover:text-white"
    : "inline-flex h-9 items-center justify-center gap-2 rounded-md border border-border bg-background px-3 text-sm font-medium hover:bg-secondary"
}

function videoClass() {
  return props.mode === "compact"
    ? "aspect-video w-full rounded-xl bg-black shadow-[0_20px_72px_rgb(0_0_0_/_0.34)]"
    : "aspect-video w-full rounded-lg border border-border bg-black"
}

function audioClass() {
  return props.mode === "compact" ? "w-full px-2 pb-2" : "w-full"
}

function previewImage(block: Extract<ResultBlock, { type: "image" }>, image: { url: string; label?: string }, index: number) {
  emit("preview", {
    id: `result-image-${index}-${image.url}`,
    kind: "image",
    title: image.label || block.title || "生成图片",
    subtitle: block.title,
    url: image.url,
    urls: block.images.map((item) => item.url),
  })
}

function previewVideo(block: Extract<ResultBlock, { type: "video" }>) {
  emit("preview", {
    id: `result-video-${block.url}`,
    kind: "video",
    title: block.title || "生成视频",
    url: block.url,
  })
}

function previewAudio(block: Extract<ResultBlock, { type: "audio" }>, track: AudioTrackItem) {
  emit("preview", {
    id: `result-audio-${track.url}`,
    kind: "audio",
    title: track.title || block.title || "生成音频",
    url: track.url,
    coverUrl: track.coverUrl,
  })
}

function audioTracks(block: Extract<ResultBlock, { type: "audio" }>) {
  return resolveAudioTracks(block)
}

function downloadReportDocx(block: Extract<ResultBlock, { type: "report" }>) {
  const blob = buildDocxBlob(block.title, block.content)
  downloadBlob(blob, `${block.filename}.docx`)
}

function printReportPdf(block: Extract<ResultBlock, { type: "report" }>) {
  const printWindow = window.open("", "_blank", "noopener,noreferrer,width=960,height=720")
  if (!printWindow) return

  printWindow.document.write(`
<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <title>${escapeHtml(block.title)}</title>
    <style>
      body { margin: 0; background: #f5f3ef; color: #1f2933; font-family: "Microsoft YaHei", "Noto Sans CJK SC", sans-serif; }
      main { max-width: 820px; margin: 32px auto; background: #fff; padding: 42px 48px; box-shadow: 0 12px 36px rgba(15, 23, 42, .12); }
      h1 { font-size: 26px; margin: 0 0 18px; }
      pre { white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.75; font-family: inherit; }
      @media print { body { background: #fff; } main { margin: 0; padding: 0; box-shadow: none; max-width: none; } }
    </style>
  </head>
  <body>
    <main>
      <h1>${escapeHtml(block.title)}</h1>
      <pre>${escapeHtml(block.content)}</pre>
    </main>
  </body>
</html>`)
  printWindow.document.close()
  printWindow.focus()
  window.setTimeout(() => printWindow.print(), 200)
}

function buildDocxBlob(title: string, markdown: string): Blob {
  const files = [
    {
      name: "[Content_Types].xml",
      content:
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
        '<Default Extension="xml" ContentType="application/xml"/>' +
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
        "</Types>",
    },
    {
      name: "_rels/.rels",
      content:
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>' +
        "</Relationships>",
    },
    {
      name: "word/document.xml",
      content: buildDocumentXml(title, markdown),
    },
  ]
  return new Blob([buildZip(files)], {
    type: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  })
}

function buildDocumentXml(title: string, markdown: string): string {
  const paragraphs = [title, "", ...markdown.split(/\r?\n/)]
  const body = paragraphs.map((line) => buildParagraph(line)).join("")
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">' +
    `<w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body>` +
    "</w:document>"
  )
}

function buildParagraph(line: string): string {
  const trimmed = line.trim()
  const headingLevel = trimmed.startsWith("# ") ? 1 : trimmed.startsWith("## ") ? 2 : 0
  const text = trimmed.replace(/^#{1,6}\s+/, "")
  const runProps =
    headingLevel === 1
      ? '<w:rPr><w:b/><w:sz w:val="32"/></w:rPr>'
      : headingLevel === 2
        ? '<w:rPr><w:b/><w:sz w:val="26"/></w:rPr>'
        : ""
  return `<w:p><w:r>${runProps}<w:t xml:space="preserve">${escapeXml(text)}</w:t></w:r></w:p>`
}

function buildZip(files: Array<{ name: string; content: string }>): Uint8Array {
  const encoder = new TextEncoder()
  const chunks: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0

  for (const file of files) {
    const name = encoder.encode(file.name)
    const data = encoder.encode(file.content)
    const crc = crc32(data)
    chunks.push(localFileHeader(name, data, crc), data)
    central.push(centralDirectoryHeader(name, data, crc, offset))
    offset += 30 + name.length + data.length
  }

  const centralOffset = offset
  const centralSize = central.reduce((sum, chunk) => sum + chunk.length, 0)
  const end = endOfCentralDirectory(files.length, centralSize, centralOffset)
  return concatUint8Arrays([...chunks, ...central, end])
}

function localFileHeader(name: Uint8Array, data: Uint8Array, crc: number): Uint8Array {
  const header = new Uint8Array(30 + name.length)
  const view = new DataView(header.buffer)
  view.setUint32(0, 0x04034b50, true)
  view.setUint16(4, 20, true)
  view.setUint32(14, crc, true)
  view.setUint32(16, data.length, true)
  view.setUint32(20, data.length, true)
  view.setUint16(26, name.length, true)
  header.set(name, 30)
  return header
}

function centralDirectoryHeader(name: Uint8Array, data: Uint8Array, crc: number, offset: number): Uint8Array {
  const header = new Uint8Array(46 + name.length)
  const view = new DataView(header.buffer)
  view.setUint32(0, 0x02014b50, true)
  view.setUint16(4, 20, true)
  view.setUint16(6, 20, true)
  view.setUint32(16, crc, true)
  view.setUint32(20, data.length, true)
  view.setUint32(24, data.length, true)
  view.setUint16(28, name.length, true)
  view.setUint32(42, offset, true)
  header.set(name, 46)
  return header
}

function endOfCentralDirectory(fileCount: number, centralSize: number, centralOffset: number): Uint8Array {
  const header = new Uint8Array(22)
  const view = new DataView(header.buffer)
  view.setUint32(0, 0x06054b50, true)
  view.setUint16(8, fileCount, true)
  view.setUint16(10, fileCount, true)
  view.setUint32(12, centralSize, true)
  view.setUint32(16, centralOffset, true)
  return header
}

function crc32(data: Uint8Array): number {
  let crc = 0xffffffff
  for (const byte of data) {
    crc ^= byte
    for (let i = 0; i < 8; i += 1) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1))
    }
  }
  return (crc ^ 0xffffffff) >>> 0
}

function concatUint8Arrays(chunks: Uint8Array[]): Uint8Array {
  const size = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const result = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) {
    result.set(chunk, offset)
    offset += chunk.length
  }
  return result
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement("a")
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;")
}

function escapeXml(value: string): string {
  return escapeHtml(value)
}
</script>

<template>
  <div :class="rendererClass()">
    <div
      v-for="(b, i) in blocks"
      :key="i"
      :class="blockShellClass(b)"
    >
      <template v-if="b.type === 'text'">
        <p class="text-xs text-muted-foreground mb-1">{{ b.title }}</p>
        <p class="text-sm leading-relaxed whitespace-pre-wrap">{{ b.content }}</p>
      </template>
      <template v-else-if="b.type === 'json'">
        <p class="text-xs text-muted-foreground mb-2">{{ b.title }}</p>
        <pre class="overflow-x-auto rounded-lg border border-border bg-background p-4 text-xs leading-relaxed text-foreground/90">{{ b.content }}</pre>
      </template>
      <template v-else-if="b.type === 'image'">
        <div v-if="props.mode !== 'compact'" class="mb-3">
          <p class="text-xs text-muted-foreground">图片结果</p>
          <h2 class="text-base font-semibold text-foreground">{{ b.title }}</h2>
        </div>
        <div :class="mediaGridClass(b.images.length)">
          <figure
            v-for="(image, imageIndex) in b.images"
            :key="image.url"
            :ref="(el) => setDraggableAssetRef(el as Element | null, image.url)"
            :class="[figureClass(), resolveChatAsset?.(image.url) && enableAssetDrag ? 'chat-asset-draggable' : '']"
            class="cursor-zoom-in"
            :draggable="enableAssetDrag && !!resolveChatAsset?.(image.url)"
            @dragstart="onAssetDragStart($event, image.url)"
            @click="previewImage(b, image, imageIndex)"
          >
            <div :class="imageFrameClass()">
              <img
                :src="image.url"
                :alt="image.label ?? b.title"
                :class="imageClass()"
                loading="lazy"
              />
            </div>
            <figcaption v-if="props.mode !== 'compact'" :class="captionClass()">
              <span>{{ image.label ?? "图片" }}</span>
              <a :href="image.url" download :class="downloadLinkClass()" @click.stop>
                <Download class="h-3.5 w-3.5" />
                下载
              </a>
            </figcaption>
            <a
              v-if="props.mode === 'compact'"
              :href="image.url"
              download
              :class="floatingDownloadClass()"
            >
              <Download class="h-3.5 w-3.5" />
              下载
            </a>
          </figure>
        </div>
      </template>
      <template v-else-if="b.type === 'audio'">
        <div
          v-if="props.mode !== 'compact'"
          class="mb-3 flex flex-wrap items-center justify-between gap-3"
        >
          <div>
            <p class="text-xs text-muted-foreground">音频结果</p>
            <h2 class="text-base font-semibold text-foreground">{{ b.title }}</h2>
            <p v-if="audioTracks(b).length > 1" class="mt-1 text-xs text-muted-foreground">
              Suno 单次生成通常返回 {{ audioTracks(b).length }} 个版本，可分别试听与下载。
            </p>
          </div>
        </div>
        <div :class="mediaGridClass(audioTracks(b).length)">
          <article
            v-for="(track, trackIndex) in audioTracks(b)"
            :key="`${track.url}-${trackIndex}`"
            :ref="(el) => setDraggableAssetRef(el as Element | null, track.url)"
            :class="[
              'overflow-hidden rounded-xl border border-border bg-background/80',
              resolveChatAsset?.(track.url) && enableAssetDrag ? 'chat-asset-draggable' : '',
            ]"
            :draggable="enableAssetDrag && !!resolveChatAsset?.(track.url)"
            @dragstart="onAssetDragStart($event, track.url)"
          >
            <div class="relative aspect-[4/3] overflow-hidden bg-secondary/40">
              <img
                v-if="track.coverUrl"
                :src="track.coverUrl"
                :alt="track.title || b.title"
                class="h-full w-full object-cover"
                loading="lazy"
              />
              <div
                v-else
                class="flex h-full w-full items-center justify-center bg-gradient-to-br from-primary/20 via-background to-secondary/60 text-primary"
              >
                <Music class="h-10 w-10 opacity-80" />
              </div>
              <span
                v-if="formatAudioDuration(track.duration)"
                class="absolute bottom-3 right-3 rounded-full bg-black/55 px-2 py-0.5 text-[11px] text-white/90"
              >
                {{ formatAudioDuration(track.duration) }}
              </span>
            </div>
            <div class="space-y-3 p-4">
              <div class="flex items-start justify-between gap-3">
                <div class="min-w-0">
                  <p class="truncate text-sm font-semibold text-foreground">{{ track.title || `版本 ${trackIndex + 1}` }}</p>
                  <p class="text-xs text-muted-foreground">{{ b.title }}</p>
                </div>
                <a
                  :href="track.url"
                  :download="track.downloadName ?? `audio-${trackIndex + 1}`"
                  class="inline-flex h-8 shrink-0 items-center gap-1 rounded-md border border-border px-2.5 text-xs hover:bg-secondary"
                  @click.stop
                >
                  <Download class="h-3.5 w-3.5" />
                  下载
                </a>
              </div>
              <audio :src="track.url" controls preload="metadata" class="w-full">
                当前浏览器不支持音频播放。
              </audio>
              <button
                v-if="props.mode === 'compact'"
                type="button"
                class="text-xs text-white/45 transition hover:text-white"
                @click="previewAudio(b, track)"
              >
                打开资产卡片
              </button>
            </div>
          </article>
        </div>
      </template>
      <template v-else-if="b.type === 'video'">
        <div
          v-if="props.mode !== 'compact'"
          class="mb-3 flex flex-wrap items-center justify-between gap-3"
        >
          <div>
            <p class="text-xs text-muted-foreground">视频结果</p>
            <h2 class="text-base font-semibold text-foreground">{{ b.title }}</h2>
          </div>
          <a
            :href="b.url"
            :download="b.downloadName ?? 'digital-human-video.mp4'"
            class="inline-flex h-9 items-center justify-center gap-2 rounded-md border border-border bg-background px-3 text-sm font-medium hover:bg-secondary"
            @click.stop
          >
            <Download class="h-4 w-4" />
            下载视频
          </a>
        </div>
        <div
          :ref="(el) => setDraggableAssetRef(el as Element | null, b.url)"
          :class="resolveChatAsset?.(b.url) && enableAssetDrag ? 'chat-asset-draggable' : ''"
          :draggable="enableAssetDrag && !!resolveChatAsset?.(b.url)"
          @dragstart="onAssetDragStart($event, b.url)"
        >
          <video
            :src="b.url"
            controls
            playsinline
            preload="metadata"
            :class="videoClass()"
            class="cursor-zoom-in"
            @click="previewVideo(b)"
          >
            当前浏览器不支持视频播放。
          </video>
        </div>
        <div v-if="props.mode === 'compact'" class="flex justify-end px-2 pb-2 pt-2">
          <a
            :href="b.url"
            :download="b.downloadName ?? 'digital-human-video.mp4'"
            :class="mediaActionClass()"
            @click.stop
          >
            <Download class="h-4 w-4" />
            下载
          </a>
        </div>
      </template>
      <template v-else-if="b.type === 'report'">
        <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <p class="text-xs text-muted-foreground">企业诊断报告</p>
            <h2 class="text-base font-semibold text-foreground">{{ b.title }}</h2>
          </div>
          <div class="flex flex-wrap gap-2">
            <button
              type="button"
              class="inline-flex h-9 items-center justify-center gap-2 rounded-md border border-border bg-background px-3 text-sm font-medium hover:bg-secondary"
              @click="printReportPdf(b)"
            >
              <Printer class="h-4 w-4" />
              导出 PDF
            </button>
            <button
              type="button"
              class="inline-flex h-9 items-center justify-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground hover:opacity-90"
              @click="downloadReportDocx(b)"
            >
              <FileDown class="h-4 w-4" />
              下载 DOCX
            </button>
          </div>
        </div>
        <article class="report-preview rounded-lg border border-border bg-background p-4 text-sm leading-7 text-foreground/90 whitespace-pre-wrap">
          {{ b.content }}
        </article>
      </template>
      <template v-else>
        <p class="text-xs text-muted-foreground mb-2">{{ b.title }}</p>
        <ol class="space-y-1.5 text-sm list-decimal list-inside text-foreground/90">
          <li v-for="item in b.items" :key="item">{{ item }}</li>
        </ol>
      </template>
    </div>
  </div>
</template>

<style scoped>
.chat-asset-draggable {
  cursor: grab;
}

.chat-asset-draggable:active {
  cursor: grabbing;
  opacity: 0.88;
}
</style>
