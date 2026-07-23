<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue"
import { Check, Copy, Crown, Gift, Link2, UserPlus, X } from "lucide-vue-next"
import { useAuthStore } from "@/store/authStore"
import CreditPowerIcon from "@/components/CreditPowerIcon/CreditPowerIcon.vue"

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const auth = useAuthStore()
const PUBLIC_SITE_ORIGIN = "https://wlcloudai.com"
const dialogRef = ref<HTMLElement | null>(null)
const copyButtonRef = ref<HTMLButtonElement | null>(null)
const inviteLinkInputRef = ref<HTMLInputElement | null>(null)
const copied = ref(false)
const copyError = ref("")

let copiedTimer: ReturnType<typeof window.setTimeout> | null = null
let previousActiveElement: HTMLElement | null = null
let previousBodyOverflow = ""

const inviteCode = computed(() => {
  return auth.user?.referralCode?.trim().toUpperCase() || ""
})

const inviteUrl = computed(() => {
  return inviteCode.value ? `${PUBLIC_SITE_ORIGIN}/?invite=${encodeURIComponent(inviteCode.value)}` : ""
})

function clearCopiedTimer() {
  if (copiedTimer == null) return
  window.clearTimeout(copiedTimer)
  copiedTimer = null
}

function restorePageState() {
  if (typeof document === "undefined") return
  document.body.style.overflow = previousBodyOverflow
  const elementToRestore = previousActiveElement
  previousActiveElement = null
  void nextTick(() => elementToRestore?.focus())
}

watch(
  () => props.open,
  async (open) => {
    clearCopiedTimer()
    copied.value = false
    copyError.value = ""

    if (!open) {
      restorePageState()
      return
    }

    if (typeof document !== "undefined") {
      previousActiveElement = document.activeElement instanceof HTMLElement ? document.activeElement : null
      previousBodyOverflow = document.body.style.overflow
      document.body.style.overflow = "hidden"
    }
    await nextTick()
    copyButtonRef.value?.focus()
  },
  { immediate: true },
)

function close() {
  emit("close")
}

function selectInviteLink(event: FocusEvent) {
  ;(event.target as HTMLInputElement).select()
}

async function copyInviteLink() {
  if (!inviteCode.value) {
    copyError.value = "邀请码暂不可用，请稍后重试"
    return
  }
  clearCopiedTimer()
  copied.value = false
  copyError.value = ""

  try {
    await navigator.clipboard.writeText(inviteUrl.value)
    copied.value = true
    copiedTimer = window.setTimeout(() => {
      copied.value = false
      copiedTimer = null
    }, 2200)
  } catch {
    copyError.value = "复制失败，请手动选择上方链接"
    await nextTick()
    inviteLinkInputRef.value?.focus()
    inviteLinkInputRef.value?.select()
  }
}

function focusableElements() {
  if (!dialogRef.value) return []
  return Array.from(
    dialogRef.value.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
    ),
  ).filter((element) => !element.hasAttribute("hidden"))
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== "Tab") return

  const elements = focusableElements()
  if (!elements.length) return
  const first = elements[0]
  const last = elements[elements.length - 1]

  if (!dialogRef.value?.contains(document.activeElement)) {
    event.preventDefault()
    ;(event.shiftKey ? last : first).focus()
    return
  }

  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

