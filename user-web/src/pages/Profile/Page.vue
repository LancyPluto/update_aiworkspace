<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { useRouter } from "vue-router"
import { Camera, Check, ClipboardCopy, ExternalLink, Loader2, Shield, Sparkles, ToggleLeft, Trash2, X } from "lucide-vue-next"
import CreditPowerIcon from "@/components/CreditPowerIcon/CreditPowerIcon.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import { fetchCreditAccount, fetchMyGiftCards, redeemGiftCard, redeemGiftCardByCode } from "@/api/creditApi"
import { fetchTasks } from "@/api/taskApi"
import { cancelCurrentUserAccount, sendCancelAccountSmsCode } from "@/api/userApi"
import type { CreditAccount, GiftCard } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { defaultUserDisplayName, safeDisplayName } from "@/utils/displayName"

const auth = useAuthStore()
const router = useRouter()
const fileInputRef = ref<HTMLInputElement | null>(null)
const nickname = ref("")
const bio = ref("")
const autoPublishAssets = ref(true)
const promptPublicByDefault = ref(false)
const saving = ref(false)
const savingCommunity = ref(false)
const uploading = ref(false)
const loadingStats = ref(false)
const error = ref("")
const success = ref("")
const credit = ref<CreditAccount | null>(null)
const totalTasks = ref<number | null>(null)
const successTasks = ref<number | null>(null)
const cancelDialogOpen = ref(false)
const cancelSmsCode = ref("")
const cancelConfirmText = ref("")
const cancelCooldown = ref(0)
const sendingCancelCode = ref(false)
const cancellingAccount = ref(false)
const cancelDebugCode = ref<string | null>(null)

// 礼品卡状态
const giftCards = ref<GiftCard[]>([])
const loadingGiftCards = ref(false)
const redeemingCardId = ref<number | null>(null)
const redeemCodeDialogOpen = ref(false)
const redeemCodeInput = ref("")
const redeemingByCode = ref(false)
const shareDialogOpen = ref(false)
const shareCard = ref<GiftCard | null>(null)
const copyingShareCode = ref(false)

const GIFT_CARD_STYLE_THEMES: Record<string, { bg: string; border: string }> = {
  blue: { bg: "linear-gradient(135deg, rgb(30 64 175), rgb(15 23 42))", border: "rgb(59 130 246 / 0.3)" },
  purple: { bg: "linear-gradient(135deg, rgb(107 33 168), rgb(15 23 42))", border: "rgb(168 85 247 / 0.3)" },
  gold: { bg: "linear-gradient(135deg, rgb(161 98 7), rgb(15 23 42))", border: "rgb(250 204 21 / 0.3)" },
  dark: { bg: "linear-gradient(135deg, rgb(30 41 59), rgb(10 10 15))", border: "rgb(100 116 139 / 0.3)" },
}

function giftCardStyle(theme: string | undefined | null) {
  return GIFT_CARD_STYLE_THEMES[theme || "dark"] || GIFT_CARD_STYLE_THEMES.dark
}

function maskCardCode(code: string | undefined | null) {
  if (!code) return "--"
  if (code.length <= 8) return code
  return code.slice(0, 3) + "****" + code.slice(-4)
}

function giftCardStatusLabel(status: string | undefined | null) {
  switch (status) {
    case "UNUSED": return "未使用"
    case "USED": return "已使用"
    case "EXPIRED": return "已过期"
    default: return status || "--"
  }
}

function giftCardStatusClass(status: string | undefined | null) {
  switch (status) {
    case "UNUSED": return "status-unused"
    case "USED": return "status-used"
    case "EXPIRED": return "status-expired"
    default: return ""
  }
}

const displayName = computed(
  () => safeDisplayName(auth.user?.nickname) || safeDisplayName(auth.user?.username) || defaultUserDisplayName(auth.user?.id),
)
const joinedLabel = computed(() => `UID ${auth.user?.id ?? "--"}`)
const accountLabel = computed(() => auth.user?.phone || auth.user?.email || auth.user?.username || "--")
const publicProfileUrl = computed(() => (auth.user?.id ? `/u/${auth.user.id}` : "/profile"))
const cancelConfirmPhrase = computed(() => `确认注销我的账号`)
const canSubmitCancel = computed(
  () => cancelSmsCode.value.trim().length >= 4 && cancelConfirmText.value.trim() === cancelConfirmPhrase.value,
)
const maskedPhone = computed(() => {
  const phone = auth.user?.phone || ""
  return phone.length === 11 ? `${phone.slice(0, 3)}****${phone.slice(7)}` : phone || "--"
})
const userTypeLabel = computed(() => (auth.user?.userType === "ADMIN" ? "管理员" : "普通用户"))
const accountStatusLabel = computed(() => (auth.user?.status === "ACTIVE" ? "正常" : "受限"))
const membershipLabel = computed(() => auth.user?.membershipPlan || "基础版")
const completedProfileItems = computed(() => {
  let count = 0
  if (auth.user?.avatarUrl) count += 1
  if (nickname.value.trim()) count += 1
  if (bio.value.trim()) count += 1
  if (auth.user?.phone || auth.user?.email) count += 1
  return count
})
const profileCompletion = computed(() => Math.round((completedProfileItems.value / 4) * 100))
const successRateLabel = computed(() => {
  if (!totalTasks.value || successTasks.value == null) return "--"
  return `${Math.round((successTasks.value / totalTasks.value) * 100)}%`
})
const unusedGiftCards = computed(() => giftCards.value.filter((card) => card.status === "UNUSED").length)
const giftCardCreditTotal = computed(() =>
  giftCards.value
    .filter((card) => card.status === "UNUSED")
    .reduce((sum, card) => sum + card.credits, 0),
)

