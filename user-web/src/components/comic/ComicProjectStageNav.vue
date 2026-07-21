<script setup lang="ts">
import { computed, type Component } from "vue"
import {
  BookOpenText,
  Check,
  Clapperboard,
  Images,
  LockKeyhole,
  Rows3,
  Video,
} from "lucide-vue-next"
import type { ComicEpisodeDetail } from "@/api/comicProjectApi"
import {
  COMIC_WORKSPACE_STAGES,
  currentComicStage,
  isComicStageAccessible,
  stageIndex,
  type ComicWorkspaceStage,
} from "@/utils/comicProject"

const props = defineProps<{
  modelValue: ComicWorkspaceStage
  episode?: ComicEpisodeDetail | null
}>()

const emit = defineEmits<{
  "update:modelValue": [stage: ComicWorkspaceStage]
}>()

const iconMap: Record<ComicWorkspaceStage, Component> = {
  script: BookOpenText,
  storyboard: Rows3,
  assets: Images,
  shots: Clapperboard,
  delivery: Video,
}

const current = computed(() => currentComicStage(props.episode))

function itemState(stage: ComicWorkspaceStage): "complete" | "current" | "locked" | "available" {
  if (!isComicStageAccessible(stage, props.episode)) return "locked"
  if (stage === current.value) return "current"
  return stageIndex(stage) < stageIndex(current.value) ? "complete" : "available"
}

function select(stage: ComicWorkspaceStage) {
  if (isComicStageAccessible(stage, props.episode)) emit("update:modelValue", stage)
}
</script>

<template>
  <nav aria-label="漫剧制作阶段" class="comic-stage-nav">
    <ol class="flex min-w-max gap-1 p-1 lg:min-w-0 lg:flex-col lg:p-0">
      <li v-for="(stage, index) in COMIC_WORKSPACE_STAGES" :key="stage.id" class="lg:w-full">
        <button
          type="button"
          class="group flex h-12 min-w-28 items-center gap-2 rounded-md px-3 text-left text-sm transition lg:h-auto lg:min-w-0 lg:w-full lg:gap-3 lg:px-3 lg:py-3"
          :class="[
            modelValue === stage.id
              ? 'bg-primary/12 text-foreground ring-1 ring-primary/25'
              : itemState(stage.id) === 'locked'
                ? 'cursor-not-allowed text-muted-foreground/45'
                : 'text-muted-foreground hover:bg-secondary/55 hover:text-foreground',
          ]"
          :disabled="itemState(stage.id) === 'locked'"
          :aria-current="modelValue === stage.id ? 'step' : undefined"
          @click="select(stage.id)"
        >
          <span
            class="flex h-7 w-7 shrink-0 items-center justify-center rounded-md border text-xs"
            :class="modelValue === stage.id ? 'border-primary/40 bg-primary/15 text-primary' : 'border-border bg-background/40'"
          >
            <Check v-if="itemState(stage.id) === 'complete'" class="h-3.5 w-3.5 text-success" aria-hidden="true" />
            <LockKeyhole v-else-if="itemState(stage.id) === 'locked'" class="h-3.5 w-3.5" aria-hidden="true" />
            <component :is="iconMap[stage.id]" v-else class="h-3.5 w-3.5" aria-hidden="true" />
          </span>
          <span class="min-w-0">
            <span class="block truncate font-medium">{{ stage.label }}</span>
            <span class="mt-0.5 hidden text-[11px] text-muted-foreground lg:block">阶段 {{ index + 1 }}</span>
          </span>
        </button>
      </li>
    </ol>
  </nav>
</template>

<style scoped>
@media (max-width: 1023px) {
  .comic-stage-nav {
    overflow-x: auto;
    scrollbar-width: none;
  }

  .comic-stage-nav::-webkit-scrollbar {
    display: none;
  }
}
</style>
