<script setup lang="ts">
import { RouterLink, useRouter } from "vue-router"
import {
  ArrowLeft,
  ArrowRight,
  Pencil,
  Star,
  Zap,
  CheckCircle2,
  ChevronRight,
  Heart,
  Share2,
  Clock,
  UploadCloud,
  ImageIcon,
  Sparkles,
  Loader2,
  Video,
  X,
  ExternalLink,
} from "lucide-vue-next"
import { computed, ref, onMounted, watch } from "vue"
import AppShell from "@/components/AppShell.vue"
import MediaComparisonSlider from "@/components/MediaComparisonSlider.vue"
import { fetchToolByCode } from "@/api/toolApi"
import { createTask } from "@/api/taskApi"
import { uploadChatFile } from "@/api/aiToolApi"
import type { ToolDetail, ToolField, ToolFieldOption } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { cleanToolDisplayText } from "@/utils/toolDisplayText"
import { formatToolCreditLabel, usesVariableWorkflowCredits } from "@/utils/toolCreditLabel"
import {
  buildImageTemplateTaskParams,
  compactOptionFields,
  initialImageTemplateOptions,
  isImageTemplateTool,
  primaryImageField,
} from "@/adapters/imageTemplateToolAdapter"
import {
  buildMediaTemplateTaskParams,
  compactMediaOptionFields,
  initialMediaTemplateOptions,
  isVideoTemplateTool,
  primaryMediaField,
  resolveToolKind,
} from "@/adapters/mediaTemplateToolAdapter"

const props = defineProps<{
  id: string
}>()

const auth = useAuthStore()
const router = useRouter()

const tool = ref<ToolDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const tab = ref<"intro" | "cases" | "input" | "output">("intro")
const uploadedImageUrl = ref("")
const uploadPreviewUrl = ref("")
const imageOptions = ref<Record<string, unknown>>({})
const uploadingImage = ref(false)
const imageSubmitLoading = ref(false)
const imageSubmitError = ref<string | null>(null)
const uploadedMediaUrl = ref("")
const mediaPreviewUrl = ref("")
const mediaPreviewIsVideo = ref(false)
const mediaOptions = ref<Record<string, unknown>>({})
const uploadingMedia = ref(false)
const mediaSubmitLoading = ref(false)
const mediaSubmitError = ref<string | null>(null)
const toolUseModalOpen = ref(false)
const toolUseModalExpanded = ref(false)
const uploadDragging = ref(false)

const title = computed(() => cleanToolDisplayText(tool.value?.toolName) || `工具 · ${props.id}`)
const isOffline = computed(() => tool.value?.status === "OFFLINE")

const imageTemplateMode = computed(() => isImageTemplateTool(tool.value))
const videoTemplateMode = computed(() => isVideoTemplateTool(tool.value))
const frontendStyle = computed(() => tool.value?.frontendStyle || null)
const toolKind = computed(() => resolveToolKind(tool.value))
const imageField = computed(() => (tool.value ? primaryImageField(tool.value) : null))
const compactFields = computed(() => (tool.value ? compactOptionFields(tool.value) : []))
const mediaField = computed(() => (tool.value ? primaryMediaField(tool.value, toolKind.value) : null))
const mediaCompactFields = computed(() => (tool.value ? compactMediaOptionFields(tool.value, toolKind.value) : []))
const heroTitle = computed(() => cleanToolDisplayText(frontendStyle.value?.heroTitle) || title.value)
const heroSubtitle = computed(() =>
  cleanToolDisplayText(frontendStyle.value?.heroSubtitle) ||
  cleanToolDisplayText(tool.value?.description) ||
  "上传一张图片，按预设效果快速生成同款结果。",
)
const beforeImageUrl = computed(() => frontendStyle.value?.comparisonOriginalUrl || tool.value?.coverUrl || "")
const afterImageUrl = computed(() =>
  frontendStyle.value?.comparisonEffectUrl ||
  frontendStyle.value?.demoThumbnails?.[0] ||
  tool.value?.coverUrl ||
  "",
)
const beforeVideoUrl = computed(() => frontendStyle.value?.beforeVideoUrl || tool.value?.coverUrl || "")
const afterVideoUrl = computed(() =>
  frontendStyle.value?.afterVideoUrl ||
  frontendStyle.value?.demoThumbnails?.[0] ||
  tool.value?.coverUrl ||
  "",
)
const mediaToolBadge = computed(() => (toolKind.value === "digitalHuman" ? "AI Digital Human" : "AI Video Tool"))
const imageUploadAccept = computed(() => uploadAccept(imageField.value, "image"))
const imageUploadHint = computed(() => uploadFormatHint(imageField.value, "image"))
const mediaUploadAccept = computed(() => uploadAccept(mediaField.value, toolKind.value === "image" ? "image" : "video"))
const mediaUploadHint = computed(() => uploadFormatHint(mediaField.value, toolKind.value === "image" ? "image" : "video"))
const modalPreviewUrl = computed(() => {
  if (imageTemplateMode.value) return afterImageUrl.value || beforeImageUrl.value || tool.value?.coverUrl || ""
  return afterVideoUrl.value || beforeVideoUrl.value || tool.value?.coverUrl || ""
})
const modalPreviewIsVideo = computed(() => videoTemplateMode.value && isVideoUrl(modalPreviewUrl.value))
const activeUploadTitle = computed(() => {
  if (imageTemplateMode.value) return imageField.value?.fieldName || "上传图片"
  if (toolKind.value === "digitalHuman") return mediaField.value?.fieldName || "上传人物素材"
  return mediaField.value?.fieldName || "上传视频"
})
const activeUploadAccept = computed(() => (imageTemplateMode.value ? imageUploadAccept.value : mediaUploadAccept.value))
const activeUploadHint = computed(() => (imageTemplateMode.value ? imageUploadHint.value : mediaUploadHint.value))
const activePreviewUrl = computed(() => (imageTemplateMode.value ? uploadPreviewUrl.value : mediaPreviewUrl.value))
const activePreviewIsVideo = computed(() => !imageTemplateMode.value && mediaPreviewIsVideo.value)
const activeSubmitError = computed(() => (imageTemplateMode.value ? imageSubmitError.value : mediaSubmitError.value))
const activeBusy = computed(() => imageTemplateMode.value ? uploadingImage.value || imageSubmitLoading.value : uploadingMedia.value || mediaSubmitLoading.value)
const activeUploading = computed(() => imageTemplateMode.value ? uploadingImage.value : uploadingMedia.value)
const activeSubmitLoading = computed(() => imageTemplateMode.value ? imageSubmitLoading.value : mediaSubmitLoading.value)
const activeUploadedUrl = computed(() => imageTemplateMode.value ? uploadedImageUrl.value : uploadedMediaUrl.value)
const activeCanSubmit = computed(() => !isOffline.value && !activeBusy.value && Boolean(activeUploadedUrl.value))