async function loadProfileStats() {
  if (!auth.token) return
  loadingStats.value = true
  try {
    const [creditRes, allTasks, completedTasks] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 1 } }),
      fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 1, status: "SUCCESS" } }),
    ])
    credit.value = creditRes
    totalTasks.value = allTasks.total
    successTasks.value = completedTasks.total
    window.dispatchEvent(new CustomEvent("credits:updated", { detail: creditRes }))
  } finally {
    loadingStats.value = false
  }
}

function openAvatarPicker() {
  fileInputRef.value?.click()
}

async function handleAvatarSelected(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ""
  if (!file) return
  error.value = ""
  success.value = ""
  uploading.value = true
  try {
    await auth.uploadAvatar(file)
    success.value = "头像已更新"
  } catch (err) {
    error.value = err instanceof Error ? err.message : "头像上传失败"
  } finally {
    uploading.value = false
  }
}

async function saveProfile() {
  error.value = ""
  success.value = ""
  saving.value = true
  try {
    await auth.updateProfile({
      nickname: nickname.value,
    })
    success.value = "资料已保存"
  } catch (err) {
    error.value = err instanceof Error ? err.message : "资料保存失败"
  } finally {
    saving.value = false
  }
}

async function saveCommunitySettings() {
  error.value = ""
  success.value = ""
  savingCommunity.value = true
  try {
    await auth.updateCommunityProfile({
      bio: bio.value,
      autoPublishAssets: autoPublishAssets.value,
      promptPublicByDefault: promptPublicByDefault.value,
    })
    success.value = "社区设置已保存"
  } catch (err) {
    error.value = err instanceof Error ? err.message : "社区设置保存失败"
  } finally {
    savingCommunity.value = false
  }
}

function startCancelCooldown(seconds: number) {
  cancelCooldown.value = seconds
  const tick = window.setInterval(() => {
    cancelCooldown.value -= 1
    if (cancelCooldown.value <= 0) {
      cancelCooldown.value = 0
      window.clearInterval(tick)
    }
  }, 1000)
}

async function requestCancelSmsCode() {
  if (!auth.token) return
  error.value = ""
  success.value = ""
  sendingCancelCode.value = true
  try {
    const res = await sendCancelAccountSmsCode({ token: auth.token })
    cancelDebugCode.value = res.debugCode ?? null
    startCancelCooldown(res.cooldownSeconds || 60)
    success.value = cancelDebugCode.value ? `注销验证码已发送：${cancelDebugCode.value}` : "注销验证码已发送"
  } catch (err) {
    error.value = err instanceof Error ? err.message : "验证码发送失败"
  } finally {
    sendingCancelCode.value = false
  }
}

async function submitCancelAccount() {
  if (!auth.token) return
  if (cancelConfirmText.value.trim() !== cancelConfirmPhrase.value) {
    error.value = `请在确认框输入“${cancelConfirmPhrase.value}”`
    return
  }
  error.value = ""
  success.value = ""
  cancellingAccount.value = true
  try {
    await cancelCurrentUserAccount({ smsCode: cancelSmsCode.value.trim() }, { token: auth.token })
    await auth.logout()
    await router.replace({ name: "Login" })
  } catch (err) {
    error.value = err instanceof Error ? err.message : "账号注销失败"
  } finally {
    cancellingAccount.value = false
  }
}

async function loadGiftCards() {
  if (!auth.token) return
  loadingGiftCards.value = true
  try {
    giftCards.value = await fetchMyGiftCards({ token: auth.token })
  } catch {
    // 静默失败，不影响页面其他部分
  } finally {
    loadingGiftCards.value = false
  }
}

async function redeemCard(id: number) {
  redeemingCardId.value = id
  error.value = ""
  success.value = ""
  try {
    await redeemGiftCard(id, { token: auth.token })
    await loadGiftCards()
    await loadProfileStats()
    success.value = "礼品卡兑换成功，算力已到账"
  } catch (err) {
    error.value = err instanceof Error ? err.message : "礼品卡兑换失败"
  } finally {
    redeemingCardId.value = null
  }
}

function openShareDialog(card: GiftCard) {
  shareCard.value = card
  shareDialogOpen.value = true
}

