<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue"
import { BookOpen, FileUp, ImageIcon, Music, Plus, RefreshCw, Video, X } from "lucide-vue-next"
import { getApiOrigin } from "@/api/client"
import type { ToolField } from "@/api/types"
import { uploadToolFile } from "@/api/toolApi"
import {
  defaultFieldValue,
  fieldOptionsFromMeta,
  filterFieldsForUi,
  groupVisibleFields,
  isCustomModeAdvanced,
  isFieldVisible,
  parseFieldMeta,
  resolveMaxLength,
} from "@/utils/fieldUiMeta"
import {
  isMediaListField,
  mediaListAccept,
  mediaListLibraryEnabled,
  mediaListLibraryKind,
  mediaListMax,
  mediaListMin,
  mediaListUnitLabel,
  normalizeMediaListValues,
  parseMediaListValue,
} from "@/utils/mediaListField"
import { validateSubjectElementItems, parseSubjectElementEditorItems, subjectElementMax } from "@/utils/subjectElementList"
import SubjectElementListField from "@/components/DynamicForm/SubjectElementListField.vue"

const props = defineProps<{
  fields: ToolField[]
  toolId?: string
}>()

const model = defineModel<Record<string, unknown>>({ required: true })

type FieldOption = string | { label: string; value: string; promptPrefix?: string }
const MULTI_IMAGE_HISTORY_KEY = "aidesu_multi_image_history:image"
const MULTI_VIDEO_HISTORY_KEY = "aidesu_multi_image_history:video"

function optionLabel(option: FieldOption): string {
  return typeof option === "string" ? option : option.label
}

function optionValue(option: FieldOption): string {
  return typeof option === "string" ? option : option.value
}

function fieldOptions(field: ToolField): FieldOption[] {
  return fieldOptionsFromMeta(field)
}

watch(
  () => props.fields,
  (fields) => {
    const next = { ...model.value }
    let changed = false
    for (const f of fields) {
      if (!(f.fieldKey in next)) {
        next[f.fieldKey] = defaultFieldValue(f)
        changed = true
      }
    }
    for (const key of Object.keys(next)) {
      if (!fields.some((x) => x.fieldKey === key) && !key.endsWith("Custom")) {
        delete next[key]
        changed = true
      }
    }
    if (changed) model.value = next
  },
  { immediate: true, deep: true },
)

const advancedMode = computed(() => isCustomModeAdvanced(model.value))

