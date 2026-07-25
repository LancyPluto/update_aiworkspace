<script setup lang="ts">
import { onMounted } from "vue"
import { useRouter } from "vue-router"
import { Clock3, FilePlus2, Presentation, Sparkles } from "lucide-vue-next"
import { usePptWorkspaceStore } from "@/store/pptWorkspaceStore"
import { userRoutes } from "@/router/userRoutes"

const router = useRouter()
const workspace = usePptWorkspaceStore()

onMounted(() => {
  void workspace.loadProjects()
})

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value))
}
</script>

<template>
  <main class="h-full overflow-y-auto bg-[#070a0f] text-white">
    <section class="mx-auto w-full max-w-7xl px-6 py-10 lg:px-10">
      <header class="mb-10 flex flex-col justify-between gap-6 md:flex-row md:items-end">
        <div>
          <div class="mb-3 flex items-center gap-2 text-sm font-medium text-cyan-300">
            <Sparkles class="h-4 w-4" />
            科创岛 · AI 演示创作
          </div>
          <h1 class="text-3xl font-semibold tracking-tight md:text-4xl">PPT 工作台</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-white/50">
            先把观点想明白，再让 AI 干排版的体力活。项目、任务、版本与导出都留在同一个工作台。
          </p>
        </div>
        <button
          class="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-cyan-400 px-5 text-sm font-semibold text-slate-950 transition hover:bg-cyan-300"
          type="button"
          @click="router.push(userRoutes.pptNew)"
        >
          <FilePlus2 class="h-4 w-4" />
          新建演示
        </button>
      </header>

      <div v-if="workspace.error" class="mb-6 rounded-xl border border-rose-400/25 bg-rose-400/10 px-4 py-3 text-sm text-rose-200">
        {{ workspace.error }}
      </div>

      <div v-if="workspace.loading && !workspace.projects.length" class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <div v-for="index in 6" :key="index" class="h-48 animate-pulse rounded-2xl border border-white/8 bg-white/[0.04]" />
      </div>

      <button
        v-else-if="!workspace.projects.length"
        class="flex min-h-80 w-full flex-col items-center justify-center rounded-3xl border border-dashed border-white/15 bg-white/[0.025] text-center transition hover:border-cyan-300/50 hover:bg-cyan-300/[0.04]"
        type="button"
        @click="router.push(userRoutes.pptNew)"
      >
        <span class="mb-5 grid h-16 w-16 place-items-center rounded-2xl bg-cyan-300/10 text-cyan-300">
          <Presentation class="h-8 w-8" />
        </span>
        <strong class="text-lg">还没有 PPT 项目</strong>
        <span class="mt-2 text-sm text-white/45">用一句主题，创建你的第一份结构化演示</span>
      </button>

      <div v-else class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
        <button
          v-for="project in workspace.projects"
          :key="project.projectId"
          type="button"
          class="group overflow-hidden rounded-2xl border border-white/10 bg-[#0d121a] text-left transition hover:-translate-y-0.5 hover:border-cyan-300/40"
          @click="router.push(userRoutes.pptProject(project.projectId))"
        >
          <div class="relative flex aspect-[16/8] items-center justify-center overflow-hidden bg-[radial-gradient(circle_at_20%_20%,rgba(34,211,238,.2),transparent_34%),linear-gradient(135deg,#111827,#071016)]">
            <img
              v-if="project.latestDeck?.slides?.[0]?.previewUrl"
              :src="project.latestDeck.slides[0].previewUrl"
              alt=""
              class="h-full w-full object-cover transition duration-500 group-hover:scale-[1.02]"
            />
            <Presentation v-else class="h-10 w-10 text-cyan-200/55" />
            <span class="absolute right-3 top-3 rounded-full border border-white/10 bg-black/45 px-2.5 py-1 text-[11px] text-white/70">
              {{ project.pageCount }} 页
            </span>
          </div>
          <div class="p-5">
            <h2 class="truncate text-base font-semibold">{{ project.title }}</h2>
            <p class="mt-2 line-clamp-2 min-h-10 text-sm leading-5 text-white/45">{{ project.topic }}</p>
            <div class="mt-5 flex items-center justify-between text-xs text-white/35">
              <span class="rounded-md bg-white/5 px-2 py-1">{{ project.status }}</span>
              <span class="flex items-center gap-1"><Clock3 class="h-3.5 w-3.5" />{{ formatDate(project.updatedAt) }}</span>
            </div>
          </div>
        </button>
      </div>
    </section>
  </main>
</template>