async function copyShareCode() {
  if (!shareCard.value?.cardCode) return
  copyingShareCode.value = true
  try {
    await navigator.clipboard.writeText(shareCard.value.cardCode)
    success.value = "兑换码已复制，可发送给好友"
    error.value = ""
  } catch {
    error.value = "复制失败，请手动复制兑换码"
  } finally {
    copyingShareCode.value = false
  }
}

function openRedeemCodeDialog() {
  redeemCodeInput.value = ""
  redeemCodeDialogOpen.value = true
}

async function submitRedeemByCode() {
  const code = redeemCodeInput.value.trim()
  if (!code) return
  redeemingByCode.value = true
  error.value = ""
  success.value = ""
  try {
    await redeemGiftCardByCode({ cardCode: code }, { token: auth.token })
    redeemCodeDialogOpen.value = false
    redeemCodeInput.value = ""
    await loadGiftCards()
    await loadProfileStats()
    success.value = "礼品卡兑换成功，算力已到账"
  } catch (err) {
    error.value = err instanceof Error ? err.message : "礼品卡兑换失败"
  } finally {
    redeemingByCode.value = false
  }
}

onMounted(async () => {
  if (!auth.user) await auth.fetchCurrentUser({ clearOnFailure: false })
  nickname.value = safeDisplayName(auth.user?.nickname) || safeDisplayName(auth.user?.username) || ""
  bio.value = auth.user?.bio || ""
  autoPublishAssets.value = auth.user?.autoPublishAssets !== false
  promptPublicByDefault.value = auth.user?.promptPublicByDefault === true
  void loadProfileStats()
  void loadGiftCards()
})
</script>

