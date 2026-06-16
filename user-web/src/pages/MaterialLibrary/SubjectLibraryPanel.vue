<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { LoaderCircle, Plus, RefreshCw, Trash2, UserRound, WandSparkles } from "lucide-vue-next"
import {
  createSubject,
  deleteSubject,
  fetchSubjects,
  retrySubjectSync,
  type GenerationSubject,
} from "@/api/subjectApi"
import { uploadToolFile } from "@/api/toolApi"
import { getApiOrigin } from "@/api/client"
import { confirmDelete } from "@/composables/useConfirmDelete"
import { useAuthStore } from "@/store/authStore"
import { userRoutes } from "@/router/userRoutes"
import { useRouter } from "vue-router"

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const error = ref("")
const subjects = ref<GenerationSubject[]>([])
const showCreate = ref(false)
const creating = ref(false)
const syncingCode = ref<string | null>(null)
const deletingCode = ref<string | null>(null)

const form = ref({
  displayName: "",
  description: "",
  referenceType: "image_refer" as "image_refer" | "video_refer",
  frontalImage: "",
  referImages: [] as string[],
  referVideo: "",
})

const readyCount = computed(() => subjects.value.filter((item) => item.syncStatus === "READY").length)

function previewUrl(url?: string | null): string {
  const raw = typeof url === "string" ? url.trim() : ""
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const origin = getApiOrigin()
  return origin ? `${origin}${raw.startsWith("/") ? raw : `/${raw}`}` : raw
}

function syncStatusLabel(status: string): string {
  if (status === "READY") return "已就绪"
  if (status === "PENDING") return "同步中"
  if (status === "FAILED") return "失败"
  return status
}

function syncStatusClass(status: string): string {
  if (status === "READY") return "bg-emerald-500/15 text-emerald-300"
  if (status === "PENDING") return "bg-amber-500/15 text-amber-300"
  if (status === "FAILED") return "bg-red-500/15 text-red-300"
  return "bg-white/10 text-white/70"
}

async function loadSubjects() {
  if (!auth.token) return
  loading.value = true
  error.value = ""
  try {
    const response = await fetchSubjects({ token: auth.token, provider: "kling_video", pageNo: 1, pageSize: 100 })
    subjects.value = response.list
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载主体库失败"
  } finally {
    loading.value = false
  }
}

async function uploadReference(file: File | undefined, target: "frontal" | "refer" | "video") {
  if (!auth.token || !file) return
  const uploaded = await uploadToolFile(file, { token: auth.token })
  if (target === "frontal") {
    form.value.frontalImage = uploaded.url
  } else if (target === "refer") {
    form.value.referImages = [...form.value.referImages, uploaded.url].slice(0, 3)
  } else {
    form.value.referVideo = uploaded.url
  }
}

async function submitCreate() {
  if (!auth.token) return
  if (!form.value.displayName.trim()) {
    window.alert("请填写主体名称")
    return
  }
  if (form.value.referenceType === "image_refer") {
    if (!form.value.frontalImage.trim()) {
      window.alert("请上传正面图")
      return
    }
    if (form.value.referImages.length === 0) {
      window.alert("请至少上传 1 张参考图（可灵要求正面图 + 1~3 张其他角度参考图）")
      return
    }
  } else if (!form.value.referVideo.trim()) {
    window.alert("请上传参考视频")
    return
  }
  const referenceJson =
    form.value.referenceType === "image_refer"
      ? {
          frontalImage: form.value.frontalImage.trim(),
          referImages: form.value.referImages.filter(Boolean),
        }
      : { referVideos: form.value.referVideo.trim() ? [form.value.referVideo.trim()] : [] }

  creating.value = true
  error.value = ""
  try {
    await createSubject({
      token: auth.token,
      payload: {
        displayName: form.value.displayName.trim(),
        description: form.value.description.trim() || undefined,
        referenceType: form.value.referenceType,
        referenceJson,
      },
    })
    showCreate.value = false
    form.value = {
      displayName: "",
      description: "",
      referenceType: "image_refer",
      frontalImage: "",
      referImages: [],
      referVideo: "",
    }
    await loadSubjects()
  } catch (err) {
    error.value = err instanceof Error ? err.message : "创建主体失败"
  } finally {
    creating.value = false
  }
}