const useLink = computed(() => userRoutes.toolUse(props.id))

const toolTypeLabels: Record<string, string> = {
  TEXT_GENERATION: "文本生成",
  IMAGE_GENERATION: "文生图",
  IMAGE_TO_IMAGE: "图生图",
  IMAGE_UNDERSTANDING: "图片理解",
  SPEECH_TO_TEXT: "语音转文字",
  TEXT_TO_SPEECH: "文字转语音",
  MUSIC_GENERATION: "音乐生成",
  VIDEO_GENERATION: "视频生成",
  EMBEDDING: "Embedding",
  RERANK: "Rerank",
  AGENT: "Agent 编排",
}

const modalityLabels: Record<string, string> = {
  TEXT: "文本",
  IMAGE: "图片",
  AUDIO: "音频",
  VIDEO: "视频",
  JSON: "JSON",
  FILE: "文件",
  MULTIMODAL: "多模态",
}

function labelOf(labels: Record<string, string>, value?: string | null) {
  return value ? labels[value] || value : "-"
}

function normalizedMediaUrl(value?: string | null) {
  const raw = (value || "").trim()
  if (!raw) return ""
  if (/^(https?:)?\/\//i.test(raw) || raw.startsWith("data:")) return raw
  return raw.startsWith("/") ? raw : `/${raw}`
}

function isVideoUrl(value?: string | null) {
  return /(?:data:video\/|\.mp4(?:$|\?)|\.webm(?:$|\?)|\.mov(?:$|\?)|\.m4v(?:$|\?))/i.test(value || "")
}

function fieldConfig(field?: ToolField | null): Record<string, unknown> {
  if (!field?.optionsJson) return {}
  try {
    const parsed = JSON.parse(field.optionsJson)
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
  } catch {
    return {}
  }
}

function uploadAccept(field?: ToolField | null, fallback: "image" | "video" | "mixed" = "mixed") {
  const config = fieldConfig(field)
  const configuredAccept = config.accept || config.accepts || config.mimeTypes
  if (typeof configuredAccept === "string" && configuredAccept.trim()) return configuredAccept.trim()
  if (Array.isArray(configuredAccept)) return configuredAccept.map((item) => String(item).trim()).filter(Boolean).join(",")

  const formats = config.formats || config.extensions
  if (Array.isArray(formats) && formats.length) {
    return formats.map((item) => {
      const value = String(item).trim()
      if (!value) return ""
      return value.startsWith(".") || value.includes("/") ? value : `.${value.replace(/^\./, "")}`
    }).filter(Boolean).join(",")
  }

  const type = (field?.fieldType || "").toLowerCase()
  if (type.includes("image") || fallback === "image") return "image/jpeg,image/png,image/webp,image/gif"
  if (type.includes("video") || fallback === "video") return "video/mp4,video/webm,video/quicktime,video/x-m4v,.mov,.m4v"
  return "image/jpeg,image/png,image/webp,image/gif,video/mp4,video/webm,video/quicktime,video/x-m4v,.mov,.m4v"
}

function uploadFormatHint(field?: ToolField | null, fallback: "image" | "video" | "mixed" = "mixed") {
  const accept = uploadAccept(field, fallback)
  if (accept.includes("image/") && !accept.includes("video/")) return "支持 JPEG、PNG、WEBP、GIF"
  if (accept.includes("video/") && !accept.includes("image/")) return "支持 MP4、WebM、MOV"
  return "支持图片、GIF、MP4、WebM、MOV"
}

function fileMatchesAccept(file: File, accept: string) {
  const rules = accept.split(",").map((item) => item.trim().toLowerCase()).filter(Boolean)
  if (!rules.length) return true
  const fileType = file.type.toLowerCase()
  const fileName = file.name.toLowerCase()
  return rules.some((rule) => {
    if (rule.endsWith("/*")) return fileType.startsWith(rule.slice(0, -1))
    if (rule.startsWith(".")) return fileName.endsWith(rule)
    return fileType === rule
  })
}

function optionValue(option: ToolFieldOption | string) {
  return typeof option === "string" ? option : option.value
}

function optionLabel(option: ToolFieldOption | string) {
  return typeof option === "string" ? option : option.label
}

function updateImageOption(field: ToolField, value: unknown) {
  imageOptions.value = { ...imageOptions.value, [field.fieldKey]: value }
}

function updateMediaOption(field: ToolField, value: unknown) {
  mediaOptions.value = { ...mediaOptions.value, [field.fieldKey]: value }
}

function clientRequestId() {
  return globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function openUseTool() {
  if (!tool.value || isOffline.value) return
  if (imageTemplateMode.value || videoTemplateMode.value) {
    toolUseModalOpen.value = true
    return
  }
  await router.push(useLink.value)
}

function closeUseModal() {
  toolUseModalOpen.value = false
  toolUseModalExpanded.value = false
  uploadDragging.value = false
}

async function ensureAuthenticated() {
  if (auth.token) return true
  await router.push({ ...userRoutes.login, query: { redirect: router.currentRoute.value.fullPath } })
  return false
}

async function uploadImageFile(file: File) {
  if (!await ensureAuthenticated()) return
  if (!fileMatchesAccept(file, imageUploadAccept.value)) {
    imageSubmitError.value = `文件格式不符合该工具配置：${imageUploadHint.value}`
    return
  }

  imageSubmitError.value = null
  uploadingImage.value = true
  try {
    if (uploadPreviewUrl.value) URL.revokeObjectURL(uploadPreviewUrl.value)
    uploadPreviewUrl.value = URL.createObjectURL(file)
    const result = await uploadChatFile(file, { token: auth.token, toolId: tool.value?.toolCode })
    uploadedImageUrl.value = result.url
  } catch (e) {
    uploadedImageUrl.value = ""
    uploadPreviewUrl.value = ""
    imageSubmitError.value = (e as Error).message || "图片上传失败"
  } finally {
    uploadingImage.value = false
  }
}

async function uploadMediaFile(file: File) {
  if (!await ensureAuthenticated()) return
  if (!fileMatchesAccept(file, mediaUploadAccept.value)) {
    mediaSubmitError.value = `文件格式不符合该工具配置：${mediaUploadHint.value}`
    return
  }

  mediaSubmitError.value = null
  uploadingMedia.value = true
  try {
    if (mediaPreviewUrl.value) URL.revokeObjectURL(mediaPreviewUrl.value)
    mediaPreviewUrl.value = URL.createObjectURL(file)
    mediaPreviewIsVideo.value = file.type.startsWith("video/") || isVideoUrl(file.name)
    const result = await uploadChatFile(file, { token: auth.token, toolId: tool.value?.toolCode })
    uploadedMediaUrl.value = result.url
  } catch (e) {
    uploadedMediaUrl.value = ""
    mediaPreviewUrl.value = ""
    mediaPreviewIsVideo.value = false
    mediaSubmitError.value = (e as Error).message || "素材上传失败"
  } finally {
    uploadingMedia.value = false
  }
}

async function onImagePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ""
  if (!file) return
  await uploadImageFile(file)
}

async function submitImageTemplate() {
  if (!tool.value) return
  if (!auth.token) {
    await router.push({ ...userRoutes.login, query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  if (!uploadedImageUrl.value) {
    imageSubmitError.value = "请先上传一张图片"
    return
  }

  imageSubmitError.value = null
  imageSubmitLoading.value = true
  try {
    const task = await createTask(
      {
        toolCode: tool.value.toolCode,
        params: buildImageTemplateTaskParams(tool.value, uploadedImageUrl.value, imageOptions.value),
        clientRequestId: clientRequestId(),
      },
      { token: auth.token },
    )
    await router.push(userRoutes.taskStatus(String(task.taskId)))
  } catch (e) {
    imageSubmitError.value = (e as Error).message || "任务创建失败"
  } finally {
    imageSubmitLoading.value = false
  }
}

async function onMediaPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ""
  if (!file) return
  await uploadMediaFile(file)
}

async function onImageDropped(event: DragEvent) {
  uploadDragging.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) await uploadImageFile(file)
}

async function onMediaDropped(event: DragEvent) {
  uploadDragging.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) await uploadMediaFile(file)
}

async function onModalPicked(event: Event) {
  if (imageTemplateMode.value) {
    await onImagePicked(event)
    return
  }
  await onMediaPicked(event)
}

async function onModalDropped(event: DragEvent) {
  uploadDragging.value = false
  if (imageTemplateMode.value) {
    await onImageDropped(event)
    return
  }
  await onMediaDropped(event)
}

async function submitActiveTool() {
  if (imageTemplateMode.value) {
    await submitImageTemplate()
    return
  }
  await submitMediaTemplate()
}

async function submitMediaTemplate() {
  if (!tool.value) return
  if (!auth.token) {
    await router.push({ ...userRoutes.login, query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  if (!uploadedMediaUrl.value) {
    mediaSubmitError.value = "请先上传一段素材"
    return
  }

  mediaSubmitError.value = null
  mediaSubmitLoading.value = true
  try {
    const task = await createTask(
      {
        toolCode: tool.value.toolCode,
        params: buildMediaTemplateTaskParams(tool.value, uploadedMediaUrl.value, mediaOptions.value, toolKind.value),
        clientRequestId: clientRequestId(),
      },
      { token: auth.token },
    )
    await router.push(userRoutes.taskStatus(String(task.taskId)))
  } catch (e) {
    mediaSubmitError.value = (e as Error).message || "任务创建失败"
  } finally {
    mediaSubmitLoading.value = false
  }
}

watch(
  tool,
  (next) => {
    imageOptions.value = next ? initialImageTemplateOptions(next) : {}
    mediaOptions.value = next ? initialMediaTemplateOptions(next, resolveToolKind(next)) : {}
    uploadedImageUrl.value = ""
    uploadPreviewUrl.value = ""
    imageSubmitError.value = null
    uploadedMediaUrl.value = ""
    mediaPreviewUrl.value = ""
    mediaPreviewIsVideo.value = false
    mediaSubmitError.value = null
    toolUseModalOpen.value = false
    toolUseModalExpanded.value = false
    uploadDragging.value = false
  },
  { immediate: true },
)

onMounted(async () => {
  try {
    tool.value = await fetchToolByCode(props.id, { token: auth.token })
  } catch (e) {
    error.value = (e as Error).message || "加载工具详情失败"
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <AppShell title="工具详情" :description="title">
    <div class="px-6 py-6 max-w-6xl mx-auto space-y-6">
      <!-- 面包屑 -->
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="{ name: 'ToolList' }" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> AI 工具超市
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="text-foreground">{{ title }}</span>
      </nav>

      <!-- 加载中 -->
      <div v-if="loading" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">加载中…</span>
      </div>

      <!-- 错误 -->
      <div v-else-if="error" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
        <RouterLink :to="{ name: 'ToolList' }" class="mt-3 inline-block text-sm text-primary hover:underline">
          返回工具超市
        </RouterLink>
      </div>

      <!-- 工具详情 -->
      <template v-else-if="tool">
        <div
          v-if="isOffline"
          class="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-950 dark:text-amber-100"
        >
          该工具已下架，暂时无法使用。
        </div>

        <div v-if="imageTemplateMode" class="space-y-10">
          <section class="mx-auto grid max-w-5xl gap-8 lg:items-start">
            <div class="space-y-6">
              <div class="space-y-3">
                <div class="inline-flex items-center gap-2 rounded-full border border-primary/25 bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
                  <Sparkles class="h-3.5 w-3.5" />
                  AI Image Tool
                </div>
                <h1 class="max-w-3xl text-3xl font-semibold tracking-normal md:text-5xl">
                  {{ heroTitle }}
                </h1>
                <p class="max-w-2xl text-sm leading-7 text-muted-foreground md:text-base">
                  {{ heroSubtitle }}
                </p>
                <div class="flex flex-wrap items-center gap-3 pt-2">
                  <button
                    type="button"
                    class="inline-flex h-11 items-center justify-center rounded-full bg-primary px-6 text-sm font-medium text-primary-foreground shadow-lg shadow-primary/20 transition hover:scale-[1.02] hover:opacity-95 disabled:cursor-not-allowed disabled:opacity-60"
                    :disabled="isOffline"
                    @click="openUseTool"
                  >
                    立即使用
                    <ArrowRight class="ml-1.5 h-4 w-4" />
                  </button>
                  <span class="inline-flex items-center gap-1 rounded-full border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground">
                    <Zap class="h-3.5 w-3.5 text-warning" /> {{ formatToolCreditLabel(tool) }} / 次
                  </span>
                </div>
              </div>

              <MediaComparisonSlider
                v-if="beforeImageUrl && afterImageUrl"
                :before-src="normalizedMediaUrl(beforeImageUrl)"
                :after-src="normalizedMediaUrl(afterImageUrl)"
                before-label="Original"
                after-label="AI Result"
                aspect-ratio="4/3"
              />

              <div v-if="frontendStyle?.demoThumbnails?.length" class="flex gap-3 overflow-x-auto pb-1">
                <img
                  v-for="thumb in frontendStyle.demoThumbnails"
                  :key="thumb"
                  :src="normalizedMediaUrl(thumb)"
                  :alt="`${title} demo`"
                  class="h-20 w-28 shrink-0 rounded-md border border-border object-cover"
                />
              </div>
            </div>

            <aside v-if="false" class="rounded-lg border border-border bg-card p-5 shadow-sm">
              <div class="mb-4 flex items-center justify-between gap-3">
                <div>
                  <h2 class="text-base font-semibold">上传图片</h2>
                  <p class="mt-1 text-xs text-muted-foreground">
                    {{ imageField?.fieldName || "上传需要处理的原图" }}
                  </p>
                </div>
                <span class="inline-flex items-center gap-1 text-xs text-warning">
                  <Zap class="h-3.5 w-3.5" /> {{ formatToolCreditLabel(tool) }}
                </span>
              </div>

              <label
                class="flex min-h-48 cursor-pointer flex-col items-center justify-center overflow-hidden rounded-lg border border-dashed border-border bg-secondary/40 text-center transition hover:border-primary/60 hover:bg-primary/5"
              >
                <img
                  v-if="uploadPreviewUrl"
                  :src="uploadPreviewUrl"
                  alt="uploaded preview"
                  class="h-full max-h-64 w-full object-cover"
                />
                <template v-else>
                  <UploadCloud class="mb-3 h-9 w-9 text-primary" />
                  <span class="text-sm font-medium">选择图片</span>
                  <span class="mt-1 text-xs text-muted-foreground">支持 JPG、PNG、WEBP</span>
                </template>
                <input type="file" accept="image/jpeg,image/png,image/webp" class="hidden" @change="onImagePicked" />
              </label>

              <div v-if="compactFields.length" class="mt-5 space-y-4">
                <div v-for="field in compactFields" :key="field.fieldKey" class="space-y-2">
                  <div class="flex items-center justify-between gap-3">
                    <label class="text-sm font-medium">{{ field.fieldName }}</label>
                    <span v-if="field.fieldType === 'slider'" class="text-xs text-muted-foreground">
                      {{ imageOptions[field.fieldKey] }}
                    </span>
                  </div>

                  <div v-if="field.fieldType === 'radio'" class="grid grid-cols-2 gap-2">
                    <button
                      v-for="option in field.options || []"
                      :key="optionValue(option)"
                      type="button"
                      class="min-h-9 rounded-md border px-3 py-2 text-xs font-medium transition"
                      :class="imageOptions[field.fieldKey] === optionValue(option) ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-background text-muted-foreground hover:bg-secondary'"
                      @click="updateImageOption(field, optionValue(option))"
                    >
                      {{ optionLabel(option) }}
                    </button>
                  </div>

                  <select
                    v-else-if="field.fieldType === 'select'"
                    class="h-10 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring"
                    :value="String(imageOptions[field.fieldKey] ?? '')"
                    @change="updateImageOption(field, ($event.target as HTMLSelectElement).value)"
                  >
                    <option v-for="option in field.options || []" :key="optionValue(option)" :value="optionValue(option)">
                      {{ optionLabel(option) }}
                    </option>
                  </select>

                  <input
                    v-else-if="field.fieldType === 'slider'"
                    type="range"
                    min="0"
                    max="100"
                    class="w-full accent-primary"
                    :value="Number(imageOptions[field.fieldKey] ?? 50)"
                    @input="updateImageOption(field, ($event.target as HTMLInputElement).value)"
                  />

                  <label v-else-if="field.fieldType === 'checkbox'" class="inline-flex items-center gap-2 text-sm text-muted-foreground">
                    <input
                      type="checkbox"
                      class="h-4 w-4 accent-primary"
                      :checked="Boolean(imageOptions[field.fieldKey])"
                      @change="updateImageOption(field, ($event.target as HTMLInputElement).checked)"
                    />
                    {{ field.placeholder || "启用" }}
                  </label>

                  <input
                    v-else
                    :type="field.fieldType === 'number' ? 'number' : 'text'"
                    class="h-10 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring"
                    :placeholder="field.placeholder || ''"
                    :value="String(imageOptions[field.fieldKey] ?? '')"
                    @input="updateImageOption(field, ($event.target as HTMLInputElement).value)"
                  />
                </div>
              </div>

              <p v-if="imageSubmitError" class="mt-4 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
                {{ imageSubmitError }}
              </p>

              <button
                type="button"
                class="mt-5 inline-flex h-11 w-full items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
                :disabled="isOffline || uploadingImage || imageSubmitLoading || !uploadedImageUrl"
                @click="submitImageTemplate"
              >
                <Loader2 v-if="uploadingImage || imageSubmitLoading" class="mr-2 h-4 w-4 animate-spin" />
                {{ uploadingImage ? "上传中" : imageSubmitLoading ? "生成中" : "一键生成同款效果" }}
              </button>
            </aside>
          </section>

          <section class="grid gap-6 md:grid-cols-3">
            <div class="space-y-3">
              <h2 class="text-base font-semibold">适用场景</h2>
              <div class="space-y-2">
                <div
                  v-for="item in frontendStyle?.useCases?.length ? frontendStyle.useCases : ['电商商品图处理', '社媒内容创作', '品牌视觉统一']"
                  :key="item"
                  class="rounded-md border border-border bg-card px-4 py-3 text-sm text-muted-foreground"
                >
                  {{ item }}
                </div>
              </div>
            </div>
            <div class="space-y-3 md:col-span-2">
              <h2 class="text-base font-semibold">使用步骤</h2>
              <div class="grid gap-3 sm:grid-cols-3">
                <div
                  v-for="(step, index) in frontendStyle?.steps?.length ? frontendStyle.steps : ['上传原图', '选择效果参数', '生成并下载结果']"
                  :key="step"
                  class="rounded-md border border-border bg-card p-4"
                >
                  <span class="flex h-7 w-7 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">
                    {{ index + 1 }}
                  </span>
                  <p class="mt-3 text-sm text-muted-foreground">{{ step }}</p>
                </div>
              </div>
            </div>
          </section>
        </div>

        <div v-else-if="videoTemplateMode" class="space-y-10">
          <section class="mx-auto grid max-w-5xl gap-8 lg:items-start">
            <div class="space-y-6">
              <div class="space-y-3">
                <div class="inline-flex items-center gap-2 rounded-full border border-primary/25 bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
                  <Sparkles class="h-3.5 w-3.5" />
                  {{ mediaToolBadge }}
                </div>
                <h1 class="max-w-3xl text-3xl font-semibold tracking-normal md:text-5xl">
                  {{ heroTitle }}
                </h1>
                <p class="max-w-2xl text-sm leading-7 text-muted-foreground md:text-base">
                  {{ heroSubtitle }}
                </p>
                <div class="flex flex-wrap items-center gap-3 pt-2">
                  <button
                    type="button"
                    class="inline-flex h-11 items-center justify-center rounded-full bg-primary px-6 text-sm font-medium text-primary-foreground shadow-lg shadow-primary/20 transition hover:scale-[1.02] hover:opacity-95 disabled:cursor-not-allowed disabled:opacity-60"
                    :disabled="isOffline"
                    @click="openUseTool"
                  >
                    立即使用
                    <ArrowRight class="ml-1.5 h-4 w-4" />
                  </button>
                  <span class="inline-flex items-center gap-1 rounded-full border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground">
                    <Zap class="h-3.5 w-3.5 text-warning" /> {{ formatToolCreditLabel(tool) }} / 次
                  </span>
                </div>
              </div>

              <MediaComparisonSlider
                v-if="beforeVideoUrl && afterVideoUrl"
                :before-src="normalizedMediaUrl(beforeVideoUrl)"
                :after-src="normalizedMediaUrl(afterVideoUrl)"
                :is-video="isVideoUrl(beforeVideoUrl) || isVideoUrl(afterVideoUrl)"
                before-label="Source"
                after-label="AI Result"
                aspect-ratio="16/9"
              />
            </div>

            <aside v-if="false" class="rounded-lg border border-border bg-card p-5 shadow-sm">
              <div class="mb-4 flex items-center justify-between gap-3">
                <div>
                  <h2 class="text-base font-semibold">{{ toolKind === 'digitalHuman' ? '上传人物素材' : '上传视频素材' }}</h2>
                  <p class="mt-1 text-xs text-muted-foreground">
                    {{ mediaField?.fieldName || "上传需要处理的原始素材" }}
                  </p>
                </div>
                <span class="inline-flex items-center gap-1 text-xs text-warning">
                  <Zap class="h-3.5 w-3.5" /> {{ formatToolCreditLabel(tool) }}
                </span>
              </div>

              <label
                class="flex min-h-48 cursor-pointer flex-col items-center justify-center overflow-hidden rounded-lg border border-dashed border-border bg-secondary/40 text-center transition hover:border-primary/60 hover:bg-primary/5"
              >
                <video
                  v-if="mediaPreviewUrl && mediaPreviewIsVideo"
                  :src="mediaPreviewUrl"
                  class="h-full max-h-64 w-full object-cover"
                  muted
                  loop
                  playsinline
                  controls
                />
                <img
                  v-else-if="mediaPreviewUrl"
                  :src="mediaPreviewUrl"
                  alt="uploaded media preview"
                  class="h-full max-h-64 w-full object-cover"
                />
                <template v-else>
                  <UploadCloud class="mb-3 h-9 w-9 text-primary" />
                  <span class="text-sm font-medium">选择素材</span>
                  <span class="mt-1 text-xs text-muted-foreground">支持 MP4、WebM、MOV、JPG、PNG</span>
                </template>
                <input type="file" accept="video/mp4,video/webm,video/quicktime,video/x-m4v,image/jpeg,image/png,image/webp" class="hidden" @change="onMediaPicked" />
              </label>

              <div v-if="mediaCompactFields.length" class="mt-5 space-y-4">
                <div v-for="field in mediaCompactFields" :key="field.fieldKey" class="space-y-2">
                  <div class="flex items-center justify-between gap-3">
                    <label class="text-sm font-medium">{{ field.fieldName }}</label>
                    <span v-if="field.fieldType === 'slider'" class="text-xs text-muted-foreground">
                      {{ mediaOptions[field.fieldKey] }}
                    </span>
                  </div>

                  <div v-if="field.fieldType === 'radio'" class="grid grid-cols-2 gap-2">
                    <button
                      v-for="option in field.options || []"
                      :key="optionValue(option)"
                      type="button"
                      class="min-h-9 rounded-md border px-3 py-2 text-xs font-medium transition"
                      :class="mediaOptions[field.fieldKey] === optionValue(option) ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-background text-muted-foreground hover:bg-secondary'"
                      @click="updateMediaOption(field, optionValue(option))"
                    >
                      {{ optionLabel(option) }}
                    </button>
                  </div>

                  <select
                    v-else-if="field.fieldType === 'select'"
                    class="h-10 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring"
                    :value="String(mediaOptions[field.fieldKey] ?? '')"
                    @change="updateMediaOption(field, ($event.target as HTMLSelectElement).value)"
                  >
                    <option v-for="option in field.options || []" :key="optionValue(option)" :value="optionValue(option)">
                      {{ optionLabel(option) }}
                    </option>
                  </select>

                  <input
                    v-else-if="field.fieldType === 'slider'"
                    type="range"
                    min="0"
                    max="100"
                    class="w-full accent-primary"
                    :value="Number(mediaOptions[field.fieldKey] ?? 50)"
                    @input="updateMediaOption(field, ($event.target as HTMLInputElement).value)"
                  />

                  <label v-else-if="field.fieldType === 'checkbox'" class="inline-flex items-center gap-2 text-sm text-muted-foreground">
                    <input
                      type="checkbox"
                      class="h-4 w-4 accent-primary"
                      :checked="Boolean(mediaOptions[field.fieldKey])"
                      @change="updateMediaOption(field, ($event.target as HTMLInputElement).checked)"
                    />
                    {{ field.placeholder || "启用" }}
                  </label>

                  <textarea
                    v-else-if="field.fieldType === 'textarea'"
                    class="min-h-24 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
                    :placeholder="field.placeholder || ''"
                    :value="String(mediaOptions[field.fieldKey] ?? '')"
                    @input="updateMediaOption(field, ($event.target as HTMLTextAreaElement).value)"
                  />

                  <input
                    v-else
                    :type="field.fieldType === 'number' ? 'number' : 'text'"
                    class="h-10 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring"
                    :placeholder="field.placeholder || ''"
                    :value="String(mediaOptions[field.fieldKey] ?? '')"
                    @input="updateMediaOption(field, ($event.target as HTMLInputElement).value)"
                  />
                </div>
              </div>

              <p v-if="mediaSubmitError" class="mt-4 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
                {{ mediaSubmitError }}
              </p>

              <button
                type="button"
                class="mt-5 inline-flex h-11 w-full items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
                :disabled="isOffline || uploadingMedia || mediaSubmitLoading || !uploadedMediaUrl"
                @click="submitMediaTemplate"
              >
                <Loader2 v-if="uploadingMedia || mediaSubmitLoading" class="mr-2 h-4 w-4 animate-spin" />
                {{ uploadingMedia ? "上传中" : mediaSubmitLoading ? "生成中" : "一键生成同款效果" }}
              </button>
            </aside>
          </section>
        </div>

        <div v-if="!imageTemplateMode && !videoTemplateMode" class="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div class="flex flex-col md:flex-row md:items-start gap-5">
            <img
              v-if="tool.coverUrl"
              :src="tool.coverUrl"
              :alt="title"
              class="h-16 w-16 shrink-0 rounded-2xl object-cover ring-1 ring-border"
            />
            <div
              v-else
              class="flex h-16 w-16 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-primary to-chart-2 text-primary-foreground"
            >
              <Pencil class="h-8 w-8" />
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <h2 class="text-xl font-semibold">{{ title }}</h2>
                <span
                  v-if="(tool.estimatedCreditCost ?? 0) > 50"
                  class="rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium text-destructive"
                >
                  HOT
                </span>
                <span class="rounded bg-secondary px-1.5 py-0.5 text-[10px] text-muted-foreground">
                  {{ labelOf(modalityLabels, tool.outputModality || 'TEXT') }}
                </span>
                <span class="rounded bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">
                  {{ labelOf(toolTypeLabels, tool.toolType || 'TEXT_GENERATION') }}
                </span>
              </div>
              <p class="mt-2 text-sm text-muted-foreground leading-relaxed">
                {{ cleanToolDisplayText(tool.description) || '暂无描述' }}
              </p>
              <div class="mt-3 flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
                <span class="inline-flex items-center gap-1">
                  <Star class="h-3.5 w-3.5 fill-warning text-warning" /> 4.9 / 5.0
                </span>
                <span>
                  {{ labelOf(modalityLabels, tool.inputModality || 'TEXT') }}
                  →
                  {{ labelOf(modalityLabels, tool.outputModality || 'TEXT') }}
                </span>
              </div>
            </div>
            <div class="flex flex-col items-stretch md:items-end gap-2 shrink-0">
              <div class="flex items-center gap-2 text-sm">
                <Zap class="h-4 w-4 text-warning" />
                <span class="text-2xl font-semibold">{{ usesVariableWorkflowCredits(tool) ? "不详" : (tool.estimatedCreditCost ?? 0) }}</span>
                <span class="text-xs text-muted-foreground">算力 / 次</span>
              </div>
              <RouterLink
                v-if="!isOffline"
                :to="useLink"
                class="inline-flex h-11 items-center justify-center rounded-md bg-primary px-8 text-sm font-medium text-primary-foreground hover:opacity-90"
              >
                开始使用
                <ArrowRight class="ml-1.5 h-4 w-4" />
              </RouterLink>
              <span
                v-else
                class="inline-flex h-11 cursor-not-allowed items-center justify-center rounded-md border border-border bg-muted px-8 text-sm font-medium text-muted-foreground"
              >
                已下架
              </span>
              <div class="flex items-center gap-1 justify-end">
                <button
                  type="button"
                  class="inline-flex h-8 items-center rounded-md px-2 text-xs text-muted-foreground hover:bg-secondary"
                >
                  <Heart class="h-3.5 w-3.5 mr-1" /> 收藏
                </button>
                <button
                  type="button"
                  class="inline-flex h-8 items-center rounded-md px-2 text-xs text-muted-foreground hover:bg-secondary"
                >
                  <Share2 class="h-3.5 w-3.5 mr-1" /> 分享
                </button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="!imageTemplateMode && !videoTemplateMode" class="grid gap-6 lg:grid-cols-3">
          <div class="lg:col-span-2 space-y-6">
            <div class="rounded-xl border border-border bg-card p-1 shadow-sm">
              <div class="flex flex-wrap gap-1">
                <button
                  v-for="t in (['intro', 'cases', 'input', 'output'] as const)"
                  :key="t"
                  type="button"
                  class="rounded-md px-3 py-2 text-xs font-medium transition"
                  :class="tab === t ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-secondary'"
                  @click="tab = t"
                >
                  {{
                    t === "intro"
                      ? "工具介绍"
                      : t === "cases"
                        ? "适用场景"
                        : t === "input"
                          ? "输入说明"
                          : "输出示例"
                  }}
                </button>
              </div>
            </div>

            <div v-show="tab === 'intro'" class="rounded-xl border border-border bg-card p-6 space-y-4 text-sm">
              <h4 class="text-sm font-semibold">核心能力</h4>
              <ul class="space-y-2 text-muted-foreground">
                <li
                  v-for="c in ['多平台适配', '智能生成', '一键输出']"
                  :key="c"
                  class="flex gap-2"
                >
                  <CheckCircle2 class="h-4 w-4 shrink-0 text-success mt-0.5" />
                  <span>{{ c }}</span>
                </li>
              </ul>
            </div>

            <div v-show="tab === 'cases'" class="rounded-xl border border-border bg-card p-6">
              <p class="text-sm text-muted-foreground">适用多种业务场景</p>
            </div>

            <div v-show="tab === 'input'" class="rounded-xl border border-border bg-card overflow-hidden">
              <table class="w-full text-sm">
                <thead class="bg-secondary/60">
                  <tr class="text-xs text-muted-foreground">
                    <th class="px-4 py-2.5 text-left font-medium">字段</th>
                    <th class="px-4 py-2.5 text-left font-medium">类型</th>
                    <th class="px-4 py-2.5 text-left font-medium">必填</th>
                    <th class="px-4 py-2.5 text-left font-medium">说明</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-border">
                  <tr v-for="f in tool.fields" :key="f.fieldKey">
                    <td class="px-4 py-3 font-medium">{{ f.fieldName }}</td>
                    <td class="px-4 py-3 text-muted-foreground">{{ f.fieldType }}</td>
                    <td class="px-4 py-3">
                      <span
                        v-if="f.required"
                        class="rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] text-destructive"
                      >必填</span>
                      <span v-else class="text-xs text-muted-foreground">可选</span>
                    </td>
                    <td class="px-4 py-3 text-muted-foreground">{{ f.placeholder || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-show="tab === 'output'" class="rounded-xl border border-border bg-card p-6 space-y-4">
              <div class="rounded-lg border border-border bg-secondary/40 p-4">
                <p class="text-xs text-muted-foreground">AI 将生成符合要求的结果内容</p>
              </div>
            </div>
          </div>

          <div class="space-y-6">
            <div class="rounded-xl border border-border bg-card p-5 shadow-sm">
              <h3 class="text-sm font-semibold mb-4 flex items-center gap-2">
                <Clock class="h-4 w-4 text-primary" /> 使用步骤
              </h3>
              <ol class="space-y-4">
                <li v-for="(s, i) in [
                  { title: '填写参数', desc: '填写工具所需的输入参数' },
                  { title: 'AI 生成', desc: '系统自动调用 AI 模型处理' },
                  { title: '查看结果', desc: '在任务结果页查看输出内容' },
                ]" :key="s.title" class="flex gap-3">
                  <span
                    class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary"
                  >
                    {{ i + 1 }}
                  </span>
                  <div>
                    <p class="text-sm font-medium">{{ s.title }}</p>
                    <p class="text-xs text-muted-foreground mt-0.5">{{ s.desc }}</p>
                  </div>
                </li>
              </ol>
            </div>
          </div>
        </div>
      </template>
    </div>
  </AppShell>

  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="toolUseModalOpen && tool"
        class="fixed inset-0 z-[90] bg-black/80 p-3 text-white backdrop-blur-sm md:p-5"
        @click.self="closeUseModal"
        @keydown.esc.window="closeUseModal"
      >
        <Transition
          appear
          enter-active-class="transition duration-200 ease-out"
          enter-from-class="translate-y-4 scale-[0.98] opacity-0"
          enter-to-class="translate-y-0 scale-100 opacity-100"
          leave-active-class="transition duration-150 ease-in"
          leave-from-class="translate-y-0 scale-100 opacity-100"
          leave-to-class="translate-y-3 scale-[0.98] opacity-0"
        >
          <div
            class="mx-auto flex h-full flex-col overflow-hidden rounded-2xl border border-white/10 bg-[#09090d] shadow-2xl md:flex-row"
            :class="toolUseModalExpanded ? 'max-w-none' : 'max-w-[1500px]'"
          >
            <aside class="flex w-full flex-col border-b border-white/10 bg-[#17171d] md:max-w-[430px] md:border-b-0 md:border-r">
              <div class="min-h-0 flex-1 overflow-y-auto p-5">
                <h2 class="text-lg font-semibold text-white">{{ title }}</h2>

                <div class="mt-6 space-y-2">
                  <p class="text-sm font-medium text-zinc-200">
                    {{ activeUploadTitle }}<span class="text-primary">*</span>
                  </p>
                  <label
                    class="flex min-h-44 cursor-pointer flex-col items-center justify-center overflow-hidden rounded-lg border border-dashed p-4 text-center transition"
                    :class="uploadDragging ? 'border-primary bg-primary/10' : 'border-white/10 bg-white/5 hover:border-primary/70 hover:bg-primary/5'"
                    @dragenter.prevent="uploadDragging = true"
                    @dragover.prevent="uploadDragging = true"
                    @dragleave.prevent="uploadDragging = false"
                    @drop.prevent="onModalDropped"
                  >
                    <video
                      v-if="activePreviewUrl && activePreviewIsVideo"
                      :src="activePreviewUrl"
                      class="h-full max-h-56 w-full rounded-md object-cover"
                      muted
                      loop
                      playsinline
                      controls
                    />
                    <img
                      v-else-if="activePreviewUrl"
                      :src="activePreviewUrl"
                      alt="uploaded media preview"
                      class="h-full max-h-56 w-full rounded-md object-cover"
                    />
                    <template v-else>
                      <UploadCloud class="mb-3 h-10 w-10 text-zinc-400" />
                      <span class="text-sm font-medium text-zinc-200">点击上传或拖拽到这里</span>
                      <span class="mt-1 text-xs text-zinc-500">{{ activeUploadHint }}</span>
                    </template>
                    <input
                      type="file"
                      class="hidden"
                      :accept="activeUploadAccept"
                      @change="onModalPicked"
                    />
                  </label>
                  <p class="text-xs leading-5 text-zinc-500">
                    文件格式按管理端字段配置限制；未配置时使用当前工具类型的默认格式。
                  </p>
                </div>

                <p v-if="activeSubmitError" class="mt-4 rounded-md border border-primary/30 bg-primary/10 px-3 py-2 text-xs text-primary">
                  {{ activeSubmitError }}
                </p>
              </div>

              <div class="border-t border-white/10 bg-white/[0.03] p-5">
                <div class="mb-4 flex items-center justify-between text-sm">
                  <span class="inline-flex items-center gap-2 text-zinc-300">
                    <Zap class="h-4 w-4 text-primary" /> 所需额度
                  </span>
                  <span class="font-medium text-white">{{ usesVariableWorkflowCredits(tool) ? "按实际用量结算" : `${tool.estimatedCreditCost ?? 0} 额度` }}</span>
                </div>
                <button
                  type="button"
                  class="inline-flex h-12 w-full items-center justify-center rounded-md bg-primary px-4 text-sm font-semibold text-primary-foreground transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
                  :disabled="!activeCanSubmit"
                  @click="submitActiveTool"
                >
                  <Loader2 v-if="activeBusy" class="mr-2 h-4 w-4 animate-spin" />
                  {{ activeUploading ? "上传中" : activeSubmitLoading ? "生成中" : "生成" }}
                </button>
              </div>
            </aside>

            <section class="relative flex min-h-0 flex-1 items-center justify-center bg-[#0b0b10] p-6 md:p-10">
              <div class="absolute right-5 top-5 z-10 flex items-center gap-3">
                <button
                  type="button"
                  class="inline-flex h-10 w-10 items-center justify-center rounded-full bg-white/10 text-zinc-200 transition hover:bg-white/15"
                  aria-label="切换宽屏"
                  @click="toolUseModalExpanded = !toolUseModalExpanded"
                >
                  <ExternalLink class="h-5 w-5" />
                </button>
                <button
                  type="button"
                  class="inline-flex h-10 w-10 items-center justify-center rounded-full bg-white/10 text-zinc-200 transition hover:bg-white/15"
                  aria-label="关闭"
                  @click="closeUseModal"
                >
                  <X class="h-5 w-5" />
                </button>
              </div>

              <div class="w-full max-w-3xl text-center">
                <h2 class="text-3xl font-semibold tracking-normal text-white md:text-4xl">{{ heroTitle }}</h2>
                <div class="mt-8 overflow-hidden rounded-2xl border border-white/10 bg-white/[0.04] shadow-2xl">
                  <video
                    v-if="modalPreviewUrl && modalPreviewIsVideo"
                    :src="normalizedMediaUrl(modalPreviewUrl)"
                    class="aspect-video w-full object-cover"
                    muted
                    loop
                    playsinline
                    autoplay
                    controls
                  />
                  <img
                    v-else-if="modalPreviewUrl"
                    :src="normalizedMediaUrl(modalPreviewUrl)"
                    :alt="heroTitle"
                    class="aspect-video w-full object-cover"
                  />
                  <div v-else class="flex aspect-video items-center justify-center text-sm text-zinc-500">
                    <Sparkles class="mr-2 h-5 w-5" /> 暂未配置效果预览
                  </div>
                </div>
                <p v-if="heroSubtitle" class="mx-auto mt-5 max-w-2xl text-sm leading-6 text-zinc-400">
                  {{ heroSubtitle }}
                </p>
              </div>
            </section>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>
