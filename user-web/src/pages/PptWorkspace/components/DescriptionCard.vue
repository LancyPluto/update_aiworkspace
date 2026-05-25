<script setup lang="ts">
import { ref, watch } from "vue"
import { Loader2, Pencil, Sparkles, Check, X } from "lucide-vue-next"
import type { PptPage } from "@/api/pptApi"
import { getPageDescriptionText } from "@/utils/pptProjectUtils"

const props = defineProps<{
  page: PptPage
  index: number
  selected?: boolean
  busy?: boolean
  generating?: boolean
}>()

const emit = defineEmits<{
  select: []
  save: [text: string]
  generate: []
}>()

const editing = ref(false)
const editText = ref("")

watch(
  () => props.page,
  () => {
    if (!editing.value) editText.value = getPageDescriptionText(props.page)
  },
  { immediate: true, deep: true },
)

function save() {
  emit("save", editText.value)
  editing.value = false
}
</script>

<template>
  <div
    class="rounded-xl border transition-colors"
    :class="
      selected
        ? 'border-primary bg-primary/5'
        : 'border-border bg-card hover:border-primary/30'
    "
    @click="emit('select')"
  >
    <div class="space-y-3 p-4">
      <div class="flex items-start justify-between gap-2">
        <div class="min-w-0">
          <p class="text-xs text-muted-foreground">第 {{ index + 1 }} 页</p>
          <p class="font-medium truncate">{{ page.outlineContent?.title || "（无标题）" }}</p>
        </div>
        <div class="flex shrink-0 gap-1" @click.stop>
          <button
            v-if="!editing"
            type="button"
            class="rounded-md p-1.5 text-muted-foreground hover:bg-secondary"
            :disabled="busy || generating"
            title="编辑"
            @click="editing = true"
          >
            <Pencil class="h-4 w-4" />
          </button>
          <button
            type="button"
            class="rounded-md p-1.5 text-primary hover:bg-primary/10"
            :disabled="busy || generating"
            title="生成描述"
            @click="emit('generate')"
          >
            <Loader2 v-if="generating" class="h-4 w-4 animate-spin" />
            <Sparkles v-else class="h-4 w-4" />
          </button>
        </div>
      </div>

      <template v-if="editing">
        <textarea
          v-model="editText"
          class="min-h-[120px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
          @click.stop
        />
        <div class="flex justify-end gap-2">
          <button
            type="button"
            class="inline-flex items-center rounded-md px-2 py-1 text-xs hover:bg-secondary"
            @click.stop="editing = false"
          >
            <X class="h-3.5 w-3.5 mr-1" /> 取消
          </button>
          <button
            type="button"
            class="inline-flex items-center rounded-md bg-primary px-2 py-1 text-xs text-primary-foreground"
            @click.stop="save"
          >
            <Check class="h-3.5 w-3.5 mr-1" /> 保存
          </button>
        </div>
      </template>
      <p v-else class="text-sm text-muted-foreground whitespace-pre-wrap line-clamp-6">
        {{ getPageDescriptionText(page) || "暂无描述，点击 ✨ 生成或铅笔编辑" }}
      </p>
    </div>
  </div>
</template>