<template>
    <div class="profile-page">
      <header class="profile-header">
        <div>
          <p class="eyebrow">个人中心</p>
          <h1>账号与资料</h1>
          <p class="header-copy">管理公开身份、社区展示偏好、礼品卡和账号安全。</p>
        </div>
        <button type="button" class="secondary-action" @click="$router.push(publicProfileUrl)">
          <ExternalLink class="h-4 w-4" />
          公开主页
        </button>
      </header>

      <div v-if="error" class="alert alert-error">{{ error }}</div>
      <div v-if="success" class="alert alert-success">
        <Check class="h-4 w-4" />
        {{ success }}
      </div>

      <section class="profile-summary">
        <div class="identity-card">
          <div class="avatar-stack">
            <UserAvatar :src="auth.user?.avatarUrl" :name="displayName" size="xl" />
            <button type="button" class="avatar-action" :disabled="uploading" @click="openAvatarPicker">
              <Loader2 v-if="uploading" class="h-4 w-4 animate-spin" />
              <Camera v-else class="h-4 w-4" />
              更换
            </button>
            <input
              ref="fileInputRef"
              type="file"
              class="sr-only"
              accept="image/jpeg,image/png,image/webp"
              @change="handleAvatarSelected"
            />
          </div>
          <div class="identity-copy">
            <div class="identity-title-row">
              <h2>{{ displayName }}</h2>
              <span class="status-pill" :class="{ muted: auth.user?.status !== 'ACTIVE' }">{{ accountStatusLabel }}</span>
            </div>
            <p>{{ accountLabel }}</p>
            <div class="identity-meta">
              <span>{{ joinedLabel }}</span>
              <span>{{ userTypeLabel }}</span>
              <span>{{ membershipLabel }}</span>
            </div>
          </div>
        </div>

        <div class="summary-metrics" :class="{ loading: loadingStats }">
          <div class="metric-card">
            <Sparkles class="h-4 w-4" />
            <span>生成任务</span>
            <strong>{{ totalTasks ?? "--" }}</strong>
          </div>
          <div class="metric-card">
            <Shield class="h-4 w-4" />
            <span>成功率</span>
            <strong>{{ successRateLabel }}</strong>
          </div>
          <div class="metric-card">
            <CreditPowerIcon :size="16" />
            <span>可用算力</span>
            <strong>{{ credit?.balance ?? "--" }}</strong>
          </div>
        </div>
      </section>

      <section class="profile-content">
        <div class="settings-column">
          <section class="profile-panel">
            <div class="panel-heading">
              <div>
                <p class="panel-kicker">基础资料</p>
                <h2>公开身份</h2>
              </div>
              <span class="completion-badge">{{ profileCompletion }}%</span>
            </div>

            <label class="form-field">
              <span>昵称</span>
              <input v-model="nickname" maxlength="40" placeholder="设置一个好记的昵称" />
            </label>

            <div class="account-lines">
              <div>
                <span>账号</span>
                <strong>{{ accountLabel }}</strong>
              </div>
              <div>
                <span>身份</span>
                <strong>{{ userTypeLabel }}</strong>
              </div>
              <div>
                <span>会员</span>
                <strong>{{ membershipLabel }}</strong>
              </div>
            </div>

            <button type="button" class="primary-action" :disabled="saving || !nickname.trim()" @click="saveProfile">
              <Loader2 v-if="saving" class="h-4 w-4 animate-spin" />
              保存资料
            </button>
          </section>

          <section class="profile-panel">
            <div class="panel-heading">
              <div>
                <p class="panel-kicker">社区展示</p>
                <h2>公开偏好</h2>
              </div>
              <ToggleLeft class="panel-icon h-5 w-5" />
            </div>

            <label class="form-field">
              <span>个人简介</span>
              <textarea v-model="bio" maxlength="280" placeholder="写一句会出现在公开主页上的介绍"></textarea>
            </label>

            <div class="switch-list">
              <label class="switch-line">
                <span>
                  <strong>新生成作品默认公开</strong>
                  <small>生成成功后自动进入公开主页</small>
                </span>
                <input v-model="autoPublishAssets" type="checkbox" />
              </label>
              <label class="switch-line">
                <span>
                  <strong>默认公开提示词</strong>
                  <small>公开作品中展示创作提示词</small>
                </span>
                <input v-model="promptPublicByDefault" type="checkbox" />
              </label>
            </div>

            <div class="settings-actions">
              <button type="button" class="secondary-action" @click="$router.push(publicProfileUrl)">
                <ExternalLink class="h-4 w-4" />
                查看公开主页
              </button>
              <button type="button" class="primary-action compact" :disabled="savingCommunity" @click="saveCommunitySettings">
                <Loader2 v-if="savingCommunity" class="h-4 w-4 animate-spin" />
                保存设置
              </button>
            </div>
          </section>
        </div>

        <aside class="side-column">
          <section class="profile-panel compact-panel">
            <div class="panel-heading">
              <div>
                <p class="panel-kicker">账户状态</p>
                <h2>安全概览</h2>
              </div>
            </div>
            <div class="security-list">
              <div>
                <span>绑定手机</span>
                <strong>{{ maskedPhone }}</strong>
              </div>
              <div>
                <span>账号状态</span>
                <strong>{{ accountStatusLabel }}</strong>
              </div>
              <div>
                <span>冻结算力</span>
                <strong>{{ credit?.frozen ?? "--" }}</strong>
              </div>
            </div>
          </section>

          <section class="profile-panel compact-panel gift-overview">
            <div class="panel-heading">
              <div>
                <p class="panel-kicker">礼品卡</p>
                <h2>可兑换资产</h2>
              </div>
              <button type="button" class="icon-button" aria-label="兑换礼品卡" @click="openRedeemCodeDialog">
                <CreditPowerIcon :size="16" />
              </button>
            </div>
            <div class="gift-summary">
              <strong>{{ unusedGiftCards }}</strong>
              <span>张未使用礼品卡，共 {{ giftCardCreditTotal.toLocaleString() }} 算力</span>
            </div>
          </section>
        </aside>
      </section>

      <section class="gift-card-zone">
        <div class="section-heading">
          <div>
            <p class="panel-kicker">我的礼品卡</p>
            <h2>使用与赠送</h2>
          </div>
          <button type="button" class="secondary-action" @click="openRedeemCodeDialog">
            <CreditPowerIcon :size="16" />
            兑换礼品卡
          </button>
        </div>

        <div v-if="loadingGiftCards" class="gift-card-state">
          <Loader2 class="h-4 w-4 animate-spin" />
          加载中
        </div>
        <div v-else-if="giftCards.length === 0" class="gift-card-state">暂无礼品卡</div>
        <div v-else class="gift-card-list">
          <article
            v-for="card in giftCards"
            :key="card.id"
            class="gift-card-item"
            :style="{ background: giftCardStyle(card.cardTheme).bg, borderColor: giftCardStyle(card.cardTheme).border }"
          >
            <div class="gift-card-info">
              <span class="gift-card-status" :class="giftCardStatusClass(card.status)">
                {{ giftCardStatusLabel(card.status) }}
              </span>
              <div class="gift-card-credits">
                {{ card.credits.toLocaleString() }} <span>算力</span>
              </div>
              <code class="gift-card-code">{{ maskCardCode(card.cardCode) }}</code>
            </div>
            <div class="gift-card-actions">
              <template v-if="card.status === 'UNUSED'">
                <button
                  type="button"
                  class="gift-card-btn redeem-btn"
                  :disabled="redeemingCardId === card.id"
                  @click="redeemCard(card.id)"
                >
                  <Loader2 v-if="redeemingCardId === card.id" class="h-4 w-4 animate-spin" />
                  使用
                </button>
                <button type="button" class="gift-card-btn" @click="openShareDialog(card)">赠送</button>
              </template>
              <span v-else-if="card.status === 'USED' && card.redeemedAt" class="gift-card-time">
                兑换于 {{ card.redeemedAt }}
              </span>
              <span v-else-if="card.status === 'USED'" class="gift-card-time">已使用</span>
            </div>
          </article>
        </div>
      </section>

      <section class="danger-zone">
        <div>
          <p class="panel-kicker">账号关闭</p>
          <h2>注销账号</h2>
          <p>注销需要通过绑定手机号 {{ maskedPhone }} 完成身份验证。注销后将清理你的持久化记忆、会话、上下文快照和文件索引，账号余额不会退款。</p>
        </div>
        <button type="button" class="danger-action" @click="cancelDialogOpen = true">
          <Trash2 class="h-4 w-4" />
          注销账号
        </button>
      </section>

      <div v-if="cancelDialogOpen" class="modal-backdrop" @click.self="cancelDialogOpen = false">
        <section class="cancel-dialog" role="dialog" aria-modal="true" aria-labelledby="cancel-account-title">
          <button type="button" class="icon-action" aria-label="关闭" @click="cancelDialogOpen = false">
            <X class="h-4 w-4" />
          </button>
          <h2 id="cancel-account-title">注销账号</h2>
          <div class="cancel-warning-card">
            <section>
              <h3>服务将无法访问</h3>
              <p>账号一经注销，将无法访问本系统的所有用户侧服务，包括对话服务、创作任务和开放平台能力。</p>
            </section>
            <section>
              <h3>历史对话将被删除</h3>
              <p>当前账号的 agent 会话、消息、运行记录、上下文快照和持久化记忆会被清理，无法找回。</p>
            </section>
            <section>
              <h3>充值余额不会退款</h3>
              <p>注销不会要求余额为 0，但当前账号剩余余额和冻结算力会随账号关闭失效，系统不会自动退款。</p>
            </section>
            <section>
              <h3>请勿频繁重复注销</h3>
              <p>频繁注销和重新注册可能会被系统判定为异常行为，导致账号受限或封禁。</p>
            </section>
          </div>

          <div class="cancel-checks">
            <div>
              <span>余额</span>
              <strong>{{ credit?.balance ?? "--" }}</strong>
            </div>
            <div>
              <span>冻结算力</span>
              <strong>{{ credit?.frozen ?? "--" }}</strong>
            </div>
          </div>

          <label class="sms-field">
            <span>绑定手机号 {{ maskedPhone }}</span>
            <div>
              <input v-model="cancelSmsCode" inputmode="numeric" maxlength="6" placeholder="6 位验证码" />
              <button
                type="button"
                class="secondary-action"
                :disabled="sendingCancelCode || cancelCooldown > 0"
                @click="requestCancelSmsCode"
              >
                <Loader2 v-if="sendingCancelCode" class="h-4 w-4 animate-spin" />
                {{ cancelCooldown > 0 ? `${cancelCooldown}s` : "获取验证码" }}
              </button>
            </div>
          </label>

          <label class="confirm-field">
            <span>请在输入框中输入“{{ cancelConfirmPhrase }}”</span>
            <input v-model="cancelConfirmText" :placeholder="cancelConfirmPhrase" />
          </label>

          <div class="cancel-dialog-actions">
            <button type="button" class="cancel-text-action" @click="cancelDialogOpen = false">取消</button>
            <button
              type="button"
              class="danger-action confirm"
              :disabled="cancellingAccount || !canSubmitCancel"
              @click="submitCancelAccount"
            >
              <Loader2 v-if="cancellingAccount" class="h-4 w-4 animate-spin" />
              确认注销
            </button>
          </div>
        </section>
      </div>

      <div v-if="redeemCodeDialogOpen" class="modal-backdrop" @click.self="redeemCodeDialogOpen = false">
        <section class="transfer-dialog" role="dialog" aria-modal="true" aria-labelledby="redeem-code-title">
          <button type="button" class="icon-action dark-icon" aria-label="关闭" @click="redeemCodeDialogOpen = false">
            <X class="h-4 w-4" />
          </button>
          <h2 id="redeem-code-title">兑换礼品卡</h2>
          <p class="transfer-desc">输入好友分享的礼品卡兑换码，兑换后算力将直接到账。</p>
          <label class="transfer-field">
            <span>兑换码</span>
            <input
              v-model="redeemCodeInput"
              placeholder="例如 GC-XXXXXXXXXXXXXXXX"
              autocomplete="off"
              spellcheck="false"
              @keyup.enter="submitRedeemByCode"
            />
          </label>
          <div class="transfer-actions">
            <button type="button" class="cancel-text-action" @click="redeemCodeDialogOpen = false">取消</button>
            <button
              type="button"
              class="primary-action"
              :disabled="redeemingByCode || !redeemCodeInput.trim()"
              @click="submitRedeemByCode"
            >
              <Loader2 v-if="redeemingByCode" class="h-4 w-4 animate-spin" />
              确认兑换
            </button>
          </div>
        </section>
      </div>

      <div v-if="shareDialogOpen && shareCard" class="modal-backdrop" @click.self="shareDialogOpen = false">
        <section class="transfer-dialog" role="dialog" aria-modal="true" aria-labelledby="share-code-title">
          <button type="button" class="icon-action dark-icon" aria-label="关闭" @click="shareDialogOpen = false">
            <X class="h-4 w-4" />
          </button>
          <h2 id="share-code-title">赠送礼品卡</h2>
          <p class="transfer-desc">
            将下方兑换码发送给好友，对方可在「兑换礼品卡」中输入兑换码领取
            {{ shareCard.credits.toLocaleString() }} 算力。
          </p>
          <div class="gift-share-code-box">
            <span class="gift-share-code-label">礼品卡兑换码</span>
            <code class="gift-share-code-value">{{ shareCard.cardCode }}</code>
          </div>
          <div class="transfer-actions">
            <button type="button" class="cancel-text-action" @click="shareDialogOpen = false">关闭</button>
            <button
              type="button"
              class="primary-action"
              :disabled="copyingShareCode"
              @click="copyShareCode"
            >
              <Loader2 v-if="copyingShareCode" class="h-4 w-4 animate-spin" />
              <ClipboardCopy v-else class="h-4 w-4" aria-hidden="true" />
              复制兑换码
            </button>
          </div>
        </section>
      </div>
    </div>
