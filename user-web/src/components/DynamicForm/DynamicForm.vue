<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue"
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

const props = defineProps<{
  fields: ToolField[]
  toolId?: string
}>()

const model = defineModel<Record<string, unknown>>({ required: true })

type FieldOption = string | { label: string; value: string; promptPrefix?: string }

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

const visibleFields = computed(() => filterFieldsForUi(props.fields, model.value))

const modeField = computed(() => props.fields.find((field) => field.fieldKey === "customMode"))

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
    if ((f.fieldType === "select" || f.fieldType === "radio") && v === "__custom__") {
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
            <label class="text-sm font-medium">
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

            <div v-else-if="(f.fieldType === 'select' || f.fieldType === 'radio') && fieldOptions(f).length" class="flex flex-wrap gap-2">
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
