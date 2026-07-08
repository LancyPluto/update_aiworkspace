<script setup lang="ts">
import { ref } from "vue"
import { FileUp, ImageUp, Loader2, Music, Video, X } from "lucide-vue-next"
import { uploadChatFile } from "@/api/aiToolApi"
import { getApiOrigin } from "@/api/client"

const props = withDefaults(defineProps<{
  modelValue: string
  accept?: string
  kind?: "image" | "video" | "audio" | "file"
  toolId?: string | null
  hint?: string
}>(), {
  accept: "image/*",
  kind: "image",
  toolId: null,
  hint: "",
})

const emit = defineEmits<{
  "update:modelValue": [value: string]
}>()

const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const dragOver = ref(false)
const uploadError = ref("")

const promptByKind: Record<string, string> = {
  image: "点击上传图片",
  video: "点击上传视频",
  audio: "点击上传音频",
  file: "点击上传文件",
}

function previewUrl(value: string): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:") || raw.startsWith("blob:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function openPicker() {
  if (uploading.value) return
  fileInput.value?.click()
}

async function handleFile(file: File | undefined | null) {
  if (!file) return
  uploadError.value = ""
  uploading.value = true
  try {
    const uploaded = await uploadChatFile(file, { toolId: props.toolId || null })
    if (uploaded?.url) {
      emit("update:modelValue", uploaded.url)
    } else {
      uploadError.value = "上传完成但未返回资源地址"
    }
  } catch (e) {
    uploadError.value = e instanceof Error ? e.message : "上传失败"
  } finally {
    uploading.value = false
  }
}

async function onFilePicked(ev: Event) {
  const input = ev.target as HTMLInputElement
  await handleFile(input.files?.[0])
  input.value = ""
}

function onDrop(ev: DragEvent) {
  dragOver.value = false
  if (uploading.value) return
  void handleFile(ev.dataTransfer?.files?.[0])
}

function clearValue() {
  emit("update:modelValue", "")
  uploadError.value = ""
}
</script>

<template>
  <div class="space-y-2">
    <!-- 已上传：预览 -->
    <div
      v-if="modelValue && !uploading"
      class="group relative overflow-hidden rounded-xl border border-border bg-background/60"
    >
      <img
        v-if="kind === 'image'"
        :src="previewUrl(modelValue)"
        alt="已上传素材"
        class="max-h-64 w-full object-contain"
      />
      <video
        v-else-if="kind === 'video'"
        :src="previewUrl(modelValue)"
        class="max-h-64 w-full object-contain bg-black"
        controls
        playsinline
        preload="metadata"
      />
      <div v-else class="flex items-center gap-3 px-4 py-5 text-sm">
        <Music v-if="kind === 'audio'" class="h-5 w-5 text-primary" />
        <FileUp v-else class="h-5 w-5 text-primary" />
        <span class="truncate text-foreground/80">{{ modelValue }}</span>
      </div>

      <div class="absolute inset-x-0 bottom-0 flex items-center justify-end gap-2 bg-gradient-to-t from-black/70 to-transparent p-2 opacity-0 transition group-hover:opacity-100">
        <button
          type="button"
          class="inline-flex items-center gap-1 rounded-md bg-white/15 px-2.5 py-1 text-xs font-medium text-white backdrop-blur transition hover:bg-white/25"
          @click="openPicker"
        >
          重新上传
        </button>
        <button
          type="button"
          class="inline-flex items-center gap-1 rounded-md bg-white/15 px-2.5 py-1 text-xs font-medium text-white backdrop-blur transition hover:bg-white/25"
          @click="clearValue"
        >
          <X class="h-3.5 w-3.5" />移除
        </button>
      </div>
    </div>

    <!-- 上传区：点击 + 拖拽 -->
    <button
      v-else
      type="button"
      class="flex w-full flex-col items-center justify-center gap-3 rounded-xl border border-dashed px-4 py-10 text-center transition"
      :class="dragOver
        ? 'border-primary bg-primary/10'
        : 'border-border bg-background/40 hover:border-primary/50 hover:bg-background/70'"
      :disabled="uploading"
      @click="openPicker"
      @dragenter.prevent="dragOver = true"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="onDrop"
    >
      <Loader2 v-if="uploading" class="h-7 w-7 animate-spin text-primary" />
      <template v-else>
        <ImageUp v-if="kind === 'image'" class="h-8 w-8 text-muted-foreground" />
        <Video v-else-if="kind === 'video'" class="h-8 w-8 text-muted-foreground" />
        <Music v-else-if="kind === 'audio'" class="h-8 w-8 text-muted-foreground" />
        <FileUp v-else class="h-8 w-8 text-muted-foreground" />
      </template>
      <span class="text-sm text-muted-foreground">
        {{ uploading ? "上传中…" : (promptByKind[kind] || "点击上传文件") }}
      </span>
      <span v-if="!uploading" class="text-xs text-muted-foreground/70">支持点击选择或拖拽文件到此处</span>
    </button>

    <p v-if="uploadError" class="text-xs text-destructive">{{ uploadError }}</p>
    <p v-else-if="hint" class="text-[11px] text-muted-foreground">{{ hint }}</p>

    <input ref="fileInput" type="file" class="hidden" :accept="accept" @change="onFilePicked" />
  </div>
</template>