</template>

<style scoped>
.profile-page {
  min-height: 100%;
  padding: 24px;
  background: #10110f;
  color: #f4f5f0;
}

.profile-header,
.profile-summary,
.profile-content,
.gift-card-zone,
.danger-zone {
  margin: 0 auto;
  width: min(1180px, 100%);
}

.profile-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.eyebrow,
.panel-kicker {
  margin: 0 0 6px;
  color: #8f968c;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0;
}

.profile-header h1,
.profile-panel h2,
.section-heading h2,
.danger-zone h2 {
  margin: 0;
  color: #fafaf7;
  letter-spacing: 0;
}

.profile-header h1 {
  font-size: 28px;
  line-height: 1.2;
}

.header-copy {
  margin: 8px 0 0;
  color: #9da397;
  font-size: 14px;
}

.alert {
  margin: 0 auto 14px;
  display: flex;
  width: min(1180px, 100%);
  align-items: center;
  gap: 8px;
  border-radius: 8px;
  padding: 11px 12px;
  font-size: 13px;
}

.alert-error {
  border: 1px solid rgb(248 113 113 / 0.35);
  background: rgb(127 29 29 / 0.22);
  color: #fecaca;
}

.alert-success {
  border: 1px solid rgb(52 211 153 / 0.28);
  background: rgb(6 95 70 / 0.22);
  color: #a7f3d0;
}