async function handleRetry(subject: GenerationSubject) {
  if (!auth.token) return
  syncingCode.value = subject.subjectCode
  try {
    await retrySubjectSync({ token: auth.token, subjectCode: subject.subjectCode })
    await loadSubjects()
  } catch (err) {
    window.alert(err instanceof Error ? err.message : "重试同步失败")
  } finally {
    syncingCode.value = null
  }
}

async function handleDelete(subject: GenerationSubject) {
  if (!auth.token) return
  const confirmed = await confirmDelete(`确定删除主体「${subject.displayName}」吗？`)
  if (!confirmed) return
  deletingCode.value = subject.subjectCode
  try {
    await deleteSubject({ token: auth.token, subjectCode: subject.subjectCode })
    subjects.value = subjects.value.filter((item) => item.subjectCode !== subject.subjectCode)
  } catch (err) {
    window.alert(err instanceof Error ? err.message : "删除失败")
  } finally {
    deletingCode.value = null
  }
}

function useForOmni(subject: GenerationSubject) {
  if (subject.syncStatus !== "READY" || !subject.upstreamElementId) {
    window.alert("主体尚未同步完成，无法用于创作")
    return
  }
  router.push({
    name: userRoutes.dashboard.name,
    query: {
      tool: "kling-video-o1-omni",
      subjectCode: subject.subjectCode,
      elementId: subject.upstreamElementId,
    },
  })
}

onMounted(loadSubjects)
</script>