onBeforeUnmount(() => {
  clearCopiedTimer()
  if (props.open) restorePageState()
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="referral-dialog-backdrop"
      role="presentation"
      @click.self="close"
      @keydown="handleKeydown"
    >
      <section
        ref="dialogRef"
        class="referral-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="referral-dialog-title"
        aria-describedby="referral-dialog-description"
      >
        <header class="referral-dialog__header">
          <div class="referral-dialog__title-row">
            <span class="referral-dialog__gift" aria-hidden="true">
              <Gift :size="22" />
            </span>
            <div>
              <p class="referral-dialog__label">邀请有礼</p>
              <h2 id="referral-dialog-title">邀请好友，双方都得算力</h2>
            </div>
          </div>
          <button type="button" class="referral-dialog__close" aria-label="关闭邀请有礼" @click="close">
            <X :size="18" aria-hidden="true" />
          </button>
          <p id="referral-dialog-description" class="referral-dialog__description">
            好友通过你的专属链接注册，邀请码会自动带入。
          </p>
        </header>

        <section class="referral-dialog__benefits" aria-label="邀请奖励">
          <article class="referral-benefit referral-benefit--invitee">
            <span class="referral-benefit__icon" aria-hidden="true">
              <UserPlus :size="20" />
            </span>
            <div class="referral-benefit__copy">
              <p>好友填写邀请码</p>
              <div class="referral-benefit__value">
                <span>+200</span>
                <CreditPowerIcon :size="20" />
                <small>算力</small>
              </div>
              <strong>好友获得</strong>
            </div>
          </article>

          <article class="referral-benefit referral-benefit--inviter">
            <span class="referral-benefit__icon" aria-hidden="true">
              <Crown :size="20" />
            </span>
            <div class="referral-benefit__copy">
              <p>你的邀请码被填写</p>
              <div class="referral-benefit__value">
                <span>+100</span>
                <CreditPowerIcon :size="20" />
                <small>算力</small>
              </div>
              <strong>你获得</strong>
            </div>
          </article>
        </section>

        <div class="referral-dialog__share">
          <div class="referral-dialog__code-row">
            <span>我的邀请码</span>
            <code>{{ inviteCode || "------" }}</code>
          </div>

          <label class="referral-dialog__link-label" for="referral-invite-link">
            <Link2 :size="16" aria-hidden="true" />
            专属邀请链接
          </label>
          <input
            id="referral-invite-link"
            ref="inviteLinkInputRef"
            class="referral-dialog__link"
            type="text"
            :value="inviteUrl"
            readonly
            spellcheck="false"
            @focus="selectInviteLink"
          />

          <button
            ref="copyButtonRef"
            type="button"
            class="referral-dialog__copy"
            :class="{ 'referral-dialog__copy--success': copied }"
            :disabled="!inviteCode"
            @click="copyInviteLink"
          >
            <Check v-if="copied" :size="18" aria-hidden="true" />
            <Copy v-else :size="18" aria-hidden="true" />
            {{ copied ? "邀请链接已复制" : "复制邀请链接" }}
          </button>

          <p
            class="referral-dialog__status"
            :class="{ 'referral-dialog__status--error': copyError }"
            aria-live="polite"
          >
            {{ copyError || (copied ? "现在可以发给好友了" : "每位好友仅可绑定一次邀请关系") }}
          </p>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.referral-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 140;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgb(0 0 0 / 0.72);
  backdrop-filter: blur(6px);
}

.referral-dialog {
  width: min(100%, 600px);
  max-height: calc(100dvh - 32px);
  overflow-y: auto;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 12px;
  background: #121318;
  color: #fff;
  box-shadow: 0 28px 80px rgb(0 0 0 / 0.56);
  animation: referral-dialog-in 180ms ease-out;
}

.referral-dialog__header {
  position: relative;
  padding: 24px 56px 20px 24px;
  border-bottom: 1px solid rgb(255 255 255 / 0.08);
  background: var(--brand-softer);
}

.referral-dialog__title-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.referral-dialog__gift {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--brand-border);
  border-radius: 8px;
  background: rgb(var(--brand-primary-rgb) / 0.12);
  color: var(--brand-active-text);
}

.referral-dialog__label {
  margin: 0 0 3px;
  color: var(--brand-active-text);
  font-size: 12px;
  font-weight: 700;
}

.referral-dialog__header h2 {
  margin: 0;
  color: #fff;
  font-size: 23px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: 0;
}

.referral-dialog__description {
  margin: 12px 0 0 58px;
  color: rgb(255 255 255 / 0.58);
  font-size: 13px;
  line-height: 1.6;
}

.referral-dialog__close {
  position: absolute;
  top: 16px;
  right: 16px;
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.62);
  cursor: pointer;
  transition: background-color 160ms ease, color 160ms ease, border-color 160ms ease;
}

.referral-dialog__close:hover {
  border-color: rgb(255 255 255 / 0.18);
  background: rgb(255 255 255 / 0.08);
  color: #fff;
}

.referral-dialog__benefits {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-bottom: 1px solid rgb(255 255 255 / 0.08);
}

.referral-benefit {
  display: flex;
  min-width: 0;
  gap: 12px;
  padding: 24px;
}

