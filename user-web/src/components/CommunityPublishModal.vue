<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { Globe2, Loader2, X } from "lucide-vue-next"
import type { AssetPreviewItem } from "@/types/assetPreview"
import { buildSafeCommunityTitle } from "@/utils/communityDisplay"

const props = defineProps<{
  open: boolean
  asset: AssetPreviewItem | null
  submitting?: boolean
}>()

const emit = defineEmits<{
  close: []
  confirm: [payload: { title?: string }]
}>()

const customTitle = ref("")

const fallbackTitle = computed(() => {
  if (!props.asset) return ""
  return buildSafeCommunityTitle({
    toolName: props.asset.toolName,
    toolCode: props.asset.toolCode,
    kind: props.asset.kind,
    modality: props.asset.modality,
  })
})

const previewTitle = computed(() => customTitle.value.trim() || fallbackTitle.value)

watch(
  () => [props.open, props.asset?.id] as const,
  ([open]) => {
    if (open) customTitle.value = ""
  },
)

function close() {
  if (props.submitting) return
  emit("close")
}

function submit() {
  if (props.submitting || !props.asset) return
  emit("confirm", { title: customTitle.value.trim() || undefined })
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open && asset"
      class="fixed inset-0 z-[120] flex items-center justify-center bg-black/70 px-4 backdrop-blur-sm"
      @click.self="close"
    >
      <div class="w-full max-w-md overflow-hidden rounded-[28px] border border-white/10 bg-[#12131a] shadow-[0_30px_80px_rgb(0_0_0_/_0.45)]">
        <div class="flex items-start justify-between gap-4 border-b border-white/8 px-6 py-5">
          <div>
            <p class="text-xs font-semibold uppercase tracking-[0.18em] text-white/35">分享到社区</p>
            <h2 class="mt-1 text-xl font-semibold text-white">发布作品</h2>
          </div>
          <button
            type="button"
            class="inline-flex h-9 w-9 items-center justify-center rounded-full border border-white/10 bg-white/[0.04] text-white/60 transition hover:bg-white/10 hover:text-white"
            :disabled="submitting"
            @click="close"
          >
            <X class="h-4 w-4" />
          </button>
        </div>

        <div class="space-y-5 px-6 py-5">
          <p class="text-sm leading-6 text-white/55">
            提示词是给 AI 用的技术参数，标题才是给人看的展示名。你可以为这件作品起一个更好听的名字。
          </p>

          <label class="block">
            <span class="text-sm font-medium text-white/78">作品标题（选填）</span>
            <input
              v-model="customTitle"
              maxlength="80"
              class="mt-2 w-full rounded-2xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm text-white outline-none transition placeholder:text-white/30 focus:border-primary/45"
              placeholder="例如：斋藤飞鸟·夕之立绘"
              @keydown.enter.prevent="submit"
            />
          </label>

          <div class="rounded-2xl border border-white/8 bg-white/[0.03] px-4 py-3">
            <p class="text-xs text-white/38">社区卡片将显示</p>
            <p class="mt-1 text-sm font-medium text-white/88">{{ previewTitle }}</p>
            <p v-if="!customTitle.trim()" class="mt-2 text-xs leading-5 text-white/42">
              未填写时将使用安全命名模板，不会截取或泄露提示词内容。
            </p>
          </div>
        </div>

        <div class="flex gap-3 border-t border-white/8 px-6 py-5">
          <button
            type="button"
            class="inline-flex h-11 flex-1 items-center justify-center rounded-2xl border border-white/10 bg-white/[0.04] text-sm font-semibold text-white/72 transition hover:bg-white/[0.08] hover:text-white"
            :disabled="submitting"
            @click="close"
          >
            取消
          </button>
          <button
            type="button"
            class="inline-flex h-11 flex-1 items-center justify-center gap-2 rounded-2xl border border-primary/35 bg-primary/18 text-sm font-semibold text-white transition hover:bg-primary/25 disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="submitting"
            @click="submit"
          >
            <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
            <Globe2 v-else class="h-4 w-4" />
            {{ submitting ? "发布中…" : "发布到社区" }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