<template>
  <div class="space-y-6">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p class="text-sm text-white/60">已就绪 {{ readyCount }} / {{ subjects.length }} 个主体</p>
      <button
        type="button"
        class="inline-flex items-center gap-2 rounded-xl bg-violet-600 px-4 py-2 text-sm font-medium text-white hover:bg-violet-500"
        @click="showCreate = true"
      >
        <Plus class="h-4 w-4" />
        添加主体
      </button>
    </div>

    <p v-if="error" class="rounded-xl border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-200">{{ error }}</p>

    <div v-if="loading" class="flex items-center justify-center py-16 text-white/60">
      <LoaderCircle class="mr-2 h-5 w-5 animate-spin" />
      加载中...
    </div>

    <div v-else-if="subjects.length === 0" class="rounded-2xl border border-dashed border-white/10 px-6 py-16 text-center text-white/50">
      暂无主体，点击「添加主体」创建参考对象
    </div>

    <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      <article
        v-for="subject in subjects"
        :key="subject.subjectCode"
        class="overflow-hidden rounded-2xl border border-white/10 bg-white/[0.03]"
      >
        <div class="aspect-[4/3] bg-black/30">
          <img
            v-if="subject.previewUrl"
            :src="previewUrl(subject.previewUrl)"
            :alt="subject.displayName"
            class="h-full w-full object-cover"
          />
          <div v-else class="flex h-full items-center justify-center text-white/30">
            <UserRound class="h-10 w-10" />
          </div>
        </div>
        <div class="space-y-3 p-4">
          <div class="flex items-start justify-between gap-3">
            <div>
              <h3 class="font-medium text-white">{{ subject.displayName }}</h3>
              <p class="mt-1 text-xs text-white/50">
                {{ subject.referenceType === "video_refer" ? "视频主体" : "图片主体" }}
              </p>
            </div>
            <span class="rounded-full px-2 py-0.5 text-xs" :class="syncStatusClass(subject.syncStatus)">
              {{ syncStatusLabel(subject.syncStatus) }}
            </span>
          </div>
          <p v-if="subject.upstreamElementId" class="truncate text-xs text-white/45">element_id: {{ subject.upstreamElementId }}</p>
          <p v-if="subject.syncError" class="text-xs text-red-300">{{ subject.syncError }}</p>
          <div class="flex flex-wrap gap-2">
            <button
              type="button"
              class="inline-flex items-center gap-1 rounded-lg bg-violet-600/90 px-3 py-1.5 text-xs text-white hover:bg-violet-500 disabled:opacity-40"
              :disabled="subject.syncStatus !== 'READY'"
              @click="useForOmni(subject)"
            >
              <WandSparkles class="h-3.5 w-3.5" />
              用于 Omni 创作
            </button>
            <button
              v-if="subject.syncStatus === 'FAILED'"
              type="button"
              class="inline-flex items-center gap-1 rounded-lg border border-white/10 px-3 py-1.5 text-xs text-white/80 hover:bg-white/5"
              :disabled="syncingCode === subject.subjectCode"
              @click="handleRetry(subject)"
            >
              <RefreshCw class="h-3.5 w-3.5" />
              重试
            </button>
            <button
              type="button"
              class="inline-flex items-center gap-1 rounded-lg border border-white/10 px-3 py-1.5 text-xs text-white/70 hover:bg-white/5"
              :disabled="deletingCode === subject.subjectCode"
              @click="handleDelete(subject)"
            >
              <Trash2 class="h-3.5 w-3.5" />
              删除
            </button>
          </div>
        </div>
      </article>
    </div>

    <div
      v-if="showCreate"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      @click.self="showCreate = false"
    >
      <div class="w-full max-w-lg rounded-2xl border border-white/10 bg-[#151821] p-5 shadow-2xl">
        <h2 class="text-lg font-semibold text-white">添加主体</h2>
        <div class="mt-4 space-y-4">
          <label class="block space-y-1">
            <span class="text-sm text-white/70">名称</span>
            <input v-model="form.displayName" class="w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-white" />
          </label>
          <label class="block space-y-1">
            <span class="text-sm text-white/70">描述</span>
            <textarea v-model="form.description" rows="2" class="w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-white" />
          </label>
          <label class="block space-y-1">
            <span class="text-sm text-white/70">参考类型</span>
            <select v-model="form.referenceType" class="w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-white">
              <option value="image_refer">图片主体</option>
              <option value="video_refer">视频主体</option>
            </select>
          </label>
          <div v-if="form.referenceType === 'image_refer'" class="space-y-3">
            <label class="block space-y-1">
              <span class="text-sm text-white/70">正面图（必填）</span>
              <input type="file" accept="image/*" @change="(e) => uploadReference((e.target as HTMLInputElement).files?.[0], 'frontal')" />
            </label>
            <label class="block space-y-1">
              <span class="text-sm text-white/70">参考图（必填 1~3 张，与正面图角度不同）</span>
              <input type="file" accept="image/*" @change="(e) => uploadReference((e.target as HTMLInputElement).files?.[0], 'refer')" />
            </label>
            <p v-if="form.referImages.length" class="text-xs text-white/50">已上传 {{ form.referImages.length }} 张参考图</p>
          </div>
          <label v-else class="block space-y-1">
            <span class="text-sm text-white/70">参考视频</span>
            <input type="file" accept="video/*" @change="(e) => uploadReference((e.target as HTMLInputElement).files?.[0], 'video')" />
          </label>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="rounded-xl px-4 py-2 text-sm text-white/70 hover:bg-white/5" @click="showCreate = false">取消</button>
          <button
            type="button"
            class="rounded-xl bg-violet-600 px-4 py-2 text-sm text-white hover:bg-violet-500 disabled:opacity-50"
            :disabled="creating"
            @click="submitCreate"
          >
            {{ creating ? "创建中..." : "创建并同步" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
