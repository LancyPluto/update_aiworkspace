<script setup lang="ts">
import { computed, ref } from "vue"
import type { ScrollNavNode } from "@/utils/conversationPhases"
import { selectionTick } from "@/utils/haptic"

const props = defineProps<{
  nodes: ScrollNavNode[]
  visible?: boolean
}>()

const emit = defineEmits<{
  navigate: [messageId: number]
}>()

const hoveredId = ref<string | null>(null)
const lastTickIndex = ref(-1)
let tickTimer = 0

function onNodeClick(node: ScrollNavNode) {
  selectionTick()
  emit("navigate", node.messageId)
}

function onNodeEnter(node: ScrollNavNode, index: number) {
  hoveredId.value = node.id
  if (index !== lastTickIndex.value) {
    lastTickIndex.value = index
    window.clearTimeout(tickTimer)
    tickTimer = window.setTimeout(() => selectionTick(), 0)
  }
}

function onNodeLeave() {
  hoveredId.value = null
}

function onTouchMove(event: TouchEvent) {
  const touch = event.touches[0]
  if (!touch) return
  const el = document.elementFromPoint(touch.clientX, touch.clientY)
  const nodeEl = el?.closest("[data-nav-node-id]") as HTMLElement | null
  if (!nodeEl) return
  const index = Number(nodeEl.dataset.navIndex ?? -1)
  if (index >= 0 && index !== lastTickIndex.value) {
    lastTickIndex.value = index
    window.clearTimeout(tickTimer)
    tickTimer = window.setTimeout(() => selectionTick(), 80)
  }
}

const showRail = computed(() => props.visible !== false && props.nodes.length > 1)
</script>

<template>
  <div
    v-if="showRail"
    class="scroll-nav"
    @touchmove.passive="onTouchMove"
  >
    <div class="scroll-nav__track" aria-hidden="true" />
    <button
      v-for="(node, index) in nodes"
      :key="node.id"
      type="button"
      class="scroll-nav__node"
      :class="{
        'scroll-nav__node--user': node.kind === 'user',
        'scroll-nav__node--media': node.kind === 'media',
        'scroll-nav__node--tool': node.kind === 'tool',
      }"
      :style="{ top: `${node.ratio * 100}%` }"
      :data-nav-node-id="node.id"
      :data-nav-index="index"
      :aria-label="node.label"
      @mouseenter="onNodeEnter(node, index)"
      @mouseleave="onNodeLeave"
      @click="onNodeClick(node)"
    >
      <span class="scroll-nav__dot" />
      <Transition name="scroll-nav-tip">
        <div v-if="hoveredId === node.id" class="scroll-nav__tooltip">
          <img
            v-if="node.thumbnailUrl"
            :src="node.thumbnailUrl"
            alt=""
            class="scroll-nav__thumb"
            loading="lazy"
          />
          <span>{{ node.label }}</span>
        </div>
      </Transition>
    </button>
  </div>
</template>

<style scoped>
.scroll-nav {
  position: absolute;
  top: 64px;
  right: 12px;
  bottom: 120px;
  width: 28px;
  z-index: 4;
  pointer-events: none;
}

.scroll-nav__track {
  position: absolute;
  top: 0;
  bottom: 0;
  right: 10px;
  width: 2px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.06);
}

.scroll-nav__node {
  position: absolute;
  right: 0;
  transform: translateY(-50%);
  width: 28px;
  height: 20px;
  border: none;
  background: transparent;
  cursor: pointer;
  pointer-events: auto;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.scroll-nav__dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.22);
  transition: transform 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
}

.scroll-nav__node--user .scroll-nav__dot {
  background: var(--agent-accent);
  opacity: 0.75;
}

.scroll-nav__node--media .scroll-nav__dot {
  background: rgb(34 211 238 / 0.8);
}

.scroll-nav__node--tool .scroll-nav__dot {
  background: rgb(251 191 36 / 0.75);
}

.scroll-nav__node:hover .scroll-nav__dot,
.scroll-nav__node:focus-visible .scroll-nav__dot {
  transform: scale(1.35);
  box-shadow: 0 0 10px var(--agent-accent-glow);
}

.scroll-nav__tooltip {
  position: absolute;
  right: calc(100% + 10px);
  top: 50%;
  transform: translateY(-50%);
  min-width: 120px;
  max-width: 180px;
  padding: 8px 10px;
  border-radius: 12px;
  background: rgb(24 24 28 / 0.82);
  border: 1px solid rgb(255 255 255 / 0.08);
  backdrop-filter: blur(16px) saturate(140%);
  color: rgb(255 255 255 / 0.88);
  font-size: 11px;
  line-height: 1.4;
  text-align: left;
  box-shadow: 0 12px 40px rgb(0 0 0 / 0.4);
  display: flex;
  align-items: center;
  gap: 8px;
  pointer-events: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.scroll-nav__thumb {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
}

.scroll-nav-tip-enter-active,
.scroll-nav-tip-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.scroll-nav-tip-enter-from,
.scroll-nav-tip-leave-to {
  opacity: 0;
  transform: translateY(-50%) translateX(6px);
}

@media (max-width: 720px) {
  .scroll-nav {
    right: 4px;
    width: 22px;
  }
}
</style>
