<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useReducedMotion } from '@/composables/useReducedMotion'

interface HeadlineSegment {
  text: string
  fontSize: string
  fontWeight: number
  tone: 'white' | 'softPurple' | 'purple'
  spacingBefore?: string
  spacingAfter?: string
}

interface HeadlineLine {
  lineIndex: number
  segments: HeadlineSegment[]
}

const LINES: HeadlineLine[] = [
  {
    lineIndex: 0,
    segments: [
      { text: '一个平台', fontSize: 'clamp(2.2rem, 7.8vw, 68px)', fontWeight: 700, tone: 'white' },
    ],
  },
  {
    lineIndex: 1,
    segments: [
      { text: '满足您的', fontSize: 'clamp(2.4rem, 9vw, 68px)', fontWeight: 600, tone: 'softPurple' },
      {
        text: '所有',
        fontSize: 'clamp(2.85rem, 11vw, 96px)',
        fontWeight: 800,
        tone: 'purple',
        spacingBefore: '0.28em',
        spacingAfter: '0.28em',
      },
      { text: '创作需求', fontSize: 'clamp(2.4rem, 9vw, 68px)', fontWeight: 600, tone: 'softPurple' },
    ],
  },
]

const CHAR_STAGGER_MS = 55
const LINE2_DELAY_MS = 140
const CHAR_TRANSITION_MS = 420
const HOVER_RESET_MS = 320
const GLOW_LERP = 0.14
const MAX_GLOW_SHIFT_PX = 10
const MAX_TEXT_SHIFT_PX = 6
const MAX_GLOW_OPACITY = 0.28

const headlineRef = ref<HTMLElement | null>(null)
const animationStarted = ref(false)
const animationReady = ref(false)
const visibleChars = ref<Set<string>>(new Set())
const pointerFine = ref(false)
const pointerInside = ref(false)
const hoveredChar = ref<{ line: number; index: number } | null>(null)

const glowTarget = { x: 0, y: 0, opacity: 0.12 }
const glowCurrent = { x: 0, y: 0, opacity: 0.12 }
const textTarget = { x: 0, y: 0 }
const textCurrent = { x: 0, y: 0 }

const glowStyle = ref<Record<string, string>>({})
const textDriftStyle = ref<Record<string, string>>({})

const { reducedMotion } = useReducedMotion()

let observer: IntersectionObserver | null = null
let charTimers: ReturnType<typeof setTimeout>[] = []
let hoverResetTimer: ReturnType<typeof setTimeout> | null = null
let rafId: number | null = null
let pointerMq: MediaQueryList | null = null
let syncPointerFine: (() => void) | null = null

function charKey(lineIndex: number, charIndex: number) {
  return `${lineIndex}-${charIndex}`
}

function splitChars(text: string) {
  return Array.from(text)
}

function lineCharCount(line: HeadlineLine) {
  return line.segments.reduce((sum, seg) => sum + splitChars(seg.text).length, 0)
}

function isCharVisible(lineIndex: number, charIndex: number) {
  return visibleChars.value.has(charKey(lineIndex, charIndex))
}

function charClass(lineIndex: number, charIndex: number) {
  const classes = ['hero-headline__char']
  if (isCharVisible(lineIndex, charIndex)) classes.push('hero-headline__char--visible')
  const hovered = hoveredChar.value
  if (hovered && hovered.line === lineIndex) {
    if (hovered.index === charIndex) classes.push('hero-headline__char--active')
    else if (Math.abs(hovered.index - charIndex) === 1) classes.push('hero-headline__char--neighbor')
  }
  return classes
}

function clearCharTimers() {
  for (const timer of charTimers) clearTimeout(timer)
  charTimers = []
}

function scheduleCharReveal(lineIndex: number, charIndex: number, delayMs: number) {
  const timer = setTimeout(() => {
    const next = new Set(visibleChars.value)
    next.add(charKey(lineIndex, charIndex))
    visibleChars.value = next
  }, delayMs)
  charTimers.push(timer)
}

