<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue"
import { Flag, Loader2, X } from "lucide-vue-next"

const props = defineProps<{
  open: boolean
  submitting?: boolean
}>()

const emit = defineEmits<{
  close: []
  confirm: [payload: { reason?: string }]
}>()

const reason = ref("")
const modalRoot = ref<HTMLElement | null>(null)
let previouslyFocusedElement: HTMLElement | null = null

watch(
  () => props.open,
  (open) => {
    if (open) reason.value = ""
  },
)

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      restoreDialogFocus()
      return
    }

    previouslyFocusedElement = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    modalRoot.value?.focus({ preventScroll: true })
  },
  { immediate: true },
)

onBeforeUnmount(restoreDialogFocus)

function trapDialogFocus(event: KeyboardEvent) {
  const root = modalRoot.value
  if (!root) return

  const focusableElements = Array.from(
    root.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  ).filter((element) => element.getClientRects().length > 0)

  if (!focusableElements.length) {
    event.preventDefault()
    root.focus({ preventScroll: true })
    return
  }

  const first = focusableElements[0]
  const last = focusableElements[focusableElements.length - 1]
  const activeElement = document.activeElement

  if (event.shiftKey && (activeElement === first || activeElement === root)) {
    event.preventDefault()
    last.focus({ preventScroll: true })
  } else if (!event.shiftKey && activeElement === last) {
    event.preventDefault()
    first.focus({ preventScroll: true })
  }
}

function restoreDialogFocus() {
  const focusTarget = previouslyFocusedElement
  previouslyFocusedElement = null
  if (focusTarget?.isConnected) {
    void nextTick(() => focusTarget.focus({ preventScroll: true }))
  }
}

function close() {
  if (props.submitting) return
  emit("close")
}

function submit() {
  if (props.submitting) return
  emit("confirm", { reason: reason.value.trim() || undefined })
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      ref="modalRoot"
      class="fixed inset-0 z-[130] flex items-center justify-center bg-black/70 px-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label="举报作品"
      tabindex="-1"
      @click.self="close"
      @keydown.esc.stop="close"
      @keydown.tab="trapDialogFocus"
    >
      <div class="w-full max-w-md overflow-hidden rounded-[28px] border border-white/10 bg-[#12131a] shadow-[0_30px_80px_rgb(0_0_0_/_0.45)]">
        <div class="flex items-start justify-between gap-4 border-b border-white/8 px-6 py-5">
          <div>
            <p class="text-xs font-semibold uppercase tracking-[0.18em] text-white/35">社区安全</p>
            <h2 class="mt-1 text-xl font-semibold text-white">举报作品</h2>
          </div>
          <button
            type="button"
            aria-label="关闭举报弹窗"
            class="inline-flex h-9 w-9 items-center justify-center rounded-full border border-white/10 bg-white/[0.04] text-white/60 transition hover:bg-white/10 hover:text-white"
            :disabled="submitting"
            @click="close"
          >
            <X class="h-4 w-4" />
          </button>
        </div>

        <div class="space-y-5 px-6 py-5">
          <p class="text-sm leading-6 text-white/55">
            如果你认为该作品存在违规、侵权或其他不合适内容，可以提交举报。我们会尽快审核处理。
          </p>

          <label class="block">
            <span class="text-sm font-medium text-white/78">举报原因（选填）</span>
            <textarea
              v-model="reason"
              rows="4"
              maxlength="500"
              class="mt-2 w-full resize-none rounded-2xl border border-white/10 bg-white/[0.04] px-4 py-3 text-sm text-white outline-none transition focus:border-primary/40 focus:ring-2 focus:ring-primary/20"
              placeholder="例如：涉嫌抄袭、色情低俗、广告引流等"
              :disabled="submitting"
            />
          </label>

          <div class="flex items-center justify-end gap-3 pt-1">
            <button
              type="button"
              class="rounded-full px-4 py-2 text-sm font-medium text-white/55 transition hover:text-white"
              :disabled="submitting"
              @click="close"
            >
              取消
            </button>
            <button
              type="button"
              class="inline-flex items-center gap-2 rounded-full bg-primary px-5 py-2 text-sm font-semibold text-white transition hover:bg-primary/90 disabled:opacity-60"
              :disabled="submitting"
              @click="submit"
            >
              <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
              <Flag v-else class="h-4 w-4" />
              提交举报
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
