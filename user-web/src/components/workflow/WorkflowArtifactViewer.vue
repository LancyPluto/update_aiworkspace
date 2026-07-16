<script setup lang="ts">
import { computed } from "vue"
import { Download, File, FileJson, FileText, Image, Music, Video } from "lucide-vue-next"
import type { WorkflowArtifact } from "@/api/workflowApi"
import { artifactView, workflowArtifactUrl } from "@/utils/workflowPresentation"

const props = defineProps<{
  artifacts: WorkflowArtifact[]
  adapterKey?: string | null
  adapterData?: Record<string, unknown> | null
}>()

const adapterLabel = computed(() => {
  const key = props.adapterKey?.toLocaleLowerCase() ?? ""
  return ["comic", "manju", "storyboard"].some((part) => key.includes(part)) ? "漫剧产物" : null
})

function artifactKey(artifact: WorkflowArtifact, index: number): string {
  return String(artifact.artifactId ?? artifact.id ?? `${artifact.type}-${index}`)
}

function valueOf(artifact: WorkflowArtifact): unknown {
  return artifact.content ?? artifact.value ?? ""
}

function textOf(artifact: WorkflowArtifact): string {
  const value = valueOf(artifact)
  return typeof value === "string" ? value : prettyJson(value)
}

function prettyJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value ?? "")
  }
}

function urlOf(artifact: WorkflowArtifact): string {
  return workflowArtifactUrl(artifact.url || artifact.downloadUrl)
}

function iconFor(type: string) {
  if (type === "TEXT") return FileText
  if (type === "JSON") return FileJson
  if (type === "IMAGE") return Image
  if (type === "AUDIO") return Music
  if (type === "VIDEO") return Video
  if (type === "FILE") return File
  return File
}
</script>

<template>
  <section aria-labelledby="workflow-artifacts-heading">
    <div class="flex items-center justify-between gap-4">
      <h2 id="workflow-artifacts-heading" class="text-base font-semibold">{{ adapterLabel || "运行产物" }}</h2>
      <span class="text-xs text-muted-foreground">{{ artifacts.length }} 项</span>
    </div>

    <div v-if="!artifacts.length" class="mt-4 rounded-md border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground">
      暂无产物，完成的步骤会把结果汇总到这里。
    </div>

    <div v-else class="mt-4 grid gap-4 md:grid-cols-2">
      <article v-for="(artifact, index) in artifacts" :id="`artifact-${artifact.id ?? index}`" :key="artifactKey(artifact, index)" class="min-w-0 rounded-lg border border-border bg-card p-4">
        <div class="mb-3 flex items-center justify-between gap-3">
          <div class="flex min-w-0 items-center gap-2">
            <component :is="iconFor(artifact.type)" class="h-4 w-4 shrink-0 text-primary" />
            <h3 class="truncate text-sm font-medium">{{ artifactView(artifact).label }}</h3>
          </div>
          <a
            v-if="urlOf(artifact)"
            :href="urlOf(artifact)"
            target="_blank"
            rel="noopener noreferrer"
            download
            class="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground"
            title="下载产物"
          >
            <Download class="h-4 w-4" />
          </a>
        </div>

        <pre v-if="artifactView(artifact).viewer === 'text'" class="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-md bg-secondary/40 p-3 text-xs leading-5">{{ textOf(artifact) }}</pre>
        <pre v-else-if="artifactView(artifact).viewer === 'json'" class="max-h-80 overflow-auto rounded-md bg-secondary/40 p-3 text-xs leading-5">{{ prettyJson(valueOf(artifact)) }}</pre>
        <img v-else-if="artifactView(artifact).viewer === 'image' && urlOf(artifact)" :src="urlOf(artifact)" :alt="artifactView(artifact).label" class="max-h-[32rem] w-full rounded-md bg-secondary object-contain" />
        <audio v-else-if="artifactView(artifact).viewer === 'audio' && urlOf(artifact)" :src="urlOf(artifact)" controls preload="metadata" class="w-full" />
        <video v-else-if="artifactView(artifact).viewer === 'video' && urlOf(artifact)" :src="urlOf(artifact)" controls preload="metadata" class="aspect-video w-full rounded-md bg-black object-contain" />
        <a v-else-if="artifactView(artifact).viewer === 'file' && urlOf(artifact)" :href="urlOf(artifact)" target="_blank" rel="noopener noreferrer" class="flex items-center gap-3 rounded-md bg-secondary/40 p-3 text-sm hover:bg-secondary">
          <File class="h-5 w-5 text-primary" />
          <span class="min-w-0 flex-1 truncate">{{ artifact.name || artifact.title || "打开文件" }}</span>
        </a>
        <div v-else class="rounded-md bg-secondary/40 p-3 text-xs text-muted-foreground">
          <p>暂不支持预览 {{ artifact.type }} 类型产物。</p>
          <pre v-if="valueOf(artifact)" class="mt-2 max-h-52 overflow-auto whitespace-pre-wrap break-words">{{ prettyJson(valueOf(artifact)) }}</pre>
        </div>
      </article>
    </div>
  </section>
</template>