function markAnimationReady() {
  animationReady.value = true
}

function startCharAnimation() {
  if (animationStarted.value) return
  animationStarted.value = true
  clearCharTimers()

  if (reducedMotion.value) {
    const all = new Set<string>()
    LINES.forEach((line) => {
      for (let ci = 0; ci < lineCharCount(line); ci++) all.add(charKey(line.lineIndex, ci))
    })
    visibleChars.value = all
    animationReady.value = true
    syncGlowStyles()
    return
  }

  let maxDelay = 0
  LINES.forEach((line) => {
    const lineDelay = line.lineIndex === 0 ? 0 : LINE2_DELAY_MS
    const count = lineCharCount(line)
    for (let ci = 0; ci < count; ci++) {
      const delay = lineDelay + ci * CHAR_STAGGER_MS
      maxDelay = Math.max(maxDelay, delay + CHAR_TRANSITION_MS)
      scheduleCharReveal(line.lineIndex, ci, delay)
    }
  })

  const readyTimer = setTimeout(markAnimationReady, maxDelay + 120)
  charTimers.push(readyTimer)
}

function syncGlowStyles() {
  glowStyle.value = {
    transform: `translate(calc(-50% + ${glowCurrent.x}px), calc(-50% + ${glowCurrent.y}px))`,
  }
  if (pointerInside.value) {
    glowStyle.value.opacity = String(Math.min(glowCurrent.opacity, MAX_GLOW_OPACITY))
  }
  textDriftStyle.value = {
    transform: `translate(${textCurrent.x}px, ${textCurrent.y}px)`,
    transition: 'transform 240ms ease-out',
  }
}

function tickSmoothing() {
  glowCurrent.x += (glowTarget.x - glowCurrent.x) * GLOW_LERP
  glowCurrent.y += (glowTarget.y - glowCurrent.y) * GLOW_LERP
  glowCurrent.opacity += (glowTarget.opacity - glowCurrent.opacity) * GLOW_LERP
  textCurrent.x += (textTarget.x - textCurrent.x) * GLOW_LERP
  textCurrent.y += (textTarget.y - textCurrent.y) * GLOW_LERP
  syncGlowStyles()

  const settled =
    Math.abs(glowTarget.x - glowCurrent.x) < 0.05 &&
    Math.abs(glowTarget.y - glowCurrent.y) < 0.05 &&
    Math.abs(glowTarget.opacity - glowCurrent.opacity) < 0.005 &&
    Math.abs(textTarget.x - textCurrent.x) < 0.05 &&
    Math.abs(textTarget.y - textCurrent.y) < 0.05

  if (!settled || pointerInside.value) {
    rafId = requestAnimationFrame(tickSmoothing)
  } else {
    rafId = null
  }
}

function ensureAnimationLoop() {
  if (rafId == null) rafId = requestAnimationFrame(tickSmoothing)
}

function resetPointerTargets() {
  glowTarget.x = 0
  glowTarget.y = 0
  glowTarget.opacity = animationReady.value ? 0.16 : 0.12
  textTarget.x = 0
  textTarget.y = 0
  ensureAnimationLoop()
}

function updatePointerTargets(clientX: number, clientY: number) {
  const el = headlineRef.value
  if (!el || !pointerFine.value) return

  const rect = el.getBoundingClientRect()
  const centerX = rect.left + rect.width / 2
  const centerY = rect.top + rect.height / 2
  const relX = (clientX - centerX) / Math.max(rect.width / 2, 1)
  const relY = (clientY - centerY) / Math.max(rect.height / 2, 1)
  const dist = Math.min(Math.hypot(relX, relY), 1.6)
  const proximity = Math.max(0, 1 - dist / 1.6)

  glowTarget.x = relX * MAX_GLOW_SHIFT_PX
  glowTarget.y = relY * MAX_GLOW_SHIFT_PX * 0.6
  glowTarget.opacity = 0.12 + proximity * (MAX_GLOW_OPACITY - 0.12)
  textTarget.x = relX * MAX_TEXT_SHIFT_PX
  textTarget.y = relY * MAX_TEXT_SHIFT_PX * 0.5
  ensureAnimationLoop()
}

