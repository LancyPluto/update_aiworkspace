<script setup lang="ts">
import { ChevronDown, Clock, Sparkles } from "lucide-vue-next"

defineProps<{
  open: boolean
  tabs: Array<{
    key: string
    label: string
    icon: unknown
  }>
  selectedModality: string
  selectedLabel: string
  selectedIcon?: unknown
  availableCredits?: number | string | null
  runningCount: number
}>()

const emit = defineEmits<{
  "update:open": [value: boolean]
  select: [key: string]
}>()
</script>

<template>
  <aside
    class="fixed bottom-6 left-[calc(var(--app-sidebar-width,268px)+1.25rem)] top-[calc(5rem+1.25rem)] z-40 hidden w-[92px] flex-col rounded-lg border border-white/10 bg-black/80 shadow-[0_24px_80px_rgb(0_0_0_/_0.45)] backdrop-blur-xl transition-[left,width,height] lg:flex"
    :class="open ? '' : 'top-auto h-16 w-auto'"
  >
    <button
      type="button"
      class="absolute -right-3 top-4 flex h-7 w-7 items-center justify-center rounded-full border border-white/10 bg-white/10 text-white/55 transition hover:bg-white/15 hover:text-white"
      :aria-label="open ? '收起模态导航' : '展开模态导航'"
      :aria-expanded="open"
      @click="emit('update:open', !open)"
    >
      <ChevronDown class="h-4 w-4 transition" :class="open ? 'rotate-90' : '-rotate-90'" />
    </button>

    <template v-if="open">
      <div class="flex flex-1 flex-col items-center gap-3 overflow-y-auto py-6">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          class="group flex w-full flex-col items-center gap-1.5 px-2 py-3 text-[11px] transition"
          :class="selectedModality === tab.key ? 'text-white' : 'text-white/45 hover:text-white'"
          :aria-pressed="selectedModality === tab.key"
          @click="emit('select', tab.key)"
        >
          <span
            class="flex h-11 w-11 items-center justify-center rounded-xl border transition"
            :class="
              selectedModality === tab.key
                ? 'border-primary/70 bg-primary/20 shadow-[0_0_24px_rgb(var(--brand-primary-rgb)_/_0.28)]'
                : 'border-white/8 bg-white/[0.04] group-hover:bg-white/8'
            "
          >
            <component :is="tab.icon" class="h-5 w-5" />
          </span>
          <span>{{ tab.label }}</span>
        </button>
      </div>
      <div class="space-y-4 border-t border-white/8 px-3 py-5 text-center text-[11px] text-white/50">
        <div>
          <p class="text-sm font-semibold tabular-nums text-white/85">{{ availableCredits ?? "--" }}</p>
          <p class="mt-0.5">可用算力</p>
        </div>
        <div>
          <p class="flex items-center justify-center gap-1 text-sm font-semibold tabular-nums text-white/85">
            <Clock class="h-3.5 w-3.5 text-primary" />
            {{ runningCount }}
          </p>
          <p class="mt-0.5">进行中</p>
        </div>
      </div>
    </template>
    <button
      v-else
      type="button"
      class="flex h-16 items-center gap-2 rounded-lg px-5 text-sm font-semibold text-white"
      @click="emit('update:open', true)"
    >
      <component :is="selectedIcon || Sparkles" class="h-5 w-5 text-primary" />
      {{ selectedLabel }}
    </button>
  </aside>
</template>
