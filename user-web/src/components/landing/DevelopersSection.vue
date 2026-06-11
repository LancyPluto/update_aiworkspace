<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Terminal } from 'lucide-vue-next'

type TerminalBlock = {
  command: string
  lines: string[]
}

const blocks: TerminalBlock[] = [
  {
    command: '$ 生成一张图片：一只可爱的小猫在编程',
    lines: [
      '🎨 正在调用 Midjourney V6...',
      '✨ 图片生成完成！',
      '📊 耗时: 1.2s',
      '📎 保存至: 我的作品/可爱小猫.png',
    ],
  },
  {
    command: '$ 把这张图片转为视频',
    lines: [
      '🎬 正在调用 Runway Gen-3...',
      '✨ 视频生成完成！',
      '📊 耗时: 8.5s',
      '📎 保存至: 我的作品/可爱小猫.mp4',
    ],
  },
]

const sectionRef = ref<HTMLElement | null>(null)
const hasStarted = ref(false)
const visibleBlockCount = ref(0)
const typedLines = ref<string[][]>(blocks.map(() => []))
const activeBlock = ref(-1)
const activeLine = ref(-1)
const isTyping = ref(false)

let observer: IntersectionObserver | null = null
let typeTimer: ReturnType<typeof setTimeout> | null = null

function clearTypeTimer() {
  if (typeTimer) {
    clearTimeout(typeTimer)
    typeTimer = null
  }
}

function lineVisible(blockIdx: number, lineIdx: number) {
  return lineIdx < typedLines.value[blockIdx].length
}

function showCursor(blockIdx: number, lineIdx: number) {
  return (
    hasStarted.value &&
    isTyping.value &&
    activeBlock.value === blockIdx &&
    activeLine.value === lineIdx
  )
}

function typeCharacter(blockIdx: number, lineIdx: number, charIdx: number) {
  const text = blocks[blockIdx].lines[lineIdx]

  if (charIdx <= text.length) {
    typedLines.value[blockIdx][lineIdx] = text.slice(0, charIdx)
    isTyping.value = charIdx < text.length
    typeTimer = setTimeout(() => typeCharacter(blockIdx, lineIdx, charIdx + 1), 34)
    return
  }

  isTyping.value = false
  const nextLine = lineIdx + 1

  if (nextLine < blocks[blockIdx].lines.length) {
    activeLine.value = nextLine
    typedLines.value[blockIdx].push('')
    typeTimer = setTimeout(() => typeCharacter(blockIdx, nextLine, 0), 260)
    return
  }

  const nextBlock = blockIdx + 1
  if (nextBlock < blocks.length) {
    visibleBlockCount.value = nextBlock + 1
    activeBlock.value = nextBlock
    activeLine.value = 0
    typedLines.value[nextBlock] = ['']
    typeTimer = setTimeout(() => typeCharacter(nextBlock, 0, 0), 420)
  }
}

function startAnimation() {
  if (hasStarted.value) return
  hasStarted.value = true
  visibleBlockCount.value = 1
  activeBlock.value = 0
  activeLine.value = 0
  typedLines.value = [['']]
  typeCharacter(0, 0, 0)
}

onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        startAnimation()
        observer?.disconnect()
      }
    },
    { threshold: 0.2, rootMargin: '0px 0px -8% 0px' },
  )

  if (sectionRef.value) observer.observe(sectionRef.value)
})

onUnmounted(() => {
  observer?.disconnect()
  clearTypeTimer()
})
</script>

<template>
  <section id="developers" ref="sectionRef" class="py-24 lg:py-32 bg-muted/50">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <div class="text-center mb-16">
        <span class="inline-flex items-center gap-2 text-sm font-mono text-muted-foreground mb-4">
          <span class="w-6 h-px bg-foreground/30" />
          使用示例
        </span>
        <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
          像聊天一样简单
        </h2>
        <p class="text-lg text-muted-foreground">
          无需复杂配置，输入需求即可调用最强 AI 能力。
        </p>
      </div>

      <div class="max-w-3xl mx-auto">
        <div class="rounded-2xl bg-card border border-border overflow-hidden">
          <div class="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b border-border">
            <Terminal class="w-4 h-4 text-muted-foreground" />
            <span class="text-sm font-mono text-muted-foreground">terminal</span>
          </div>
          <div class="min-h-[280px] p-6 text-sm font-mono leading-relaxed overflow-x-auto">
            <template v-for="(block, blockIdx) in blocks" :key="blockIdx">
              <template v-if="blockIdx < visibleBlockCount">
                <div class="text-foreground whitespace-pre-wrap">{{ block.command }}</div>
                <div
                  v-for="(line, lineIdx) in block.lines"
                  v-show="lineVisible(blockIdx, lineIdx)"
                  :key="`${blockIdx}-${lineIdx}-${line.length}`"
                  class="text-foreground whitespace-pre-wrap"
                >
                  {{ typedLines[blockIdx][lineIdx] }}<span
                    v-if="showCursor(blockIdx, lineIdx)"
                    class="terminal-cursor"
                  >|</span>
                </div>
                <div v-if="blockIdx < blocks.length - 1 && blockIdx < visibleBlockCount - 1" class="h-6" />
              </template>
            </template>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.terminal-cursor {
  display: inline-block;
  margin-left: 1px;
  animation: terminal-blink 0.9s step-end infinite;
}

@keyframes terminal-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>
