<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { Loader2, Plus, Trash2, Video, X } from "lucide-vue-next"
import type { ToolField } from "@/api/types"
import { uploadToolFile } from "@/api/toolApi"
import { getApiOrigin } from "@/api/client"
import {
  SUBJECT_ELEMENT_MODE_OPTIONS,
  createEmptySubjectElementItem,
  parseSubjectElementEditorItems,
  serializeSubjectElementItems,
  subjectElementMax,
  type SubjectElementEditorItem,
  type SubjectElementMode,
} from "@/utils/subjectElementList"
import SubjectPickerModal from "@/components/DynamicForm/SubjectPickerModal.vue"
import type { GenerationSubject } from "@/api/subjectApi"

const props = defineProps<{
  field: ToolField
  modelValue: unknown
  compact?: boolean
}>()

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>[]]
}>()

const uploading = ref(false)
const items = ref<SubjectElementEditorItem[]>([])
const syncing = ref(false)
const pickerOpen = ref(false)
const pickerIndex = ref<number | null>(null)

const limit = computed(() => subjectElementMax(props.field))

watch(
  () => props.modelValue,
  (value) => {
    if (syncing.value) return
    const parsed = parseSubjectElementEditorItems(value, limit.value)
    items.value = parsed.map((item, index) => ({
      ...item,
      id: items.value[index]?.id || item.id,
    }))
  },
  { immediate: true },
)

function syncValue(nextItems: SubjectElementEditorItem[]) {
  syncing.value = true
  items.value = nextItems
  emit("update:modelValue", serializeSubjectElementItems(nextItems))
  syncing.value = false
}

function addItem() {
  if (items.value.length >= limit.value) {
    window.alert(`最多添加 ${limit.value} 个主体`)
    return
  }
  syncValue([...items.value, createEmptySubjectElementItem()])
}

function removeItem(index: number) {
  syncValue(items.value.filter((_, itemIndex) => itemIndex !== index))
}

function updateItem(index: number, patch: Partial<SubjectElementEditorItem>) {
  const next = items.value.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item))
  syncValue(next)
}

function setMode(index: number, mode: SubjectElementMode) {
  updateItem(index, {
    mode,
    elementId: "",
    librarySubjectCode: "",
    libraryDisplayName: "",
    upstreamElementId: "",
    frontalImage: "",
    referImages: [],
    referVideo: "",
  })
}

function openPicker(index: number) {
  pickerIndex.value = index
  pickerOpen.value = true
}

function handlePickerSelect(subject: GenerationSubject) {
  if (pickerIndex.value === null) return
  updateItem(pickerIndex.value, {
    mode: "library_ref",
    librarySubjectCode: subject.subjectCode,
    libraryDisplayName: subject.displayName,
    upstreamElementId: subject.upstreamElementId || "",
    elementId: subject.upstreamElementId || "",
  })
  pickerOpen.value = false
  pickerIndex.value = null
}

