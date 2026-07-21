<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { BookOpen, Headphones, Loader2, Play, RefreshCw, X } from "lucide-vue-next"
import { fetchLearningCenter, type LearningCategory, type LearningCenterResponse, type LearningTutorial } from "@/api/learningCenterApi"
import { getRequestBaseUrl } from "@/api/client"
import { useAuthStore } from "@/store/authStore"

const DEFAULT_TEACHER_QR = "https://cdn.wlcloudai.com/static/kf.jpg"
const auth = useAuthStore()
const data = ref<LearningCenterResponse | null>(null)
const loading = ref(true)
const error = ref("")
const activeCategoryId = ref<number | null>(null)
const selectedTutorial = ref<LearningTutorial | null>(null)
const contactOpen = ref(false)
const videoError = ref(false)
const teacherQrBroken = ref(false)

const categories = computed(() => data.value?.categories ?? [])
const activeCategory = computed<LearningCategory | null>(() => {
  return categories.value.find((item) => item.id === activeCategoryId.value) ?? categories.value[0] ?? null
})
const teacherContact = computed(() => data.value?.teacherContact)
const teacherQr = computed(() => teacherContact.value?.qrCodeUrl?.trim() || DEFAULT_TEACHER_QR)

function mediaUrl(value?: string) {
  const raw = value?.trim()
  if (!raw || /^https?:\/\//i.test(raw) || raw.startsWith("data:")) return raw || ""
  return new URL(raw.startsWith("/") ? raw : `/${raw}`, getRequestBaseUrl()).toString()
}

async function load() {
  loading.value = true
  error.value = ""
  try {
    data.value = await fetchLearningCenter({ token: auth.token })
    const stillExists = data.value.categories.some((item) => item.id === activeCategoryId.value)
    if (!stillExists) activeCategoryId.value = data.value.categories[0]?.id ?? null
  } catch (err) {
    error.value = err instanceof Error ? err.message : "学习中心加载失败"
  } finally {
    loading.value = false
  }
}

function openTutorial(tutorial: LearningTutorial) {
  videoError.value = false
  selectedTutorial.value = tutorial
}

function closeOverlays() {
  selectedTutorial.value = null
  contactOpen.value = false
}

function openContact() {
  teacherQrBroken.value = false
  contactOpen.value = true
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") closeOverlays()
}

watch(selectedTutorial, () => { videoError.value = false })

onMounted(() => {
  void load()
  document.addEventListener("keydown", onKeydown)
})
onUnmounted(() => document.removeEventListener("keydown", onKeydown))
</script>

<template>
  <div class="learning-center min-h-full px-4 py-6 sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1240px]">
      <header class="learning-header flex flex-col gap-5 border-b border-white/[0.08] pb-7 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div class="mb-3 flex items-center gap-2 text-sm font-medium text-[var(--brand-active-text)]">
            <BookOpen class="h-4 w-4" aria-hidden="true" />
            AI 学习路径
          </div>
          <h1 class="text-3xl font-semibold text-white sm:text-4xl">学习中心</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-white/52 sm:text-base">
            从基础操作到进阶实战，按阶段掌握 AI 工具与内容创作方法。
          </p>
        </div>
        <button
          v-if="teacherContact?.enabled"
          type="button"
          class="inline-flex h-11 w-fit items-center justify-center gap-2 rounded-lg border border-[var(--brand-border)] bg-[var(--brand-softer)] px-4 text-sm font-semibold text-[var(--brand-active-text)] transition hover:bg-[var(--brand-soft)] hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          @click="openContact"
        >
          <Headphones class="h-[18px] w-[18px]" aria-hidden="true" />
          联系老师
        </button>
      </header>

      <div v-if="loading" class="flex min-h-[420px] items-center justify-center text-sm text-white/48">
        <Loader2 class="mr-2 h-5 w-5 animate-spin" aria-hidden="true" />
        正在加载课程
      </div>

      <div v-else-if="error" class="mt-8 flex min-h-[320px] flex-col items-center justify-center rounded-lg border border-red-400/20 bg-red-400/[0.04] px-6 text-center">
        <p class="text-sm text-red-200">{{ error }}</p>
        <button type="button" class="mt-4 inline-flex items-center gap-2 rounded-lg border border-white/10 px-4 py-2 text-sm text-white/70 hover:bg-white/[0.06] hover:text-white" @click="load">
          <RefreshCw class="h-4 w-4" />重新加载
        </button>
      </div>

      <template v-else>
        <nav v-if="categories.length" class="category-tabs mt-7 flex gap-2 overflow-x-auto pb-2" aria-label="课程分类">
          <button
            v-for="category in categories"
            :key="category.id"
            type="button"
            class="shrink-0 rounded-lg border px-4 py-2.5 text-sm font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            :class="activeCategory?.id === category.id ? 'border-[var(--brand-border)] bg-[var(--brand-softer)] text-[var(--brand-active-text)]' : 'border-white/[0.08] bg-white/[0.025] text-white/55 hover:border-white/15 hover:text-white/85'"
            @click="activeCategoryId = category.id"
          >
            {{ category.name }}
            <span class="ml-1.5 text-xs opacity-55">{{ category.tutorials.length }}</span>
          </button>
        </nav>

        <section v-if="activeCategory" class="mt-7" :aria-labelledby="`learning-category-${activeCategory.id}`">
          <div class="mb-4 flex items-center justify-between gap-4">
            <div>
              <h2 :id="`learning-category-${activeCategory.id}`" class="text-xl font-semibold text-white">{{ activeCategory.name }}</h2>
              <p class="mt-1 text-sm text-white/42">共 {{ activeCategory.tutorials.length }} 节视频课程</p>
            </div>
          </div>

          <div v-if="activeCategory.tutorials.length" class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            <button
              v-for="tutorial in activeCategory.tutorials"
              :key="tutorial.id"
              type="button"
              class="tutorial-card group overflow-hidden rounded-lg border border-white/[0.08] bg-white/[0.025] text-left transition hover:-translate-y-0.5 hover:border-[var(--brand-border)] hover:bg-white/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              @click="openTutorial(tutorial)"
            >
              <span class="relative block aspect-video overflow-hidden bg-[#18191d]">
                <img v-if="tutorial.coverImageUrl" :src="mediaUrl(tutorial.coverImageUrl)" :alt="tutorial.title" class="h-full w-full object-cover transition duration-300 group-hover:scale-[1.02]" />
                <span v-else class="flex h-full w-full items-center justify-center text-white/20"><BookOpen class="h-10 w-10" /></span>
                <span class="absolute inset-0 bg-black/10 transition group-hover:bg-black/25" />
                <span class="absolute bottom-3 right-3 inline-flex h-10 w-10 items-center justify-center rounded-full bg-black/70 text-white ring-1 ring-white/20 backdrop-blur-sm transition group-hover:bg-[var(--brand-strong)]">
                  <Play class="ml-0.5 h-4 w-4 fill-current" aria-hidden="true" />
                </span>
              </span>
              <span class="block p-4">
                <span class="block truncate text-base font-semibold text-white">{{ tutorial.title }}</span>
                <span class="mt-2 line-clamp-2 block min-h-10 text-sm leading-5 text-white/48">{{ tutorial.summary || "点击开始学习本节课程" }}</span>
              </span>
            </button>
          </div>
          <div v-else class="flex min-h-[280px] flex-col items-center justify-center rounded-lg border border-dashed border-white/[0.1] bg-white/[0.018] px-6 text-center">
            <BookOpen class="h-9 w-9 text-white/18" aria-hidden="true" />
            <p class="mt-4 text-sm font-medium text-white/68">该阶段课程正在准备中</p>
            <p class="mt-1 text-xs text-white/35">请稍后回来查看最新教程</p>
          </div>
        </section>

        <div v-else class="mt-8 flex min-h-[360px] flex-col items-center justify-center rounded-lg border border-dashed border-white/[0.1] bg-white/[0.018] px-6 text-center">
          <BookOpen class="h-10 w-10 text-white/18" aria-hidden="true" />
          <p class="mt-4 text-sm font-medium text-white/68">课程内容正在整理中</p>
          <p class="mt-1 text-xs text-white/35">学习中心上线后，课程会按阶段展示在这里</p>
        </div>
      </template>
    </div>

    <div v-if="selectedTutorial" class="fixed inset-0 z-[110] flex items-center justify-center bg-black/80 px-3 py-6 backdrop-blur-sm" @click.self="selectedTutorial = null">
      <section class="relative w-full max-w-5xl overflow-hidden rounded-lg border border-white/10 bg-[#18191d] shadow-[0_30px_100px_rgb(0_0_0_/_0.7)]" role="dialog" aria-modal="true" :aria-label="selectedTutorial.title">
        <button type="button" class="absolute right-3 top-3 z-10 inline-flex h-9 w-9 items-center justify-center rounded-full bg-black/65 text-white/70 hover:bg-black hover:text-white" aria-label="关闭视频" @click="selectedTutorial = null"><X class="h-4 w-4" /></button>
        <div class="aspect-video bg-black">
          <video v-if="!videoError" :src="selectedTutorial.videoUrl" :poster="mediaUrl(selectedTutorial.coverImageUrl)" class="h-full w-full" controls autoplay playsinline preload="metadata" @error="videoError = true" />
          <div v-else class="flex h-full flex-col items-center justify-center px-6 text-center"><p class="text-sm font-medium text-white/75">视频暂时无法播放</p><p class="mt-2 text-xs text-white/40">请检查网络或联系老师确认课程地址</p></div>
        </div>
        <div class="p-5"><h2 class="text-lg font-semibold text-white">{{ selectedTutorial.title }}</h2><p v-if="selectedTutorial.summary" class="mt-2 text-sm leading-6 text-white/48">{{ selectedTutorial.summary }}</p></div>
      </section>
    </div>

    <div v-if="contactOpen" class="fixed inset-0 z-[115] flex items-center justify-center bg-black/70 px-4 backdrop-blur-sm" @click.self="contactOpen = false">
      <section class="relative w-full max-w-sm rounded-lg border border-white/10 bg-[#1d1d22] p-6 text-center shadow-[0_24px_80px_rgb(0_0_0_/_0.55)]" role="dialog" aria-modal="true" aria-label="联系老师">
        <button type="button" class="absolute right-4 top-4 inline-flex h-8 w-8 items-center justify-center rounded-full text-white/45 hover:bg-white/10 hover:text-white" aria-label="关闭联系老师" @click="contactOpen = false"><X class="h-4 w-4" /></button>
        <div class="mx-auto flex h-12 w-12 items-center justify-center rounded-lg bg-[var(--brand-softer)] text-[var(--brand-active-text)] ring-1 ring-[var(--brand-border)]"><Headphones class="h-6 w-6" /></div>
        <h2 class="mt-4 text-xl font-semibold text-white">联系老师</h2>
        <p class="mt-2 text-sm leading-6 text-white/55">{{ teacherContact?.description || "扫码添加老师，获取课程学习支持" }}</p>
        <div class="mx-auto mt-5 flex aspect-square w-56 max-w-full items-center justify-center rounded-lg bg-white p-3">
          <img v-if="!teacherQrBroken" :src="mediaUrl(teacherQr)" alt="联系老师二维码" class="h-full w-full rounded-md object-contain" @error="teacherQrBroken = true" />
          <div v-else class="flex h-full w-full items-center justify-center px-4 text-xs leading-5 text-slate-500">二维码暂时无法加载，请稍后重试</div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.learning-center { background: #111111; }
.category-tabs { scrollbar-width: thin; scrollbar-color: rgb(255 255 255 / 0.15) transparent; }
.category-tabs::-webkit-scrollbar { height: 5px; }
.category-tabs::-webkit-scrollbar-thumb { border-radius: 999px; background: rgb(255 255 255 / 0.14); }
@media (prefers-reduced-motion: reduce) {
  .tutorial-card, .tutorial-card img { transition: none; }
  .tutorial-card:hover { transform: none; }
}
</style>
