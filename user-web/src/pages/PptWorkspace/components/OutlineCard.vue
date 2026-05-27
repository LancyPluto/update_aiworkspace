<script setup lang="ts">
import { ref, watch } from "vue"
import { ChevronDown, ChevronUp, GripVertical, Pencil, Trash2, Check, X } from "lucide-vue-next"
import type { PptPage, PptPageOutline } from "@/api/pptApi"

const props = defineProps<{
  page: PptPage
  index: number
  isLast?: boolean
  selected?: boolean
  busy?: boolean
}>()

const emit = defineEmits<{
  select: []
  save: [outline: PptPageOutline]
  delete: []
  moveUp: []
  moveDown: []
}>()

const editing = ref(false)
const editTitle = ref("")
const editPoints = ref("")

watch(
  () => props.page.outlineContent,
  (outline) => {
    if (!editing.value && outline) {
      editTitle.value = outline.title ?? ""
      editPoints.value = (outline.points ?? []).join("\n")
    }
  },
  { immediate: true, deep: true },
)

function startEdit() {
  editTitle.value = props.page.outlineContent?.title ?? ""
  editPoints.value = (props.page.outlineContent?.points ?? []).join("\n")
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

function saveEdit() {
  emit("save", {
    title: editTitle.value.trim(),
    points: editPoints.value
      .split("\n")
      .map((p) => p.trim())
      .filter(Boolean),
  })
  editing.value = false
}
</script>

<template>
  <div
    class="rounded-xl border transition-colors"
    :class="
      selected
        ? 'border-primary bg-primary/5 shadow-sm'
        : 'border-border bg-card hover:border-primary/30'
    "
    @click="emit('select')"
  >
    <div class="flex items-start gap-2 p-4">
      <div class="flex flex-col gap-0.5 pt-1 text-muted-foreground">
        <button
          type="button"
          class="rounded p-0.5 hover:bg-secondary disabled:opacity-30"
          :disabled="index === 0 || busy"
          title="上移"
          @click.stop="emit('moveUp')"
        >
          <ChevronUp class="h-4 w-4" />
        </button>
        <GripVertical class="h-4 w-4 opacity-40" />
        <button
          type="button"
          class="rounded p-0.5 hover:bg-secondary disabled:opacity-30"
          :disabled="isLast || busy"
          title="下移"
          @click.stop="emit('moveDown')"
        >
          <ChevronDown class="h-4 w-4" />
        </button>
      </div>

      <div class="min-w-0 flex-1 space-y-2">
        <div class="flex items-center justify-between gap-2">
          <span class="text-xs font-medium text-muted-foreground">第 {{ index + 1 }} 页</span>
          <div class="flex items-center gap-1" @click.stop>
            <template v-if="editing">
              <button
                type="button"
                class="rounded-md p-1.5 text-primary hover:bg-primary/10"
                @click="saveEdit"
              >
                <Check class="h-4 w-4" />
              </button>
              <button
                type="button"
                class="rounded-md p-1.5 text-muted-foreground hover:bg-secondary"
                @click="cancelEdit"
              >
                <X class="h-4 w-4" />
              </button>
            </template>
            <template v-else>
              <button
                type="button"
                class="rounded-md p-1.5 text-muted-foreground hover:bg-secondary"
                :disabled="busy"
                @click="startEdit"
              >
                <Pencil class="h-4 w-4" />
              </button>
              <button
                type="button"
                class="rounded-md p-1.5 text-destructive hover:bg-destructive/10"
                :disabled="busy"
                @click="emit('delete')"
              >
                <Trash2 class="h-4 w-4" />
              </button>
            </template>
          </div>
        </div>

        <template v-if="editing">
          <input
            v-model="editTitle"
            class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
            placeholder="标题"
            @click.stop
          />
          <textarea
            v-model="editPoints"
            class="min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
            placeholder="要点（每行一条）"
            @click.stop
          />
        </template>
        <template v-else>
          <p class="font-medium text-foreground">
            {{ page.outlineContent?.title || "（无标题）" }}
          </p>
          <ul
            v-if="page.outlineContent?.points?.length"
            class="list-disc space-y-0.5 pl-5 text-sm text-muted-foreground"
          >
            <li v-for="(pt, i) in page.outlineContent.points" :key="i">{{ pt }}</li>
          </ul>
          <p v-else class="text-sm text-muted-foreground italic">暂无要点</p>
        </template>
      </div>
    </div>
  </div>
</template>