.profile-summary {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(360px, 0.55fr);
  gap: 14px;
  margin-bottom: 14px;
}

.identity-card,
.metric-card,
.profile-panel,
.danger-zone,
.gift-card-zone {
  border: 1px solid rgb(173 164 143 / 0.16);
  border-radius: 8px;
  background: #181916;
}

.identity-card {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 18px;
}

.avatar-stack {
  display: grid;
  gap: 10px;
  justify-items: center;
}

.avatar-action,
.primary-action,
.secondary-action,
.danger-action,
.gift-card-btn,
.icon-button,
.icon-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 36px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 800;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}

.avatar-action,
.secondary-action,
.gift-card-btn,
.icon-button {
  border: 1px solid rgb(173 164 143 / 0.22);
  background: #20231f;
  color: #e2e5db;
  padding: 0 12px;
}

.avatar-action:hover:not(:disabled),
.secondary-action:hover:not(:disabled),
.gift-card-btn:hover:not(:disabled),
.icon-button:hover:not(:disabled) {
  border-color: rgb(45 212 191 / 0.42);
  background: #263027;
  color: #fafaf7;
}

.identity-copy {
  min-width: 0;
}

.identity-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.identity-title-row h2 {
  margin: 0;
  overflow-wrap: anywhere;
  color: #fafaf7;
  font-size: 24px;
}

.identity-copy p,
.identity-meta,
.security-list span,
.account-lines span,
.metric-card span,
.gift-summary span,
.danger-zone p {
  color: #9da397;
  font-size: 13px;
}

.identity-copy p {
  margin: 8px 0 0;
}

.identity-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.identity-meta span,
.status-pill,
.completion-badge,
.gift-card-status {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  border-radius: 999px;
  padding: 0 9px;
  font-size: 12px;
  font-weight: 800;
}

.identity-meta span {
  border: 1px solid rgb(173 164 143 / 0.14);
  background: #121411;
  color: #c4c8bd;
}

.status-pill,
.completion-badge {
  border: 1px solid rgb(52 211 153 / 0.28);
  background: rgb(6 95 70 / 0.18);
  color: #a7f3d0;
}

.completion-badge {
  border-color: rgb(245 158 11 / 0.28);
  background: rgb(120 53 15 / 0.2);
  color: #fde68a;
}

.status-pill.muted {
  border-color: rgb(248 113 113 / 0.28);
  background: rgb(127 29 29 / 0.18);
  color: #fecaca;
}

