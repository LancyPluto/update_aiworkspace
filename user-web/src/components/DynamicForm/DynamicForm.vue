<script setup lang="ts">
import { watch } from "vue"
import type { ToolField } from "@/api/types"

const props = defineProps<{
  fields: ToolField[]
}>()

const model = defineModel<Record<string, unknown>>({ required: true })

type FieldOption = string | { label: string; value: string }

function optionLabel(option: FieldOption): string {
  return typeof option === "string" ? option : option.label
}

function optionValue(option: FieldOption): string {
  return typeof option === "string" ? option : option.value
}

function defaultVal(f: ToolField): unknown {
  if ((f.fieldType === "select" || f.fieldType === "radio") && f.options?.length) return optionValue(f.options[0])
  if (f.fieldType === "checkbox") return false
  if (f.fieldType === "slider") return 50
  if (f.fieldType === "number") return ""
  return ""
}

watch(
  () => props.fields,
  (fields) => {
    const next = { ...model.value }
    let changed = false
    for (const f of fields) {
      if (!(f.fieldKey in next)) {
        next[f.fieldKey] = defaultVal(f)
        changed = true
      }
    }
    for (const key of Object.keys(next)) {
      if (!fields.some((x) => x.fieldKey === key)) {
        delete next[key]
        changed = true
      }
    }
    if (changed) model.value = next
  },
  { immediate: true, deep: true },
)

function strVal(key: string): string {
  const v = model.value[key]
  if (v === undefined || v === null) return ""
  return String(v)
}

function setField(key: string, val: unknown) {
  model.value = { ...model.value, [key]: val }
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

function validate(): { valid: boolean; message?: string } {
  for (const f of props.fields) {
    if (!f.required) continue
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
  }
  return { valid: true }
}

defineExpose({ validate })
</script>

<template>
  <div class="rounded-xl border border-border bg-card p-6 shadow-sm">
    <div class="flex items-center justify-between mb-5">
      <div>
        <h2 class="text-base font-semibold">参数填写</h2>
        <p class="text-xs text-muted-foreground mt-0.5">标 <span class="text-destructive">*</span> 为必填项</p>
      </div>
    </div>

    <div v-if="fields.length === 0" class="text-sm text-muted-foreground py-8 text-center">
      暂无参数配置
    </div>

    <div v-else class="space-y-5">
      <div v-for="f in fields" :key="f.fieldKey" class="space-y-2">
        <label class="text-sm font-medium">
          {{ f.fieldName }}
          <span v-if="f.required" class="text-destructive"> *</span>
        </label>

        <textarea
          v-if="f.fieldType === 'textarea'"
          :value="strVal(f.fieldKey)"
          :placeholder="f.placeholder || '请输入' + f.fieldName"
          rows="4"
          class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm resize-none"
          @input="setField(f.fieldKey, ($event.target as HTMLTextAreaElement).value)"
        />

        <div v-else-if="(f.fieldType === 'select' || f.fieldType === 'radio') && f.options?.length" class="flex flex-wrap gap-2">
          <button
            v-for="opt in f.options"
            :key="optionValue(opt)"
            type="button"
            class="rounded-md border px-3 py-1.5 text-xs font-medium transition"
            :class="
              strVal(f.fieldKey) === optionValue(opt)
                ? 'border-primary bg-primary/10 text-primary'
                : 'border-border bg-background text-foreground/70 hover:border-primary/40'
            "
            @click="setField(f.fieldKey, optionValue(opt))"
          >
            {{ optionLabel(opt) }}
          </button>
        </div>

        <input
          v-else-if="f.fieldType === 'number' || f.fieldType === 'slider'"
          type="number"
          :min="f.fieldType === 'slider' ? 0 : undefined"
          :max="f.fieldType === 'slider' ? 100 : undefined"
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

        <input
          v-else-if="f.fieldType === 'image' || f.fieldType === 'file'"
          type="url"
          :value="strVal(f.fieldKey)"
          :placeholder="f.placeholder || '请输入资源 URL'"
          class="flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          @input="setField(f.fieldKey, ($event.target as HTMLInputElement).value)"
        />

        <input
          v-else
          :value="strVal(f.fieldKey)"
          :placeholder="f.placeholder || '请输入' + f.fieldName"
          class="flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          @input="setField(f.fieldKey, ($event.target as HTMLInputElement).value)"
        />

        <p v-if="f.placeholder" class="text-[11px] text-muted-foreground">{{ f.placeholder }}</p>
      </div>
    </div>
  </div>
</template>
