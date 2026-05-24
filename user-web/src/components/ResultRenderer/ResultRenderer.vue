<script setup lang="ts">
import { Download, FileDown, Printer } from "lucide-vue-next"
import type { ResultBlock } from "@/types/result"

defineProps<{
  blocks: ResultBlock[]
}>()

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
  <div class="space-y-4">
    <div
      v-for="(b, i) in blocks"
      :key="i"
      class="rounded-xl border border-border bg-card p-4 sm:p-6 shadow-sm"
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
        <div class="mb-3">
          <p class="text-xs text-muted-foreground">图片结果</p>
          <h2 class="text-base font-semibold text-foreground">{{ b.title }}</h2>
        </div>
        <div class="grid gap-3 sm:grid-cols-2">
          <figure
            v-for="image in b.images"
            :key="image.url"
            class="overflow-hidden rounded-lg border border-border bg-background"
          >
            <div class="flex aspect-square items-center justify-center bg-secondary/30">
              <img
                :src="image.url"
                :alt="image.label ?? b.title"
                class="h-full w-full object-contain"
                loading="lazy"
              />
            </div>
            <figcaption class="flex items-center justify-between gap-3 border-t border-border px-3 py-2 text-xs text-muted-foreground">
              <span>{{ image.label ?? "图片" }}</span>
              <a :href="image.url" download class="inline-flex items-center gap-1 text-foreground hover:text-primary">
                <Download class="h-3.5 w-3.5" />
                下载
              </a>
            </figcaption>
          </figure>
        </div>
      </template>
      <template v-else-if="b.type === 'audio'">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
          <div>
            <p class="text-xs text-muted-foreground">音频结果</p>
            <h2 class="text-base font-semibold text-foreground">{{ b.title }}</h2>
          </div>
          <a
            :href="b.url"
            :download="b.downloadName ?? 'audio-result'"
            class="inline-flex h-9 items-center justify-center gap-2 rounded-md border border-border bg-background px-3 text-sm font-medium hover:bg-secondary"
          >
            <Download class="h-4 w-4" />
            下载音频
          </a>
        </div>
        <audio :src="b.url" controls preload="metadata" class="w-full">
          当前浏览器不支持音频播放。
        </audio>
      </template>
      <template v-else-if="b.type === 'video'">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
          <div>
            <p class="text-xs text-muted-foreground">视频结果</p>
            <h2 class="text-base font-semibold text-foreground">{{ b.title }}</h2>
          </div>
          <a
            :href="b.url"
            :download="b.downloadName ?? 'digital-human-video.mp4'"
            class="inline-flex h-9 items-center justify-center gap-2 rounded-md border border-border bg-background px-3 text-sm font-medium hover:bg-secondary"
          >
            <Download class="h-4 w-4" />
            下载视频
          </a>
        </div>
        <video
          :src="b.url"
          controls
          playsinline
          preload="metadata"
          class="aspect-video w-full rounded-lg border border-border bg-black"
        >
          当前浏览器不支持视频播放。
        </video>
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
