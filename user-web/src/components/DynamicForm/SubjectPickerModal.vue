<script setup lang="ts">
import { onMounted, ref, watch } from "vue"
import { LoaderCircle, UserRound, X } from "lucide-vue-next"
import { fetchSubjects, type GenerationSubject } from "@/api/subjectApi"
import { getApiOrigin } from "@/api/client"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  close: []
  select: [subject: GenerationSubject]
}>()

const auth = useAuthStore()
const loading = ref(false)
const error = ref("")
const subjects = ref<GenerationSubject[]>([])

function previewUrl(url?: string | null): string {
  if (!url) return ""
  if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) return url
  const origin = getApiOrigin()
  return origin ? `${origin}${url.startsWith("/") ? url : `/${url}`}` : url
}

async function loadSubjects() {
  if (!auth.token) return
  loading.value = true
  error.value = ""
  try {
    const response = await fetchSubjects({
      token: auth.token,
      provider: "kling_video",
      status: "READY",
      pageNo: 1,
      pageSize: 100,
    })
    subjects.value = response.list
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载主体库失败"
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.open) loadSubjects()
})

watch(
  () => props.open,
  (open) => {
    if (open) loadSubjects()
  },
)
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-[70] flex items-center justify-center bg-black/60 p-4" @click.self="emit('close')">
    <div class="flex max-h-[80vh] w-full max-w-2xl flex-col rounded-2xl border border-white/10 bg-[#151821] shadow-2xl">
      <div class="flex items-center justify-between border-b border-white/10 px-5 py-4">
        <h3 class="text-base font-semibold text-white">从主体库选择</h3>
        <button type="button" class="text-white/50 hover:text-white" @click="emit('close')">
          <X class="h-4 w-4" />
        </button>
      </div>
      <div class="overflow-y-auto p-5">
        <div v-if="loading" class="flex items-center justify-center py-10 text-white/60">
          <LoaderCircle class="mr-2 h-5 w-5 animate-spin" />
          加载中...
        </div>
        <p v-else-if="error" class="text-sm text-red-300">{{ error }}</p>
        <p v-else-if="subjects.length === 0" class="py-10 text-center text-sm text-white/50">暂无已就绪主体，请先在主体库创建</p>
        <div v-else class="grid gap-3 sm:grid-cols-2">
          <button
            v-for="subject in subjects"
            :key="subject.subjectCode"
            type="button"
            class="flex items-center gap-3 rounded-xl border border-white/10 bg-black/20 p-3 text-left hover:border-violet-500/40 hover:bg-violet-500/5"
            @click="emit('select', subject)"
          >
            <div class="h-14 w-14 shrink-0 overflow-hidden rounded-lg bg-black/30">
              <img v-if="subject.previewUrl" :src="previewUrl(subject.previewUrl)" :alt="subject.displayName" class="h-full w-full object-cover" />
              <div v-else class="flex h-full items-center justify-center text-white/30">
                <UserRound class="h-5 w-5" />
              </div>
            </div>
            <div class="min-w-0">
              <div class="truncate text-sm font-medium text-white">{{ subject.displayName }}</div>
              <div class="truncate text-xs text-white/45">{{ subject.upstreamElementId }}</div>
            </div>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