function previewUrl(url: string): string {
  const raw = url.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:") || raw.startsWith("blob:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

async function uploadSingle(accept: string): Promise<string | null> {
  return new Promise((resolve) => {
    const input = document.createElement("input")
    input.type = "file"
    input.accept = accept
    input.onchange = async () => {
      const file = input.files?.[0]
      if (!file) {
        resolve(null)
        return
      }
      uploading.value = true
      try {
        const uploaded = await uploadToolFile(file)
        resolve(uploaded?.url || null)
      } finally {
        uploading.value = false
      }
    }
    input.click()
  })
}

async function uploadImageForItem(index: number, target: "frontal" | "refer") {
  const url = await uploadSingle("image/*")
  if (!url) return
  const item = items.value[index]
  if (!item) return
  if (target === "frontal") {
    updateItem(index, { frontalImage: url })
    return
  }
  const nextRefer = [...item.referImages, url].slice(0, 4)
  updateItem(index, { referImages: nextRefer })
}

async function uploadVideoForItem(index: number) {
  const url = await uploadSingle("video/*")
  if (!url) return
  updateItem(index, { referVideo: url })
}

function removeReferImage(index: number, referIndex: number) {
  const item = items.value[index]
  if (!item) return
  updateItem(index, { referImages: item.referImages.filter((_, itemIndex) => itemIndex !== referIndex) })
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
        添加主体
      </button>
    </div>

    <div v-if="items.length === 0" class="rounded-lg border border-dashed border-white/10 px-3 py-4 text-xs text-[#a8a6b5]">
      暂无主体，点击「添加主体」创建参考对象
    </div>

    <div
      v-for="(item, index) in items"
      :key="item.id"
      class="space-y-3 rounded-lg border border-white/10 bg-black/20 p-3"
    >
      <div class="flex items-center justify-between gap-2">
        <span class="text-xs font-medium text-white/70">主体 {{ index + 1 }}</span>
        <button type="button" class="text-white/45 transition hover:text-destructive" @click="removeItem(index)">
          <Trash2 class="h-3.5 w-3.5" />
        </button>
      </div>

      <div class="flex flex-wrap gap-2">
        <button
          v-for="option in SUBJECT_ELEMENT_MODE_OPTIONS"
          :key="option.value"
          type="button"
          class="rounded-full border px-2.5 py-1 text-[11px] transition"
          :class="
            item.mode === option.value
              ? 'border-purple-500/30 bg-purple-500/10 text-purple-300'
              : 'border-white/10 text-white/45 hover:border-white/20 hover:text-white/75'
          "
          @click="setMode(index, option.value)"
        >
          {{ option.label }}
        </button>
      </div>

      <div v-if="item.mode === 'element_id'" class="space-y-1">
        <label class="text-[11px] text-white/45">主体 ID</label>
        <input
          :value="item.elementId"
          placeholder="输入可灵主体 element_id"
          class="flex h-9 w-full rounded-md border border-white/10 bg-[#07070c] px-3 text-xs text-white"
          @input="updateItem(index, { elementId: ($event.target as HTMLInputElement).value })"
        />
      </div>

      <div v-else-if="item.mode === 'library_ref'" class="space-y-2">
        <button
          type="button"
          class="inline-flex items-center gap-2 rounded-lg border border-white/10 px-3 py-2 text-xs text-white/80 hover:border-violet-500/30 hover:bg-violet-500/10"
          @click="openPicker(index)"
        >
          {{ item.libraryDisplayName || "选择主体库条目" }}
        </button>
        <p v-if="item.upstreamElementId" class="text-[11px] text-white/45">element_id: {{ item.upstreamElementId }}</p>
      </div>

      <div v-else-if="item.mode === 'image_element'" class="space-y-3">
        <div class="space-y-1">
          <label class="text-[11px] text-white/45">正面图</label>
          <div class="flex flex-wrap items-center gap-2">
            <button
              type="button"
              class="inline-flex h-16 w-16 items-center justify-center rounded-lg border border-dashed border-white/15 bg-[#07070c] text-white/45"
              :disabled="uploading"
              @click="uploadImageForItem(index, 'frontal')"
            >
              <Loader2 v-if="uploading" class="h-4 w-4 animate-spin" />
              <Plus v-else class="h-4 w-4" />
            </button>
            <div v-if="item.frontalImage" class="relative h-16 w-16 overflow-hidden rounded-lg border border-white/10">
              <img :src="previewUrl(item.frontalImage)" alt="正面图" class="h-full w-full object-cover" />
              <button
                type="button"
                class="absolute right-1 top-1 rounded-full bg-black/65 p-1 text-white"
                @click="updateItem(index, { frontalImage: '' })"
              >
                <X class="h-3 w-3" />
              </button>
            </div>
          </div>
        </div>

        <div class="space-y-1">
          <label class="text-[11px] text-white/45">参考图（最多 4 张）</label>
          <div class="flex flex-wrap gap-2">
            <button
              type="button"
              class="inline-flex h-14 w-14 items-center justify-center rounded-lg border border-dashed border-white/15 bg-[#07070c] text-white/45"
              :disabled="uploading || item.referImages.length >= 4"
              @click="uploadImageForItem(index, 'refer')"
            >
              <Plus class="h-4 w-4" />
            </button>
            <div
              v-for="(url, referIndex) in item.referImages"
              :key="`${url}-${referIndex}`"
              class="relative h-14 w-14 overflow-hidden rounded-lg border border-white/10"
            >
              <img :src="previewUrl(url)" alt="参考图" class="h-full w-full object-cover" />
              <button
                type="button"
                class="absolute right-1 top-1 rounded-full bg-black/65 p-1 text-white"
                @click="removeReferImage(index, referIndex)"
              >
                <X class="h-3 w-3" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="space-y-1">
        <label class="text-[11px] text-white/45">参考视频</label>
        <div class="flex flex-wrap items-center gap-2">
          <button
            type="button"
            class="inline-flex h-16 min-w-16 items-center justify-center gap-1 rounded-lg border border-dashed border-white/15 bg-[#07070c] px-3 text-xs text-white/45"
            :disabled="uploading"
            @click="uploadVideoForItem(index)"
          >
            <Loader2 v-if="uploading" class="h-4 w-4 animate-spin" />
            <Video v-else class="h-4 w-4" />
            上传视频
          </button>
          <div v-if="item.referVideo" class="relative min-w-0 flex-1 rounded-lg border border-white/10 bg-black/30 p-2">
            <video :src="previewUrl(item.referVideo)" class="max-h-24 w-full rounded object-contain" controls playsinline preload="metadata" />
            <button
              type="button"
              class="absolute right-2 top-2 rounded-full bg-black/65 p-1 text-white"
              @click="updateItem(index, { referVideo: '' })"
            >
              <X class="h-3 w-3" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <p class="text-[11px] text-[#a8a6b5]">已添加 {{ items.length }}/{{ limit }} 个主体</p>
    <SubjectPickerModal :open="pickerOpen" @close="pickerOpen = false" @select="handlePickerSelect" />
  </div>
</template>