function onHeadlineMouseMove(event: MouseEvent) {
  if (!pointerFine.value) return
  pointerInside.value = true
  updatePointerTargets(event.clientX, event.clientY)
}

function onHeadlineMouseLeave() {
  if (!pointerFine.value) return
  pointerInside.value = false
  resetPointerTargets()
}

function onCharEnter(lineIndex: number, charIndex: number) {
  if (!pointerFine.value) return
  if (hoverResetTimer) {
    clearTimeout(hoverResetTimer)
    hoverResetTimer = null
  }
  hoveredChar.value = { line: lineIndex, index: charIndex }
}

function onCharLeave() {
  if (!pointerFine.value) return
  if (hoverResetTimer) clearTimeout(hoverResetTimer)
  hoverResetTimer = setTimeout(() => {
    hoveredChar.value = null
    hoverResetTimer = null
  }, HOVER_RESET_MS)
}

function segmentCharStart(line: HeadlineLine, segmentIndex: number) {
  let start = 0
  for (let i = 0; i < segmentIndex; i++) {
    start += splitChars(line.segments[i].text).length
  }
  return start
}

// 全局文字呼吸控制类
const containerClass = computed(() => [
  'hero-headline',
  animationReady.value ? 'hero-headline--ready' : '',
  animationReady.value && !pointerInside ? 'global-breathe' : '',
])

onMounted(() => {
  pointerMq = window.matchMedia('(pointer: fine) and (min-width: 768px)')
  syncPointerFine = () => {
    pointerFine.value = pointerMq?.matches ?? false
    if (!pointerFine.value) hoveredChar.value = null
  }
  syncPointerFine()
  pointerMq.addEventListener('change', syncPointerFine)

  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        startCharAnimation()
        observer?.disconnect()
      }
    },
    { threshold: 0.2, rootMargin: '0px 0px -8% 0px' },
  )

  if (headlineRef.value) observer.observe(headlineRef.value)
  syncGlowStyles()
})

onUnmounted(() => {
  observer?.disconnect()
  clearCharTimers()
  if (hoverResetTimer) clearTimeout(hoverResetTimer)
  if (rafId != null) cancelAnimationFrame(rafId)
  if (pointerMq && syncPointerFine) pointerMq.removeEventListener('change', syncPointerFine)
})
</script>

<template>
  <div class="flex min-h-0 items-center">
    <div
      ref="headlineRef"
      :class="containerClass"
      @mousemove="onHeadlineMouseMove"
      @mouseleave="onHeadlineMouseLeave"
    >
      <div class="hero-headline__glow" aria-hidden="true" :style="glowStyle" />
      <h1 class="hero-headline__text font-display tracking-tight" :style="textDriftStyle">
        <span
          v-for="line in LINES"
          :key="line.lineIndex"
          class="hero-headline__line block"
        >
          <span
            v-for="(segment, si) in line.segments"
            :key="`${line.lineIndex}-${si}`"
            class="hero-headline__segment"
            :class="[
              `hero-headline__segment--${segment.tone}`,
              // 紫色「所有」单独叠加更强呼吸
              segment.tone === 'purple' && animationReady && !pointerInside ? 'strong-breathe' : ''
            ]"
            :style="{
              fontSize: segment.fontSize,
              fontWeight: segment.fontWeight,
              marginLeft: segment.spacingBefore,
              marginRight: segment.spacingAfter,
            }"
          >
            <span
              v-for="(char, ci) in splitChars(segment.text)"
              :key="charKey(line.lineIndex, segmentCharStart(line, si) + ci)"
              :class="charClass(line.lineIndex, segmentCharStart(line, si) + ci)"
              @mouseenter="onCharEnter(line.lineIndex, segmentCharStart(line, si) + ci)"
              @mouseleave="onCharLeave"
            >{{ char }}</span>
          </span>
        </span>
      </h1>
    </div>
  </div>
