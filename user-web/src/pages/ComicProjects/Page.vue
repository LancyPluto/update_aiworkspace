<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue"
import { RouterLink, useRouter } from "vue-router"
import {
  ArrowRight,
  Clapperboard,
  Clock3,
  Loader2,
  Plus,
  RefreshCw,
  Search,
  Trash2,
  X,
} from "lucide-vue-next"
import {
  comicProjectApi,
  type ComicProjectSummary,
  type CreateComicProjectRequest,
} from "@/api/comicProjectApi"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const router = useRouter()
const projects = ref<ComicProjectSummary[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const keyword = ref("")
const createOpen = ref(false)
const creating = ref(false)
const createError = ref<string | null>(null)
const deletingId = ref<number | null>(null)
const deleteTarget = ref<ComicProjectSummary | null>(null)
let loadController: AbortController | null = null

const createForm = reactive<CreateComicProjectRequest>({
  title: "",
  description: "",
  aspectRatio: "9:16",
  visualStyle: "电影感国漫",
})

const filteredProjects = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  if (!query) return projects.value
  return projects.value.filter((project) =>
    [project.title, project.description, project.visualStyle]
      .some((value) => value?.toLocaleLowerCase().includes(query)),
  )
})

function formatDate(value?: string | null): string {
  if (!value) return "刚刚"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString("zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })
}

function stageLabel(project: ComicProjectSummary): string {
  const value = String(project.currentStage || project.status || "DRAFT").toUpperCase()
  if (value.includes("DELIVERY") || value.includes("COMPLETED")) return "合成交付"
  if (value.includes("SHOT") || value.includes("GENERAT")) return "镜头生成"
  if (value.includes("ASSET")) return "角色与场景"
  if (value.includes("STORYBOARD")) return "分镜整理"
  return "剧本创作"
}

async function loadProjects() {
  loadController?.abort()
  const controller = new AbortController()
  loadController = controller
  loading.value = true
  error.value = null
  try {
    const result = await comicProjectApi.list({ token: auth.token, signal: controller.signal })
    projects.value = Array.isArray(result) ? result : result.projects ?? []
  } catch (loadError) {
    if (controller.signal.aborted) return
    error.value = loadError instanceof Error ? loadError.message : "漫剧项目暂时无法加载"
    projects.value = []
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}

function openCreateDialog() {
  createError.value = null
  createOpen.value = true
}

function closeCreateDialog() {
  if (!creating.value) createOpen.value = false
}

async function createProject() {
  if (creating.value) return
  if (!createForm.title.trim()) {
    createError.value = "请填写项目名称"
    return
  }
  creating.value = true
  createError.value = null
  try {
    const created = await comicProjectApi.create({
      title: createForm.title.trim(),
      description: createForm.description?.trim() || undefined,
      aspectRatio: createForm.aspectRatio,
      visualStyle: createForm.visualStyle,
    }, { token: auth.token })
    createOpen.value = false
    createForm.title = ""
    createForm.description = ""
    await router.push(userRoutes.comicProject(created.id))
  } catch (submitError) {
    createError.value = submitError instanceof Error ? submitError.message : "项目创建失败"
  } finally {
    creating.value = false
  }
}

async function confirmDelete() {
  const target = deleteTarget.value
  if (!target || deletingId.value != null) return
  deletingId.value = target.id
  try {
    await comicProjectApi.remove(target.id, { token: auth.token })
    projects.value = projects.value.filter((project) => project.id !== target.id)
    deleteTarget.value = null
  } catch (deleteError) {
    error.value = deleteError instanceof Error ? deleteError.message : "项目删除失败"
    deleteTarget.value = null
  } finally {
    deletingId.value = null
  }
}

onMounted(loadProjects)
onBeforeUnmount(() => loadController?.abort())
</script>

<template>
  <main class="mx-auto w-full max-w-7xl px-4 py-6 sm:px-7 lg:py-8">
    <header class="flex flex-col gap-5 border-b border-border pb-6 md:flex-row md:items-end md:justify-between">
      <div class="min-w-0">
        <div class="mb-2 flex items-center gap-2 text-sm font-medium text-primary">
          <Clapperboard class="h-4 w-4" aria-hidden="true" />
          AI 漫剧
        </div>
        <h1 class="text-2xl font-semibold text-foreground">漫剧项目</h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
          按项目保存剧本、分镜、角色场景和每个镜头的生成版本。
        </p>
      </div>
      <button
        type="button"
        class="inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition hover:opacity-90"
        @click="openCreateDialog"
      >
        <Plus class="h-4 w-4" aria-hidden="true" />
        新建项目
      </button>
    </header>

    <div class="flex flex-col gap-3 border-b border-border py-4 sm:flex-row sm:items-center sm:justify-between">
      <label class="relative block w-full sm:max-w-sm">
        <Search class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <input
          v-model="keyword"
          type="search"
          placeholder="搜索项目"
          class="h-10 w-full rounded-md border border-input bg-background pl-9 pr-3 text-sm outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
        />
      </label>
      <span v-if="!loading && !error" class="text-xs text-muted-foreground">{{ filteredProjects.length }} 个项目</span>
    </div>

    <div v-if="loading" class="flex min-h-72 items-center justify-center text-sm text-muted-foreground">
      <Loader2 class="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
      正在加载项目
    </div>

    <section v-else-if="error" class="flex min-h-72 flex-col items-center justify-center text-center">
      <Clapperboard class="h-9 w-9 text-muted-foreground" aria-hidden="true" />
      <h2 class="mt-4 text-base font-semibold">无法加载漫剧项目</h2>
      <p class="mt-2 max-w-md text-sm leading-6 text-muted-foreground">{{ error }}</p>
      <button
        type="button"
        class="mt-5 inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm transition hover:bg-secondary"
        @click="loadProjects"
      >
        <RefreshCw class="h-4 w-4" aria-hidden="true" />
        重新加载
      </button>
    </section>

    <section v-else-if="filteredProjects.length" class="grid gap-4 py-6 md:grid-cols-2 xl:grid-cols-3">
      <article
        v-for="project in filteredProjects"
        :key="project.id"
        class="group relative flex min-h-52 flex-col overflow-hidden rounded-lg border border-border bg-card transition hover:border-primary/45"
      >
        <RouterLink :to="userRoutes.comicProject(project.id)" class="flex min-h-0 flex-1 flex-col p-5 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary">
          <div class="flex items-start justify-between gap-4">
            <span class="inline-flex items-center gap-1.5 rounded-md bg-primary/10 px-2 py-1 text-xs font-medium text-primary">
              {{ stageLabel(project) }}
            </span>
            <span class="text-xs text-muted-foreground">{{ project.aspectRatio || "9:16" }}</span>
          </div>
          <h2 class="mt-4 line-clamp-2 text-base font-semibold text-foreground">{{ project.title }}</h2>
          <p class="mt-2 line-clamp-2 text-sm leading-6 text-muted-foreground">
            {{ project.description || "尚未填写项目简介" }}
          </p>
          <div class="mt-auto flex items-end justify-between gap-3 pt-5 text-xs text-muted-foreground">
            <span class="min-w-0">
              <span class="block truncate">{{ project.visualStyle || "未设置画风" }}</span>
              <span class="mt-1 inline-flex items-center gap-1">
                <Clock3 class="h-3.5 w-3.5" aria-hidden="true" />
                {{ formatDate(project.updatedAt) }}
              </span>
            </span>
            <ArrowRight class="h-4 w-4 shrink-0 transition group-hover:translate-x-0.5 group-hover:text-primary" aria-hidden="true" />
          </div>
        </RouterLink>
        <button
          type="button"
          class="absolute bottom-4 right-11 inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground opacity-0 transition hover:bg-destructive/10 hover:text-destructive focus:opacity-100 group-hover:opacity-100"
          :aria-label="`删除项目 ${project.title}`"
          title="删除项目"
          @click="deleteTarget = project"
        >
          <Trash2 class="h-4 w-4" aria-hidden="true" />
        </button>
      </article>
    </section>

    <section v-else class="flex min-h-72 flex-col items-center justify-center text-center">
      <Clapperboard class="h-10 w-10 text-muted-foreground" aria-hidden="true" />
      <h2 class="mt-4 text-base font-semibold">{{ keyword ? "没有匹配的项目" : "还没有漫剧项目" }}</h2>
      <p class="mt-2 text-sm text-muted-foreground">{{ keyword ? "换一个关键词试试。" : "新建项目后即可导入剧本并开始拆分镜头。" }}</p>
      <button
        v-if="!keyword"
        type="button"
        class="mt-5 inline-flex h-10 items-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground"
        @click="openCreateDialog"
      >
        <Plus class="h-4 w-4" aria-hidden="true" />
        新建项目
      </button>
    </section>

    <div
      v-if="createOpen"
      class="fixed inset-0 z-50 flex items-end justify-center bg-black/70 p-0 sm:items-center sm:p-5"
      @click.self="closeCreateDialog"
    >
      <section class="w-full rounded-t-lg border border-border bg-card p-5 shadow-2xl sm:max-w-lg sm:rounded-lg sm:p-6" role="dialog" aria-modal="true" aria-labelledby="create-comic-project-title">
        <div class="flex items-center justify-between gap-4">
          <h2 id="create-comic-project-title" class="text-lg font-semibold">新建漫剧项目</h2>
          <button type="button" class="inline-flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground" aria-label="关闭" @click="closeCreateDialog">
            <X class="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
        <form class="mt-5 space-y-4" @submit.prevent="createProject">
          <label class="block text-sm">
            <span class="mb-2 block font-medium">项目名称</span>
            <input v-model="createForm.title" maxlength="80" autofocus class="h-10 w-full rounded-md border border-input bg-background px-3 outline-none focus:border-primary focus:ring-2 focus:ring-primary/20" placeholder="例如：午夜便利店 第一季" />
          </label>
          <label class="block text-sm">
            <span class="mb-2 block font-medium">项目简介</span>
            <textarea v-model="createForm.description" rows="3" maxlength="500" class="w-full resize-y rounded-md border border-input bg-background px-3 py-2 leading-6 outline-none focus:border-primary focus:ring-2 focus:ring-primary/20" placeholder="一句话说明故事和制作目标" />
          </label>
          <div class="grid gap-4 sm:grid-cols-2">
            <label class="block text-sm">
              <span class="mb-2 block font-medium">画面比例</span>
              <select v-model="createForm.aspectRatio" class="h-10 w-full rounded-md border border-input bg-background px-3 outline-none focus:border-primary">
                <option value="9:16">9:16 竖屏</option>
                <option value="16:9">16:9 横屏</option>
                <option value="1:1">1:1 方形</option>
              </select>
            </label>
            <label class="block text-sm">
              <span class="mb-2 block font-medium">视觉风格</span>
              <select v-model="createForm.visualStyle" class="h-10 w-full rounded-md border border-input bg-background px-3 outline-none focus:border-primary">
                <option>电影感国漫</option>
                <option>日漫赛璐璐</option>
                <option>写实厚涂</option>
                <option>轻喜剧条漫</option>
              </select>
            </label>
          </div>
          <p v-if="createError" class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">{{ createError }}</p>
          <div class="flex justify-end gap-3 border-t border-border pt-4">
            <button type="button" class="h-10 rounded-md border border-border px-4 text-sm hover:bg-secondary" :disabled="creating" @click="closeCreateDialog">取消</button>
            <button type="submit" class="inline-flex h-10 items-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-50" :disabled="creating">
              <Loader2 v-if="creating" class="h-4 w-4 animate-spin" aria-hidden="true" />
              {{ creating ? "正在创建" : "创建并进入" }}
            </button>
          </div>
        </form>
      </section>
    </div>

    <div v-if="deleteTarget" class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-5" @click.self="deleteTarget = null">
      <section class="w-full max-w-md rounded-lg border border-border bg-card p-6" role="alertdialog" aria-modal="true" aria-labelledby="delete-comic-project-title">
        <h2 id="delete-comic-project-title" class="text-base font-semibold">删除“{{ deleteTarget.title }}”</h2>
        <p class="mt-2 text-sm leading-6 text-muted-foreground">项目内的剧本、分镜和生成记录将一并删除，此操作无法撤销。</p>
        <div class="mt-6 flex justify-end gap-3">
          <button type="button" class="h-9 rounded-md border border-border px-3 text-sm hover:bg-secondary" :disabled="deletingId != null" @click="deleteTarget = null">取消</button>
          <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md bg-destructive px-3 text-sm font-medium text-destructive-foreground disabled:opacity-50" :disabled="deletingId != null" @click="confirmDelete">
            <Loader2 v-if="deletingId != null" class="h-4 w-4 animate-spin" aria-hidden="true" />
            删除项目
          </button>
        </div>
      </section>
    </div>
  </main>
</template>