const uniqueFields = computed(() => {
  const seen = new Set<string>()
  return props.fields.filter((field) => {
    const key = field.fieldKey || `${field.fieldName}:${field.sortOrder}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
})

const visibleFields = computed(() => filterFieldsForUi(uniqueFields.value, model.value))

const modeField = computed(() => uniqueFields.value.find((field) => field.fieldKey === "customMode"))

const groupedFields = computed(() => {
  const withoutMode = visibleFields.value.filter((field) => field.fieldKey !== "customMode")
  if (!advancedMode.value) {
    return [{ key: "__default__", label: "参数", fields: withoutMode }]
  }
  return groupVisibleFields(withoutMode)
})

const collapsedGroups = ref<Record<string, boolean>>({})

function isGroupCollapsed(key: string): boolean {
  if (key === "more") return collapsedGroups.value[key] ?? true
  return collapsedGroups.value[key] ?? false
}

function toggleGroup(key: string) {
  collapsedGroups.value = { ...collapsedGroups.value, [key]: !isGroupCollapsed(key) }
}

function strVal(key: string): string {
  const v = model.value[key]
  if (v === undefined || v === null) return ""
  return String(v)
}

function setField(key: string, val: unknown) {
  model.value = { ...model.value, [key]: val }
}

const uploading = reactive<Record<string, boolean>>({})
const replacingIndex = reactive<Record<string, number | null>>({})

type UploadFieldKind = "image" | "video" | "audio" | "file"

function isUploadField(field: ToolField): boolean {
  return ["image_upload", "video_upload", "audio_upload", "file_upload"].includes(field.fieldType)
}

function uploadFieldKind(field: ToolField): UploadFieldKind {
  if (field.fieldType === "image_upload") return "image"
  if (field.fieldType === "video_upload") return "video"
  if (field.fieldType === "audio_upload") return "audio"
  return "file"
}

function uploadAccept(field: ToolField): string {
  const metaAccept = parseFieldMeta(field).accept
  if (metaAccept) return metaAccept
  const kind = uploadFieldKind(field)
  if (kind === "image") return "image/*"
  if (kind === "video") return "video/*"
  if (kind === "audio") return "audio/*"
  return "image/*,video/*,audio/*,.pdf,.txt,.doc,.docx"
}

function uploadPrompt(field: ToolField): string {
  const kind = uploadFieldKind(field)
  if (kind === "image") return "点击上传图片"
  if (kind === "video") return "点击上传视频"
  if (kind === "audio") return "点击上传音频"
  return "点击上传文件"
}

function uploadedPreviewUrl(value: string): string {
  const raw = value.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:") || raw.startsWith("blob:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

async function onFilePicked(key: string, ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploading[key] = true
  try {
    const uploaded = await uploadToolFile(file)
    if (uploaded?.url) setField(key, uploaded.url)
  } finally {
    uploading[key] = false
    input.value = ""
  }
}

function isMediaListFieldType(field: ToolField): boolean {
  return isMediaListField(field)
}

function multiImageValues(field: ToolField): string[] {
  return parseMediaListValue(model.value[field.fieldKey], multiImageMax(field))
}

function setMultiImageValues(field: ToolField, values: string[]) {
  setField(field.fieldKey, normalizeMediaListValues(values, multiImageMax(field)))
}

function multiImageMeta(field: ToolField) {
  return parseFieldMeta(field)
}

function multiImageMax(field: ToolField): number {
  return isMediaListField(field) ? mediaListMax(field) : multiImageMeta(field).maxCount ?? 8
}

function multiImageMin(field: ToolField): number {
  return isMediaListField(field) ? mediaListMin(field) : multiImageMeta(field).minCount ?? 0
}

function multiImageAccept(field: ToolField): string {
  return isMediaListField(field) ? mediaListAccept(field) : multiImageMeta(field).accept || "image/*"
}

function multiImageLibraryEnabled(field: ToolField): boolean {
  return isMediaListField(field) ? mediaListLibraryEnabled(field) : multiImageMeta(field).libraryEnabled ?? true
}

function mediaListHistoryKey(field: ToolField): string {
  return mediaListLibraryKind(field) === "video" ? MULTI_VIDEO_HISTORY_KEY : MULTI_IMAGE_HISTORY_KEY
}

function loadLibraryItems(field: ToolField): string[] {
  if (typeof window === "undefined") return []
  try {
    const parsed = JSON.parse(window.localStorage.getItem(mediaListHistoryKey(field)) || "[]")
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string" && item.trim().length > 0) : []
  } catch {
    return []
  }
}

function saveLibraryItem(field: ToolField, url: string) {
  const clean = url.trim()
  if (!clean || typeof window === "undefined") return
  const current = loadLibraryItems(field)
  const next = [clean, ...current.filter((item) => item !== clean)].slice(0, 48)
  libraryItemsByField[field.fieldKey] = next
  window.localStorage.setItem(mediaListHistoryKey(field), JSON.stringify(next))
}

const libraryItemsByField = reactive<Record<string, string[]>>({})
const libraryOpen = reactive<Record<string, boolean>>({})

function libraryItems(field: ToolField): string[] {
  if (!libraryItemsByField[field.fieldKey]) {
    libraryItemsByField[field.fieldKey] = loadLibraryItems(field)
  }
  return libraryItemsByField[field.fieldKey]
}

function canAddMultiImage(field: ToolField): boolean {
  return multiImageValues(field).length < multiImageMax(field)
}

async function onMultiImagePicked(field: ToolField, ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files || [])
  if (!files.length) return
  const current = multiImageValues(field)
  const limit = multiImageMax(field)
  const replaceAt = replacingIndex[field.fieldKey]
  const remaining = replaceAt !== null && replaceAt !== undefined ? 1 : limit - current.length
  if (remaining <= 0) {
    window.alert(`最多选择 ${limit} ${mediaListUnitLabel(field)}`)
    input.value = ""
    return
  }
  const selected = files.slice(0, remaining)
  if (files.length > selected.length) {
    window.alert(`最多选择 ${limit} ${mediaListUnitLabel(field)}，已自动保留前 ${selected.length} 个`)
  }
  uploading[field.fieldKey] = true
  try {
    const uploadedUrls: string[] = []
    for (const file of selected) {
      const uploaded = await uploadToolFile(file)
      if (uploaded?.url) {
        uploadedUrls.push(uploaded.url)
        saveLibraryItem(field, uploaded.url)
      }
    }
    if (replaceAt !== null && replaceAt !== undefined) {
      const next = [...current]
      if (uploadedUrls[0]) next[replaceAt] = uploadedUrls[0]
      setMultiImageValues(field, next)
    } else {
      setMultiImageValues(field, [...current, ...uploadedUrls])
    }
  } finally {
    replacingIndex[field.fieldKey] = null
    uploading[field.fieldKey] = false
    input.value = ""
  }
}

function removeMultiImage(field: ToolField, index: number) {
  const next = multiImageValues(field).filter((_, itemIndex) => itemIndex !== index)
  setMultiImageValues(field, next)
}

function addLibraryImage(field: ToolField, url: string) {
  if (!canAddMultiImage(field)) {
    window.alert(`最多选择 ${multiImageMax(field)} ${mediaListUnitLabel(field)}`)
    return
  }
  const current = multiImageValues(field)
  if (current.includes(url)) return
  setMultiImageValues(field, [...current, url])
}

function previewImage(url: string) {
  if (!url) return
  window.open(url, "_blank", "noopener,noreferrer")
}

function setOptionField(key: string, val: string) {
  const next = { ...model.value, [key]: val }
  if (val !== "__custom__") delete next[`${key}Custom`]
  model.value = next
}

function onNumberInput(key: string, ev: Event) {
  const el = ev.target as HTMLInputElement
  const t = el.value
  if (t === "") {
    setField(key, "")
    return
  }
  const n = Number(t)
  setField(key, Number.isNaN(n) ? "" : n)
}

function sliderConfig(field: ToolField) {
  return parseFieldMeta(field).slider || { min: 0, max: 1, step: 0.01 }
}

function maxLengthFor(field: ToolField): number | undefined {
  return resolveMaxLength(field, model.value)
}

function isEffectivelyRequired(field: ToolField): boolean {
  if (!field.required && !field.userRequired) {
    if (field.fieldKey === "style" || field.fieldKey === "title") {
      return advancedMode.value
    }
    if (field.fieldKey === "prompt") {
      return advancedMode.value && !Boolean(model.value.instrumental)
    }
    return false
  }
  return Boolean(field.required || field.userRequired)
}

function validate(): { valid: boolean; message?: string } {
  for (const f of visibleFields.value) {
    if (!isFieldVisible(f, model.value)) continue
    if (uploading[f.fieldKey]) {
      return { valid: false, message: `${f.fieldName} 上传中，请稍后提交` }
    }
    if (isMediaListField(f)) {
      const count = multiImageValues(f).length
      const minCount = multiImageMin(f)
      if (isEffectivelyRequired(f) && count < Math.max(1, minCount)) {
        return { valid: false, message: `请至少选择 ${Math.max(1, minCount)} ${mediaListUnitLabel(f)}` }
      }
      if (count < minCount) {
        return { valid: false, message: `${f.fieldName} 至少需要 ${minCount} ${mediaListUnitLabel(f)}` }
      }
      if (count > multiImageMax(f)) {
        return { valid: false, message: `${f.fieldName} 最多选择 ${multiImageMax(f)} ${mediaListUnitLabel(f)}` }
      }
    }
    if (f.fieldType === "subject_element_list") {
      const items = parseSubjectElementEditorItems(model.value[f.fieldKey], subjectElementMax(f))
      const check = validateSubjectElementItems(items, {
        ...f,
        required: isEffectivelyRequired(f),
      })
      if (!check.valid) return check
    }
    if (!isEffectivelyRequired(f)) continue
    const v = model.value[f.fieldKey]
    if (v === undefined || v === null) {
      return { valid: false, message: `请填写：${f.fieldName}` }
    }
    if (typeof v === "string" && v.trim() === "") {
      return { valid: false, message: `请填写：${f.fieldName}` }
    }
    if ((f.fieldType === "number" || f.fieldType === "slider") && v === "") {
      return { valid: false, message: `请填写：${f.fieldName}` }
    }
    if (f.fieldType === "textarea") {
      const maxLen = maxLengthFor(f)
      if (maxLen && typeof v === "string" && v.length > maxLen) {
        return { valid: false, message: `${f.fieldName} 不能超过 ${maxLen} 字` }
      }
    }
    if ((f.fieldType === "select" || f.fieldType === "radio" || f.fieldType === "aspect_ratio") && v === "__custom__") {
      const custom = model.value[`${f.fieldKey}Custom`]
      if (custom === undefined || custom === null || String(custom).trim() === "") {
        return { valid: false, message: `请填写：${f.fieldName}` }
      }
    }
  }
  return { valid: true }
}

defineExpose({ validate })
</script>

<template>
  <div class="rounded-xl border border-border bg-card p-6 shadow-sm">
    <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="text-base font-semibold">参数填写</h2>
        <p class="mt-0.5 text-xs text-muted-foreground">
          {{ advancedMode ? "高级模式：可配置歌词、风格、标题与更多选项" : "常规模式：描述想法即可快速生成" }}
        </p>
      </div>
      <div v-if="modeField" class="inline-flex rounded-lg border border-border bg-background p-1">
        <button
          v-for="opt in fieldOptions(modeField)"
          :key="optionValue(opt)"
          type="button"
          class="rounded-md px-3 py-1.5 text-xs font-medium transition"
          :class="
            strVal('customMode') === optionValue(opt)
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:text-foreground'
          "
          @click="setOptionField('customMode', optionValue(opt))"
        >
          {{ optionLabel(opt) }}
        </button>
      </div>
    </div>

    <div v-if="fields.length === 0" class="py-8 text-center text-sm text-muted-foreground">
      暂无参数配置
    </div>

    <div v-else class="space-y-6">
      <section v-for="group in groupedFields" :key="group.key" class="space-y-4">
        <button
          v-if="group.key !== '__default__' && advancedMode"
          type="button"
          class="flex w-full items-center justify-between rounded-lg border border-border/70 bg-secondary/20 px-3 py-2 text-left"
          @click="toggleGroup(group.key)"
        >
          <span class="text-sm font-medium">{{ group.label }}</span>
          <span class="text-xs text-muted-foreground">{{ isGroupCollapsed(group.key) ? "展开" : "收起" }}</span>
        </button>

        <div v-show="group.key === '__default__' || !isGroupCollapsed(group.key)" class="space-y-5">
          <div v-for="f in group.fields" :key="f.fieldKey" class="space-y-2">
            <label v-if="!isMediaListFieldType(f) && f.fieldType !== 'subject_element_list'" class="text-sm font-medium">
              {{ f.fieldName }}
              <span v-if="isEffectivelyRequired(f)" class="text-destructive"> *</span>
            </label>

            <textarea
              v-if="f.fieldType === 'textarea'"
              :value="strVal(f.fieldKey)"
              :placeholder="f.placeholder || '请输入' + f.fieldName"
              :maxlength="maxLengthFor(f)"
              rows="4"
              class="w-full resize-none rounded-md border border-border bg-background px-3 py-2 text-sm"
              @input="setField(f.fieldKey, ($event.target as HTMLTextAreaElement).value)"
            />

            <div v-else-if="(f.fieldType === 'select' || f.fieldType === 'radio' || f.fieldType === 'aspect_ratio') && fieldOptions(f).length" class="flex flex-wrap gap-2">
              <button
                v-for="opt in fieldOptions(f)"
                :key="optionValue(opt)"
                type="button"
                class="rounded-md border px-3 py-1.5 text-xs font-medium transition"
                :class="
                  strVal(f.fieldKey) === optionValue(opt)
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border bg-background text-foreground/70 hover:border-primary/40'
                "
                @click="setOptionField(f.fieldKey, optionValue(opt))"
              >
                {{ optionLabel(opt) }}
              </button>
              <input
                v-if="strVal(f.fieldKey) === '__custom__'"
                :value="strVal(`${f.fieldKey}Custom`)"
                :placeholder="f.placeholder || '请输入自定义' + f.fieldName"
                class="mt-1 flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                @input="setField(`${f.fieldKey}Custom`, ($event.target as HTMLInputElement).value)"
              />
            </div>

            <div v-else-if="f.fieldType === 'slider'" class="space-y-2">
              <input
                type="range"
                :min="sliderConfig(f).min"
                :max="sliderConfig(f).max"
                :step="sliderConfig(f).step"
                :value="Number(model[f.fieldKey] ?? sliderConfig(f).min)"
                class="w-full accent-primary"
                @input="setField(f.fieldKey, Number(($event.target as HTMLInputElement).value))"
              />
              <div class="flex items-center justify-between text-xs text-muted-foreground">
                <span>{{ sliderConfig(f).min }}</span>
                <span class="font-medium text-foreground">{{ model[f.fieldKey] ?? sliderConfig(f).min }}</span>
                <span>{{ sliderConfig(f).max }}</span>
              </div>
            </div>

            <input
              v-else-if="f.fieldType === 'number'"
              type="number"
              :value="model[f.fieldKey] === '' || model[f.fieldKey] === undefined ? '' : model[f.fieldKey]"
              :placeholder="f.placeholder || '请输入数字'"
              class="flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
              @input="onNumberInput(f.fieldKey, $event)"
            />

            <label
              v-else-if="f.fieldType === 'checkbox'"
              class="flex min-h-10 items-center gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <input
                type="checkbox"
                :checked="Boolean(model[f.fieldKey])"
                class="h-4 w-4 accent-primary"
                @change="setField(f.fieldKey, ($event.target as HTMLInputElement).checked)"
              />
              <span>{{ f.placeholder || f.fieldName }}</span>
            </label>

            <div v-else-if="isMediaListFieldType(f)" class="rounded-lg border border-white/10 bg-[#18181f] p-4 text-[#f5f5f7] shadow-sm">
              <div class="mb-3 text-sm text-[#c8c7d2]">
                {{ f.fieldName }}
                <span v-if="isEffectivelyRequired(f)" class="text-destructive"> *</span>
              </div>
              <div class="flex flex-wrap gap-3">
                <label
                  class="grid h-24 w-24 cursor-pointer place-items-center rounded-xl border border-dashed border-white/15 bg-[#07070c] text-[#b9b7c6] transition hover:border-white/35 hover:text-white"
                  :class="{ 'cursor-not-allowed opacity-50': !canAddMultiImage(f) || uploading[f.fieldKey] }"
                >
                  <Plus v-if="f.fieldType !== 'multi_video'" class="h-7 w-7" />
                  <Video v-else class="h-7 w-7" />
                  <input
                    type="file"
                    class="hidden"
                    multiple
                    :disabled="!canAddMultiImage(f) || uploading[f.fieldKey]"
                    :accept="multiImageAccept(f)"
                    @change="onMultiImagePicked(f, $event)"
                  />
                </label>

                <button
                  v-if="multiImageLibraryEnabled(f)"
                  type="button"
                  class="flex h-24 w-28 items-center justify-center gap-2 rounded-xl border border-white/10 bg-[#08070d] px-3 text-sm text-[#d7d5df] transition hover:border-white/25 hover:text-white"
                  @click="libraryOpen[f.fieldKey] = !libraryOpen[f.fieldKey]"
                >
                  <BookOpen class="h-5 w-5" />
                  <span>素材库</span>
                </button>
              </div>

              <div class="mt-3 text-xs text-[#a8a6b5]">
                已选 {{ multiImageValues(f).length }}/{{ multiImageMax(f) }} {{ mediaListUnitLabel(f) }}
                <span v-if="uploading[f.fieldKey]" class="ml-2 text-[#d8d6e4]">上传中...</span>
              </div>

              <div v-if="multiImageValues(f).length" class="mt-4 grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6">
                <div
                  v-for="(url, index) in multiImageValues(f)"
                  :key="`${url}-${index}`"
                  class="group relative aspect-square overflow-hidden rounded-lg border border-white/10 bg-[#09090f]"
                >
                  <button v-if="f.fieldType !== 'multi_video'" type="button" class="h-full w-full" @click="previewImage(url)">
                    <img :src="uploadedPreviewUrl(url)" alt="参考图" class="h-full w-full object-cover" />
                  </button>
                  <div v-else class="h-full w-full bg-black">
                    <video :src="uploadedPreviewUrl(url)" class="h-full w-full object-cover" muted playsinline preload="metadata" />
                  </div>
                  <div class="absolute inset-x-1 top-1 flex justify-end gap-1 opacity-0 transition group-hover:opacity-100">
                    <label
                      class="grid h-7 w-7 cursor-pointer place-items-center rounded-md bg-black/65 text-white backdrop-blur hover:bg-black/85"
                      title="替换"
                      @click="replacingIndex[f.fieldKey] = index"
                    >
                      <RefreshCw class="h-3.5 w-3.5" />
                      <input
                        type="file"
                        class="hidden"
                        :accept="multiImageAccept(f)"
                        @change="onMultiImagePicked(f, $event)"
                      />
                    </label>
                    <button
                      type="button"
                      class="grid h-7 w-7 place-items-center rounded-md bg-black/65 text-white backdrop-blur hover:bg-black/85"
                      title="删除"
                      @click="removeMultiImage(f, index)"
                    >
                      <X class="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              </div>

              <div v-if="libraryOpen[f.fieldKey]" class="mt-4 rounded-lg border border-white/10 bg-black/20 p-3">
                <div v-if="libraryItems(f).length" class="grid grid-cols-4 gap-2 sm:grid-cols-6 md:grid-cols-8">
                  <button
                    v-for="url in libraryItems(f)"
                    :key="url"
                    type="button"
                    class="aspect-square overflow-hidden rounded-md border border-white/10 bg-[#09090f] transition hover:border-white/35"
                    @click="addLibraryImage(f, url)"
                  >
                    <img v-if="f.fieldType !== 'multi_video'" :src="uploadedPreviewUrl(url)" alt="素材图" class="h-full w-full object-cover" />
                    <video v-else :src="uploadedPreviewUrl(url)" class="h-full w-full object-cover" muted playsinline preload="metadata" />
                  </button>
                </div>
                <div v-else class="flex items-center gap-2 text-xs text-[#a8a6b5]">
                  <ImageIcon v-if="f.fieldType !== 'multi_video'" class="h-4 w-4" />
                  <Video v-else class="h-4 w-4" />
                  <span>{{ f.fieldType === "multi_video" ? "暂无最近上传视频" : "暂无最近上传图片" }}</span>
                </div>
              </div>
            </div>

            <SubjectElementListField
              v-else-if="f.fieldType === 'subject_element_list'"
              :field="f"
              :model-value="model[f.fieldKey]"
              @update:model-value="setField(f.fieldKey, $event)"
            />

            <div v-else-if="isUploadField(f)" class="space-y-2">
              <div
                v-if="strVal(f.fieldKey) && !uploading[f.fieldKey]"
                class="group relative overflow-hidden rounded-xl border border-border bg-background/60"
              >
                <img
                  v-if="uploadFieldKind(f) === 'image'"
                  :src="uploadedPreviewUrl(strVal(f.fieldKey))"
                  :alt="f.fieldName"
                  class="max-h-64 w-full object-contain"
                />
                <video
                  v-else-if="uploadFieldKind(f) === 'video'"
                  :src="uploadedPreviewUrl(strVal(f.fieldKey))"
                  class="max-h-64 w-full object-contain bg-black"
                  controls
                  playsinline
                  preload="metadata"
                />
                <div v-else class="flex items-center gap-3 px-4 py-5 text-sm">
                  <Music v-if="uploadFieldKind(f) === 'audio'" class="h-5 w-5 text-primary" />
                  <FileUp v-else class="h-5 w-5 text-primary" />
                  <span class="truncate text-foreground/80">{{ strVal(f.fieldKey) }}</span>
                </div>
                <div class="absolute inset-x-0 bottom-0 flex items-center justify-end gap-2 bg-gradient-to-t from-black/70 to-transparent p-2 opacity-0 transition group-hover:opacity-100">
                  <label class="inline-flex cursor-pointer items-center gap-1 rounded-md bg-white/15 px-2.5 py-1 text-xs font-medium text-white backdrop-blur transition hover:bg-white/25">
                    重新上传
                    <input class="hidden" type="file" :accept="uploadAccept(f)" @change="onFilePicked(f.fieldKey, $event)" />
                  </label>
                  <button
                    type="button"
                    class="inline-flex items-center gap-1 rounded-md bg-white/15 px-2.5 py-1 text-xs font-medium text-white backdrop-blur transition hover:bg-white/25"
                    @click="setField(f.fieldKey, '')"
                  >
                    <X class="h-3.5 w-3.5" />移除
                  </button>
                </div>
              </div>

              <label
                v-else
                class="flex w-full cursor-pointer flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-border bg-background/40 px-4 py-10 text-center transition hover:border-primary/50 hover:bg-background/70"
                :class="{ 'cursor-not-allowed opacity-60': uploading[f.fieldKey] }"
              >
                <RefreshCw v-if="uploading[f.fieldKey]" class="h-7 w-7 animate-spin text-primary" />
                <template v-else>
                  <ImageIcon v-if="uploadFieldKind(f) === 'image'" class="h-8 w-8 text-muted-foreground" />
                  <Video v-else-if="uploadFieldKind(f) === 'video'" class="h-8 w-8 text-muted-foreground" />
                  <Music v-else-if="uploadFieldKind(f) === 'audio'" class="h-8 w-8 text-muted-foreground" />
                  <FileUp v-else class="h-8 w-8 text-muted-foreground" />
                </template>
                <span class="text-sm text-muted-foreground">
                  {{ uploading[f.fieldKey] ? "上传中..." : uploadPrompt(f) }}
                </span>
                <span v-if="!uploading[f.fieldKey]" class="text-xs text-muted-foreground/70">
                  支持点击选择文件
                </span>
                <input
                  class="hidden"
                  type="file"
                  :accept="uploadAccept(f)"
                  :disabled="uploading[f.fieldKey]"
                  @change="onFilePicked(f.fieldKey, $event)"
                />
              </label>
            </div>

            <div v-else-if="f.fieldType === 'image' || f.fieldType === 'file'" class="space-y-2">
              <input
                type="url"
                :value="strVal(f.fieldKey)"
                :placeholder="f.placeholder || '请输入资源 URL'"
                class="flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                @input="setField(f.fieldKey, ($event.target as HTMLInputElement).value)"
              />
              <div class="flex items-center gap-2">
                <input
                  :accept="f.fieldType === 'image' ? 'image/*' : 'video/*,audio/*,image/*'"
                  type="file"
                  class="text-xs"
                  @change="onFilePicked(f.fieldKey, $event)"
                />
                <span v-if="uploading[f.fieldKey]" class="text-xs text-muted-foreground">上传中...</span>
              </div>
            </div>

            <input
              v-else
              :value="strVal(f.fieldKey)"
              :placeholder="f.placeholder || '请输入' + f.fieldName"
              :maxlength="maxLengthFor(f)"
              class="flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
              @input="setField(f.fieldKey, ($event.target as HTMLInputElement).value)"
            />

            <p v-if="maxLengthFor(f)" class="text-[11px] text-muted-foreground">
              最多 {{ maxLengthFor(f) }} 字
              <span v-if="f.fieldType === 'textarea'">（当前 {{ strVal(f.fieldKey).length }} 字）</span>
            </p>
            <p v-else-if="f.placeholder" class="text-[11px] text-muted-foreground">{{ f.placeholder }}</p>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
