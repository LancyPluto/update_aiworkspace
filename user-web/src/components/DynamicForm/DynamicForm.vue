<script setup lang="ts">
import type { ToolField } from "@/api/types"

defineProps<{
  fields: ToolField[]
}>()
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

        <!-- textarea 类型 -->
        <textarea
          v-if="f.fieldType === 'textarea'"
          :placeholder="f.placeholder || '请输入' + f.fieldName"
          rows="4"
          class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm resize-none"
        ></textarea>

        <!-- select 类型 -->
        <div v-else-if="f.fieldType === 'select' && f.options?.length" class="flex flex-wrap gap-2">
          <button
            v-for="opt in f.options"
            :key="opt.value"
            type="button"
            class="rounded-md border px-3 py-1.5 text-xs font-medium transition border-border bg-background text-foreground/70 hover:border-primary/40"
          >
            {{ opt.label }}
          </button>
        </div>

        <!-- text 类型（默认） -->
        <input
          v-else
          :placeholder="f.placeholder || '请输入' + f.fieldName"
          class="flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
        />

        <p v-if="f.placeholder" class="text-[11px] text-muted-foreground">{{ f.placeholder }}</p>
      </div>
    </div>
  </div>
</template>