.summary-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.summary-metrics.loading {
  opacity: 0.65;
}

.metric-card {
  display: grid;
  align-content: space-between;
  min-height: 116px;
  padding: 14px;
}

.metric-card svg {
  color: #5eead4;
}

.metric-card strong {
  color: #fafaf7;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 24px;
}

.profile-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 340px);
  gap: 14px;
}

.settings-column,
.side-column {
  display: grid;
  align-content: start;
  gap: 14px;
}

.profile-panel {
  display: grid;
  gap: 18px;
  padding: 18px;
}

.compact-panel {
  gap: 14px;
}

.panel-heading,
.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.profile-panel h2,
.section-heading h2,
.danger-zone h2 {
  font-size: 18px;
}

.panel-icon {
  color: #5eead4;
}

.form-field {
  display: grid;
  gap: 8px;
}

.form-field > span {
  color: #c6cabe;
  font-size: 13px;
  font-weight: 800;
}

.form-field input,
.form-field textarea,
.transfer-field input,
.sms-field input,
.confirm-field input {
  width: 100%;
  border: 1px solid rgb(173 164 143 / 0.2);
  border-radius: 8px;
  background: #111310;
  color: #fafaf7;
  outline: none;
}

.form-field input,
.transfer-field input,
.sms-field input,
.confirm-field input {
  height: 42px;
  padding: 0 12px;
}

.form-field textarea {
  min-height: 92px;
  resize: vertical;
  padding: 11px 12px;
  line-height: 1.6;
}

.form-field input:focus,
.form-field textarea:focus,
.transfer-field input:focus,
.sms-field input:focus,
.confirm-field input:focus {
  border-color: rgb(45 212 191 / 0.55);
  box-shadow: 0 0 0 3px rgb(45 212 191 / 0.12);
}

.account-lines,
.security-list {
  display: grid;
  border: 1px solid rgb(173 164 143 / 0.12);
  border-radius: 8px;
  overflow: hidden;
}

.account-lines div,
.security-list div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  border-bottom: 1px solid rgb(173 164 143 / 0.1);
  padding: 0 12px;
}

.account-lines div:last-child,
.security-list div:last-child {
  border-bottom: 0;
}

.account-lines strong,
.security-list strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: #fafaf7;
  font-size: 13px;
}

.primary-action {
  align-self: flex-start;
  border: 1px solid rgb(45 212 191 / 0.45);
  background: #0f766e;
  color: #fafaf7;
  padding: 0 14px;
}

.primary-action:hover:not(:disabled) {
  background: #0d9488;
}

.primary-action.compact {
  align-self: auto;
}

.primary-action:disabled,
.secondary-action:disabled,
.avatar-action:disabled,
.gift-card-btn:disabled,
.danger-action:disabled {
  cursor: not-allowed;
  opacity: 0.52;
}

.switch-list {
  display: grid;
  gap: 10px;
}

.switch-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border: 1px solid rgb(173 164 143 / 0.12);
  border-radius: 8px;
  background: #121411;
  padding: 12px;
}

.switch-line span {
  display: grid;
  gap: 4px;
}

.switch-line strong {
  color: #ecefe7;
  font-size: 13px;
}

.switch-line small {
  color: #8f968c;
  font-size: 12px;
}

.switch-line input {
  width: 42px;
  height: 24px;
  accent-color: #14b8a6;
}

.settings-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.gift-overview {
  background: #171d19;
}

.icon-button {
  width: 36px;
  padding: 0;
}

.gift-summary {
  display: grid;
  gap: 6px;
}

.gift-summary strong {
  color: #fafaf7;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 34px;
  line-height: 1;
}

.gift-card-zone,
.danger-zone {
  margin-top: 14px;
  padding: 18px;
}

.section-heading {
  margin-bottom: 14px;
}

.gift-card-state {
  display: flex;
  min-height: 92px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed rgb(173 164 143 / 0.2);
  border-radius: 8px;
  color: #9da397;
  font-size: 14px;
}

.gift-card-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}

.gift-card-item {
  display: grid;
  min-height: 154px;
  align-content: space-between;
  gap: 14px;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 8px;
  padding: 16px;
}

.gift-card-info {
  display: grid;
  gap: 9px;
}

.gift-card-credits {
  color: #fff;
  font-size: 26px;
  font-weight: 900;
}

.gift-card-credits span {
  color: rgb(255 255 255 / 0.64);
  font-size: 13px;
  font-weight: 700;
}

.gift-card-code,
.gift-share-code-value {
  color: rgb(255 255 255 / 0.7);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  letter-spacing: 0;
  overflow-wrap: anywhere;
}

.gift-card-status {
  justify-self: start;
}

.status-unused {
  border: 1px solid rgb(56 189 248 / 0.3);
  background: rgb(14 116 144 / 0.28);
  color: #bae6fd;
}

.status-used {
  border: 1px solid rgb(173 164 143 / 0.2);
  background: rgb(41 37 31 / 0.4);
  color: #cbd5e1;
}