</template>

<style scoped>
.hero-headline {
  position: relative;
  isolation: isolate;
  cursor: pointer;
  margin-left: 0;
}

/* 全局文字轻微呼吸动画 */
.global-breathe .hero-headline__text {
  animation: globalTextBreathe 2s ease-in-out infinite;
}
/* 鼠标进入暂停全局呼吸 */
.hero-headline:hover .hero-headline__text {
  animation-play-state: paused;
}

.hero-headline__glow {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 0;
  width: min(50vw, 340px);
  height: min(19vw, 120px);
  border-radius: 50%;
  background: radial-gradient(
    ellipse at center,
    rgb(220 165 255 / 0.28) 0%,
    rgb(186 115 240 / 0.15) 42%,
    transparent 70%
  );
  pointer-events: none;
  will-change: transform, opacity;
  filter: blur(0.4px);
}
.hero-headline--ready .hero-headline__glow {
  animation: heroGlowBreath 3.6s ease-in-out infinite;
}

.hero-headline__text {
  position: relative;
  z-index: 1;
  margin: 0;
  line-height: 1.05;
  will-change: transform;
}

.hero-headline__line {
  text-align: left;
}
.hero-headline__segment {
  display: inline-block;
  will-change: transform;
}

/* 所有二字更强、幅度更大的呼吸 */
.strong-breathe {
  animation: strongWordBreathe 2.8s ease-in-out infinite;
}
.strong-breathe:hover {
  animation-play-state: paused;
}

.hero-headline__line + .hero-headline__line {
  margin-top: 0.18em;
}

/* 每个字独立白→淡紫渐变 */
.hero-headline__char {
  display: inline-block;
  opacity: 0;
  transform: translateY(24px) scale(0.86);
  background: linear-gradient(90deg, #ffffff, #b89aff);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  transition:
    opacity 420ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 420ms cubic-bezier(0.22, 1, 0.36, 1),
    filter 280ms ease-out;
}

.hero-headline__char--visible {
  opacity: 1;
  transform: translateY(0) scale(1);
}

/* hover提亮 */
.hero-headline__char--active {
  filter: brightness(1.25);
}
.hero-headline__char--neighbor {
  filter: brightness(1.12);
}

/* 底层光晕呼吸 */
@keyframes heroGlowBreath {
  0%, 100% {
    opacity: 0.11;
    filter: blur(0.2px);
  }
  50% {
    opacity: 0.26;
    filter: blur(0.6px);
  }
}

/* 全局整体缓慢轻微呼吸 */
@keyframes globalTextBreathe {
  0%, 100% {
    transform: translateY(0) scale(1);
  }
  50% {
    transform: translateY(-2px) scale(1.01);
  }
}

/* 「所有」专属强化呼吸，幅度更大、速度更快，视觉突出 */
@keyframes strongWordBreathe {
  0%, 100% {
    transform: translateY(0) scale(1);
  }
  50% {
    transform: translateY(-4px) scale(1.025);
  }
}

@media (prefers-reduced-motion: reduce) {
  .hero-headline__char {
    opacity: 1;
    transform: none;
    background: #fff;
    -webkit-background-clip: unset;
    color: #fff;
    transition: filter 280ms ease-out;
  }
  .global-breathe .hero-headline__text {
    animation: none;
  }
  .strong-breathe {
    animation: none;
  }
  .hero-headline--ready .hero-headline__glow {
    animation: none;
    opacity: 0.14;
  }
  .hero-headline__text {
    transform: none !important;
  }
}
</style>