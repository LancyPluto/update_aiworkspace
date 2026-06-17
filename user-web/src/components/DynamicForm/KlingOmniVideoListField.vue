<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue"
import { Loader2, Plus, Trash2, UploadCloud, Video, X } from "lucide-vue-next"
import type { ToolField } from "@/api/types"
import { uploadToolFile } from "@/api/toolApi"
import { getApiOrigin } from "@/api/client"
import {
  KLING_OMNI_KEEP_SOUND_OPTIONS,
  KLING_OMNI_REFER_TYPE_OPTIONS,
  createEmptyKlingOmniVideoItem,
  klingOmniVideoAccept,
  klingOmniVideoMax,
  parseKlingOmniVideoEditorItems,
  serializeKlingOmniVideoItems,
  type KlingOmniKeepOriginalSound,
  type KlingOmniReferType,
  type KlingOmniVideoEditorItem,
  type KlingOmniVideoReference,
} from "@/utils/klingOmniVideoList"

const props = defineProps<{
  field: ToolField
  modelValue: unknown
  compact?: boolean
}>()

const emit = defineEmits<{
  "update:modelValue": [value: KlingOmniVideoReference[]]
}>()

const items = ref<KlingOmniVideoEditorItem[]>([])
const uploading = ref(false)
const syncing = ref(false)

const limit = computed(() => klingOmniVideoMax(props.field))
const accept = computed(() => klingOmniVideoAccept(props.field))

watch(
  () => props.modelValue,
  (value) => {
    if (syncing.value) return
    const parsed = parseKlingOmniVideoEditorItems(value, limit.value)
    items.value = parsed.map((item, index) => ({ ...item, id: items.value[index]?.id || item.id }))
  },
  { immediate: true },
)

function emitItems(nextItems: KlingOmniVideoEditorItem[]) {
  syncing.value = true
  items.value = nextItems
  emit("update:modelValue", serializeKlingOmniVideoItems(nextItems))
  nextTick(() => {
    syncing.value = false
  })
}

function addItem() {
  if (items.value.length >= limit.value) {
    window.alert(`最多添加 ${limit.value} 个参考视频`)
    return
  }
  items.value = [...items.value, createEmptyKlingOmniVideoItem()]
}

function removeItem(index: number) {
  emitItems(items.value.filter((_, itemIndex) => itemIndex !== index))
}

function updateItem(index: number, patch: Partial<KlingOmniVideoEditorItem>) {
  emitItems(items.value.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item)))
}

function previewUrl(url: string): string {
  const raw = url.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:") || raw.startsWith("blob:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

async function uploadVideo(index: number) {
  const input = document.createElement("input")
  input.type = "file"
  input.accept = accept.value
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file) return
    uploading.value = true
    try {
      const uploaded = await uploadToolFile(file)
      if (uploaded?.url) updateItem(index, { videoUrl: uploaded.url })
    } finally {
      uploading.value = false
    }
  }
  input.click()
}
</script>

<template>
  <div class="space-y-3" :class="compact ? '' : 'rounded-lg border border-white/10 bg-[#18181f] p-4'">
    <div class="flex items-center justify-between gap-2">
      <div class="text-sm text-[#c8c7d2]">
        {{ field.fieldName }}
        <span v-if="field.required" class="text-destructive"> *</span>
      </div>
      <button
        type="button"
        class="inline-flex items-center gap-1 rounded-md border border-white/10 px-2 py-1 text-xs text-white/70 transition hover:border-white/25 hover:text-white"
        :disabled="uploading || items.length >= limit"
        @click="addItem"
      >
        <Plus class="h-3.5 w-3.5" />
        添加视频
      </button>
    </div>

    <div v-if="items.length === 0" class="rounded-lg border border-dashed border-white/10 px-3 py-4 text-xs text-[#a8a6b5]">
      暂无参考视频，点击“添加视频”上传或填写视频 URL
    </div>

    <div v-for="(item, index) in items" :key="item.id" class="space-y-3 rounded-lg border border-white/10 bg-black/20 p-3">
      <div class="flex items-center justify-between gap-2">
        <span class="text-xs font-medium text-white/70">参考视频 {{ index + 1 }}</span>
        <button type="button" class="text-white/45 transition hover:text-destructive" @click="removeItem(index)">
          <Trash2 class="h-3.5 w-3.5" />
        </button>
      </div>

      <div class="flex flex-wrap gap-2">
        <button
          v-for="option in KLING_OMNI_REFER_TYPE_OPTIONS"
          :key="option.value"
          type="button"
          class="rounded-full border px-2.5 py-1 text-[11px] transition"
          :class="
            item.referType === option.value
              ? 'border-purple-500/30 bg-purple-500/10 text-purple-300'
              : 'border-white/10 text-white/45 hover:border-white/20 hover:text-white/75'
          "
          @click="updateItem(index, { referType: option.value as KlingOmniReferType })"
        >
          {{ option.label }}
        </button>
      </div>

      <div class="flex flex-wrap gap-2">
        <button
          v-for="option in KLING_OMNI_KEEP_SOUND_OPTIONS"
          :key="option.value"
          type="button"
          class="rounded-full border px-2.5 py-1 text-[11px] transition"
          :class="
            item.keepOriginalSound === option.value
              ? 'border-cyan-500/30 bg-cyan-500/10 text-cyan-200'
              : 'border-white/10 text-white/45 hover:border-white/20 hover:text-white/75'
          "
          @click="updateItem(index, { keepOriginalSound: option.value as KlingOmniKeepOriginalSound })"
        >
          {{ option.label }}
        </button>
      </div>

      <div class="flex flex-wrap items-start gap-2">
        <button
          type="button"
          class="inline-flex h-16 min-w-16 items-center justify-center gap-1 rounded-lg border border-dashed border-white/15 bg-[#07070c] px-3 text-xs text-white/45"
          :disabled="uploading"
          @click="uploadVideo(index)"
        >
          <Loader2 v-if="uploading" class="h-4 w-4 animate-spin" />
          <UploadCloud v-else class="h-4 w-4" />
          上传
        </button>
        <div class="min-w-0 flex-1 space-y-2">
          <input
            :value="item.videoUrl"
            placeholder="填写上传后的视频 URL"
            class="flex h-9 w-full rounded-md border border-white/10 bg-[#07070c] px-3 text-xs text-white"
            @input="updateItem(index, { videoUrl: ($event.target as HTMLInputElement).value })"
          />
          <div v-if="item.videoUrl" class="relative overflow-hidden rounded-lg border border-white/10 bg-black/30">
            <video :src="previewUrl(item.videoUrl)" class="max-h-32 w-full object-contain" controls playsinline preload="metadata" />
            <button
              type="button"
              class="absolute right-2 top-2 rounded-full bg-black/65 p-1 text-white"
              @click="updateItem(index, { videoUrl: '' })"
            >
              <X class="h-3 w-3" />
            </button>
          </div>
        </div>
      </div>

      <p v-if="item.referType === 'base'" class="text-[11px] text-amber-200/80">
        base 模式按参考视频时长输出，时长滑杆不生效，并按输入视频时长计费。
      </p>
      <p class="flex items-center gap-1 text-[11px] text-white/35">
        <Video class="h-3 w-3" />
        参考视频存在时音频参数会自动锁定为关闭。
      </p>
    </div>

    <p class="text-[11px] text-[#a8a6b5]">已添加 {{ items.length }}/{{ limit }} 个参考视频</p>
  </div>
</template>