.status-expired {
  border: 1px solid rgb(248 113 113 / 0.3);
  background: rgb(127 29 29 / 0.28);
  color: #fecaca;
}

.gift-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.gift-card-time {
  color: rgb(255 255 255 / 0.58);
  font-size: 12px;
}

.redeem-btn {
  border-color: rgb(45 212 191 / 0.42);
  background: #0f766e;
  color: #fff;
}

.danger-zone {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-color: rgb(248 113 113 / 0.22);
  background: #1d1717;
}

.danger-zone p {
  margin: 8px 0 0;
  max-width: 760px;
  line-height: 1.7;
}

.danger-action {
  flex-shrink: 0;
  border: 1px solid rgb(248 113 113 / 0.42);
  background: rgb(127 29 29 / 0.26);
  color: #fecaca;
  padding: 0 14px;
}

.danger-action:hover:not(:disabled) {
  background: rgb(185 28 28 / 0.32);
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(8 8 7 / 0.72);
  backdrop-filter: blur(10px);
}

.transfer-dialog,
.cancel-dialog {
  position: relative;
  width: min(520px, 100%);
  max-height: calc(100vh - 40px);
  overflow-y: auto;
  border: 1px solid rgb(173 164 143 / 0.18);
  border-radius: 8px;
  background: #181916;
  box-shadow: 0 30px 90px rgb(0 0 0 / 0.38);
  color: #fafaf7;
  padding: 18px;
}

.cancel-dialog {
  background: #fff;
  color: #111827;
}

.transfer-dialog h2,
.cancel-dialog h2 {
  margin: 0;
  padding-right: 40px;
  font-size: 18px;
  font-weight: 900;
}

.transfer-desc {
  margin: 10px 0 0;
  color: #9da397;
  font-size: 13px;
  line-height: 1.6;
}

.transfer-field,
.sms-field,
.confirm-field {
  display: grid;
  gap: 8px;
  margin-top: 18px;
}

.transfer-field span,
.sms-field span,
.confirm-field span {
  color: inherit;
  font-size: 12px;
  font-weight: 800;
}

.sms-field div {
  display: flex;
  gap: 10px;
}

.cancel-dialog .secondary-action {
  border-color: #d7dee8;
  background: #fafaf7;
  color: #111827;
}

.cancel-warning-card {
  display: grid;
  gap: 12px;
  margin-top: 14px;
  border-radius: 8px;
  background: #f5f6f8;
  padding: 14px;
}

.cancel-warning-card section {
  display: grid;
  gap: 5px;
}

.cancel-warning-card h3,
.cancel-warning-card p {
  margin: 0;
}

.cancel-warning-card h3 {
  color: #111827;
  font-size: 14px;
  font-weight: 900;
}

.cancel-warning-card p {
  color: #64748b;
  font-size: 12px;
  line-height: 1.55;
}

.cancel-checks {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 18px;
}

.cancel-checks div {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 12px;
}

.cancel-checks span {
  color: #64748b;
  font-size: 12px;
  font-weight: 800;
}

.cancel-checks strong {
  display: block;
  margin-top: 6px;
  color: #111827;
  font-size: 22px;
}

.transfer-actions,
.cancel-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 22px;
}

.cancel-text-action {
  border: 0;
  background: transparent;
  color: inherit;
  font-size: 14px;
  font-weight: 800;
}

.danger-action.confirm {
  min-width: 96px;
  border-color: #f87171;
  background: #dc2626;
  color: #fff;
}

.gift-share-code-box {
  display: grid;
  gap: 8px;
  margin-top: 18px;
  border: 1px dashed rgb(45 212 191 / 0.36);
  border-radius: 8px;
  background: #111310;
  padding: 14px;
}

.gift-share-code-label {
  color: #9da397;
  font-size: 12px;
}

.gift-share-code-value {
  color: #fafaf7;
  font-size: 15px;
  font-weight: 800;
}

.icon-action {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 34px;
  border: 1px solid rgb(173 164 143 / 0.22);
  background: transparent;
  color: currentColor;
  padding: 0;
}

.icon-action:hover {
  background: rgb(173 164 143 / 0.12);
}

@media (max-width: 980px) {
  .profile-summary,
  .profile-content {
    grid-template-columns: 1fr;
  }

  .summary-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 680px) {
  .profile-page {
    padding: 16px;
  }

  .profile-header,
  .identity-card,
  .danger-zone,
  .sms-field div,
  .transfer-actions,
  .cancel-dialog-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .identity-card {
    align-items: flex-start;
  }

  .avatar-stack {
    justify-items: start;
  }

  .summary-metrics {
    grid-template-columns: 1fr;
  }

  .settings-actions,
  .gift-card-actions {
    flex-direction: column;
  }

  .primary-action,
  .secondary-action,
  .danger-action,
  .gift-card-btn {
    width: 100%;
  }
}
</style>