.referral-benefit + .referral-benefit {
  border-left: 1px solid rgb(255 255 255 / 0.08);
}

.referral-benefit__icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
}

.referral-benefit--invitee .referral-benefit__icon {
  background: rgb(var(--brand-primary-rgb) / 0.12);
  color: var(--brand-active-text);
}

.referral-benefit--inviter .referral-benefit__icon {
  background: rgb(245 208 97 / 0.12);
  color: #f5d061;
}

.referral-benefit__copy {
  min-width: 0;
}

.referral-benefit__copy p {
  margin: 0;
  color: rgb(255 255 255 / 0.64);
  font-size: 13px;
  line-height: 1.4;
}

.referral-benefit__value {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}

.referral-benefit__value span {
  color: #fff;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 34px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0;
}

.referral-benefit__value small {
  align-self: flex-end;
  margin-bottom: 7px;
  color: rgb(255 255 255 / 0.48);
  font-size: 12px;
  font-weight: 600;
}

.referral-benefit__copy strong {
  display: block;
  margin-top: 4px;
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
  font-weight: 600;
}

.referral-dialog__share {
  padding: 22px 24px 20px;
}

.referral-dialog__code-row {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.referral-dialog__code-row span,
.referral-dialog__link-label {
  color: rgb(255 255 255 / 0.58);
  font-size: 12px;
  font-weight: 700;
}

.referral-dialog__code-row code {
  min-width: 0;
  overflow-wrap: anywhere;
  color: #fff;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0;
}

.referral-dialog__link-label {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 8px;
}

.referral-dialog__link {
  width: 100%;
  min-height: 44px;
  box-sizing: border-box;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 8px;
  outline: none;
  background: rgb(255 255 255 / 0.035);
  padding: 0 12px;
  color: rgb(255 255 255 / 0.82);
  font-size: 13px;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.referral-dialog__link:focus {
  border-color: var(--brand-border);
  box-shadow: 0 0 0 3px var(--brand-softer);
}

.referral-dialog__copy {
  display: inline-flex;
  width: 100%;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 12px;
  border: 1px solid var(--brand-border);
  border-radius: 8px;
  background: var(--brand-gradient);
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  cursor: pointer;
  box-shadow: var(--brand-button-shadow);
  transition: filter 160ms ease, transform 160ms ease;
}

.referral-dialog__copy:hover {
  filter: brightness(1.08);
  transform: translateY(-1px);
}

.referral-dialog__copy--success {
  border-color: rgb(52 211 153 / 0.42);
  background: #087c5b;
  box-shadow: 0 12px 28px rgb(8 124 91 / 0.2);
}

.referral-dialog__status {
  min-height: 18px;
  margin: 8px 0 0;
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
  line-height: 1.5;
  text-align: center;
}

.referral-dialog__status--error {
  color: #fca5a5;
}

.referral-dialog__close:focus-visible,
.referral-dialog__copy:focus-visible {
  outline: 2px solid var(--brand-active-text);
  outline-offset: 2px;
}

@keyframes referral-dialog-in {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@media (max-width: 640px) {
  .referral-dialog-backdrop {
    align-items: flex-end;
    padding: 12px;
  }

  .referral-dialog {
    max-height: calc(100dvh - 24px);
  }

  .referral-dialog__header {
    padding: 20px 50px 18px 18px;
  }

  .referral-dialog__title-row {
    align-items: flex-start;
    gap: 11px;
  }

  .referral-dialog__gift {
    width: 38px;
    height: 38px;
  }

  .referral-dialog__header h2 {
    font-size: 19px;
  }

  .referral-dialog__description {
    margin-left: 49px;
  }

  .referral-dialog__benefits {
    grid-template-columns: 1fr;
  }

  .referral-benefit {
    padding: 17px 18px;
  }

  .referral-benefit + .referral-benefit {
    border-top: 1px solid rgb(255 255 255 / 0.08);
    border-left: 0;
  }

  .referral-benefit__value {
    min-height: 38px;
  }

  .referral-benefit__value span {
    font-size: 29px;
  }

  .referral-dialog__share {
    padding: 18px;
  }

  .referral-dialog__code-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 5px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .referral-dialog {
    animation: none;
  }

  .referral-dialog__copy {
    transition: none;
  }

  .referral-dialog__copy:hover {
    transform: none;
  }
}
</style>
