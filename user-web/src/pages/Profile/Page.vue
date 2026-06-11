<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { useRouter } from "vue-router"
import { Camera, Check, ExternalLink, Loader2, Shield, Sparkles, ToggleLeft, Trash2, Wallet, X } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import { fetchCreditAccount } from "@/api/creditApi"
import { fetchTasks } from "@/api/taskApi"
import { cancelCurrentUserAccount, sendCancelAccountSmsCode } from "@/api/userApi"
import type { CreditAccount } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

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

const displayName = computed(() => auth.user?.nickname || auth.user?.username || "用户")
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
      avatarUrl: auth.user?.avatarUrl ?? null,
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

onMounted(async () => {
  if (!auth.user) await auth.fetchCurrentUser({ clearOnFailure: false })
  nickname.value = auth.user?.nickname || auth.user?.username || ""
  bio.value = auth.user?.bio || ""
  autoPublishAssets.value = auth.user?.autoPublishAssets !== false
  promptPublicByDefault.value = auth.user?.promptPublicByDefault === true
  void loadProfileStats()
})
</script>

<template>
  <AppShell title="我的资料" description="社区系统的个人身份底座">
    <div class="profile-page">
      <section class="profile-hero">
        <div class="hero-copy">
          <p class="eyebrow">Profile foundation</p>
          <h1>{{ displayName }}</h1>
          <p>头像、昵称和账号信息会用于后续社区作品卡、评论和个人主页展示。</p>
        </div>
        <div class="hero-avatar">
          <UserAvatar :src="auth.user?.avatarUrl" :name="displayName" size="xl" />
          <button type="button" :disabled="uploading" @click="openAvatarPicker">
            <Loader2 v-if="uploading" class="h-4 w-4 animate-spin" />
            <Camera v-else class="h-4 w-4" />
            更换头像
          </button>
          <input
            ref="fileInputRef"
            type="file"
            class="sr-only"
            accept="image/jpeg,image/png,image/webp"
            @change="handleAvatarSelected"
          />
        </div>
      </section>

      <div v-if="error" class="alert alert-error">{{ error }}</div>
      <div v-if="success" class="alert alert-success">
        <Check class="h-4 w-4" />
        {{ success }}
      </div>

      <section class="profile-grid">
        <div class="profile-panel edit-panel">
          <div>
            <p class="panel-kicker">基础资料</p>
            <h2>身份展示</h2>
          </div>
          <label>
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
              <strong>{{ auth.user?.userType || "--" }}</strong>
            </div>
            <div>
              <span>状态</span>
              <strong>{{ auth.user?.status || "--" }}</strong>
            </div>
          </div>
          <button type="button" class="primary-action" :disabled="saving || !nickname.trim()" @click="saveProfile">
            <Loader2 v-if="saving" class="h-4 w-4 animate-spin" />
            保存资料
          </button>
        </div>

        <div class="profile-panel stats-panel">
          <div>
            <p class="panel-kicker">轻统计</p>
            <h2>创作概览</h2>
          </div>
          <div class="stat-list" :class="{ loading: loadingStats }">
            <div>
              <Sparkles class="h-4 w-4" />
              <span>生成任务</span>
              <strong>{{ totalTasks ?? "--" }}</strong>
            </div>
            <div>
              <Shield class="h-4 w-4" />
              <span>成功素材</span>
              <strong>{{ successTasks ?? "--" }}</strong>
            </div>
            <div>
              <Wallet class="h-4 w-4" />
              <span>可用算力</span>
              <strong>{{ credit?.available ?? "--" }}</strong>
            </div>
          </div>
          <div class="community-settings">
            <div class="settings-title">
              <ToggleLeft class="h-4 w-4" />
              <span>社区公开</span>
            </div>
            <label class="bio-field">
              <span>个人简介</span>
              <textarea v-model="bio" maxlength="280" placeholder="写一句会出现在公开主页上的介绍" />
            </label>
            <label class="switch-line">
              <span>新生成作品默认公开</span>
              <input v-model="autoPublishAssets" type="checkbox" />
            </label>
            <label class="switch-line">
              <span>默认公开提示词</span>
              <input v-model="promptPublicByDefault" type="checkbox" />
            </label>
            <div class="settings-actions">
              <button type="button" class="secondary-action" @click="$router.push(publicProfileUrl)">
                <ExternalLink class="h-4 w-4" />
                我的公开主页
              </button>
              <button type="button" class="primary-action compact" :disabled="savingCommunity" @click="saveCommunitySettings">
                <Loader2 v-if="savingCommunity" class="h-4 w-4 animate-spin" />
                保存社区设置
              </button>
            </div>
          </div>
          <p class="profile-note">{{ joinedLabel }} · 成功生成的新作品会按上方设置进入公开主页。</p>
        </div>
      </section>

      <section class="danger-zone">
        <div>
          <p class="panel-kicker">Account closure</p>
          <h2>注销账号</h2>
          <p>注销需要通过绑定手机号 {{ maskedPhone }} 完成身份验证。注销后将清理你的持久化记忆、会话、上下文快照和文件索引，账号余额不会退款。</p>
        </div>
        <button type="button" class="danger-action" @click="cancelDialogOpen = true">
          <Trash2 class="h-4 w-4" />
          注销
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
    </div>
  </AppShell>
</template>

<style scoped>
.profile-page {
  min-height: 100%;
  padding: clamp(24px, 4vw, 56px);
  background:
    radial-gradient(circle at 18% 8%, rgb(176 92 255 / 0.16), transparent 34%),
    radial-gradient(circle at 88% 10%, rgb(34 211 238 / 0.08), transparent 32%),
    #080808;
  color: #fff;
}

.profile-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 28px;
  align-items: end;
  min-height: 280px;
  border-bottom: 1px solid rgb(255 255 255 / 0.08);
  padding-bottom: 36px;
}

.eyebrow,
.panel-kicker {
  margin: 0 0 10px;
  color: rgb(255 255 255 / 0.42);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.hero-copy h1 {
  margin: 0;
  max-width: 780px;
  font-size: clamp(44px, 8vw, 96px);
  line-height: 0.95;
  letter-spacing: 0;
}

.hero-copy p {
  margin: 18px 0 0;
  max-width: 560px;
  color: rgb(255 255 255 / 0.52);
  font-size: 15px;
  line-height: 1.8;
}

.hero-avatar {
  display: grid;
  gap: 14px;
  justify-items: center;
}

.hero-avatar button,
.primary-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.86);
  padding: 10px 16px;
  font-weight: 700;
  transition: transform 0.18s ease, background 0.18s ease, border-color 0.18s ease;
}

.hero-avatar button:hover:not(:disabled),
.primary-action:hover:not(:disabled) {
  transform: translateY(-1px);
  border-color: rgb(176 92 255 / 0.42);
  background: rgb(176 92 255 / 0.16);
}

.hero-avatar button:disabled,
.primary-action:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.alert {
  margin-top: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: 18px;
  padding: 12px 14px;
  font-size: 13px;
}

.alert-error {
  border: 1px solid rgb(248 113 113 / 0.26);
  background: rgb(127 29 29 / 0.2);
  color: rgb(254 202 202);
}

.alert-success {
  border: 1px solid rgb(52 211 153 / 0.24);
  background: rgb(6 95 70 / 0.2);
  color: rgb(167 243 208);
}

.profile-grid {
  margin-top: 28px;
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(300px, 0.9fr);
  gap: 22px;
}

.profile-panel {
  min-height: 320px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 28px;
  background:
    linear-gradient(180deg, rgb(255 255 255 / 0.055), rgb(255 255 255 / 0.025)),
    rgb(24 24 28 / 0.72);
  padding: 24px;
  box-shadow: 0 24px 80px rgb(0 0 0 / 0.35), inset 0 1px 0 rgb(255 255 255 / 0.06);
  backdrop-filter: blur(18px);
}

.profile-panel h2 {
  margin: 0;
  font-size: 26px;
}

.edit-panel {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.edit-panel label {
  display: grid;
  gap: 9px;
}

.edit-panel label span,
.account-lines span,
.stat-list span,
.profile-note {
  color: rgb(255 255 255 / 0.42);
  font-size: 13px;
}

.edit-panel input {
  height: 48px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 16px;
  background: rgb(0 0 0 / 0.2);
  color: #fff;
  outline: none;
  padding: 0 14px;
}

.edit-panel input:focus {
  border-color: rgb(176 92 255 / 0.46);
  box-shadow: 0 0 0 3px rgb(176 92 255 / 0.13);
}

.bio-field {
  display: grid;
  gap: 8px;
}

.bio-field span {
  color: rgb(255 255 255 / 0.42);
  font-size: 13px;
}

.bio-field textarea {
  min-height: 86px;
  resize: none;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 16px;
  background: rgb(0 0 0 / 0.2);
  color: #fff;
  outline: none;
  padding: 12px 14px;
  line-height: 1.6;
}

.bio-field textarea:focus {
  border-color: rgb(176 92 255 / 0.46);
  box-shadow: 0 0 0 3px rgb(176 92 255 / 0.13);
}

.account-lines,
.stat-list {
  display: grid;
  gap: 12px;
}

.account-lines div,
.stat-list div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid rgb(255 255 255 / 0.07);
  padding: 12px 0;
}

.account-lines strong,
.stat-list strong {
  color: rgb(255 255 255 / 0.82);
  font-size: 14px;
}

.primary-action {
  align-self: flex-start;
  border-color: rgb(176 92 255 / 0.4);
  background: linear-gradient(135deg, rgb(205 132 255), rgb(176 92 255));
  box-shadow: 0 16px 42px rgb(176 92 255 / 0.22);
}

.primary-action.compact {
  align-self: auto;
  min-height: 42px;
}

.secondary-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 42px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.78);
  padding: 0 15px;
  font-size: 13px;
  font-weight: 700;
}

.stats-panel {
  display: flex;
  flex-direction: column;
  gap: 26px;
}

.stat-list.loading {
  opacity: 0.62;
}

.stat-list div {
  justify-content: flex-start;
}

.stat-list svg {
  color: rgb(196 142 255);
}

.stat-list strong {
  margin-left: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 22px;
}

.profile-note {
  margin-top: auto;
  line-height: 1.7;
}

.community-settings {
  display: grid;
  gap: 13px;
  border-top: 1px solid rgb(255 255 255 / 0.08);
  padding-top: 20px;
}

.settings-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.74);
  font-weight: 800;
}

.switch-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: rgb(255 255 255 / 0.64);
  font-size: 13px;
}

.switch-line input {
  width: 42px;
  height: 24px;
  accent-color: #b05cff;
}

.settings-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.danger-zone {
  margin-top: 22px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-top: 1px solid rgb(248 113 113 / 0.2);
  padding-top: 24px;
}

.danger-zone h2 {
  margin: 0;
  font-size: 22px;
}

.danger-zone p {
  margin: 10px 0 0;
  max-width: 720px;
  color: rgb(255 255 255 / 0.52);
  font-size: 13px;
  line-height: 1.7;
}

.danger-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 42px;
  border: 1px solid rgb(248 113 113 / 0.42);
  border-radius: 999px;
  background: rgb(127 29 29 / 0.22);
  color: rgb(254 202 202);
  padding: 0 16px;
  font-size: 13px;
  font-weight: 800;
}

.danger-action:hover:not(:disabled) {
  background: rgb(185 28 28 / 0.28);
}

.danger-action:disabled {
  cursor: not-allowed;
  opacity: 0.54;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(0 0 0 / 0.68);
  backdrop-filter: blur(12px);
}

.cancel-dialog {
  position: relative;
  width: min(520px, 100%);
  max-height: calc(100vh - 40px);
  overflow-y: auto;
  border: 1px solid rgb(15 23 42 / 0.08);
  background: #fff;
  box-shadow: 0 30px 90px rgb(0 0 0 / 0.28);
  color: #111827;
  padding: 18px;
}

.cancel-dialog h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 900;
}

.icon-action {
  position: absolute;
  top: 16px;
  right: 16px;
  display: inline-flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(15 23 42 / 0.08);
  border-radius: 50%;
  background: #fff;
  color: #475569;
}

.cancel-warning-card {
  display: grid;
  gap: 14px;
  margin-top: 14px;
  border-radius: 10px;
  background: #f5f6f8;
  padding: 16px 14px;
}

.cancel-warning-card section {
  display: grid;
  gap: 5px;
}

.cancel-warning-card h3 {
  margin: 0;
  color: #111827;
  font-size: 14px;
  font-weight: 900;
}

.cancel-warning-card p {
  margin: 0;
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
  border-radius: 10px;
  background: #fff;
  padding: 12px;
}

.cancel-checks span,
.sms-field span,
.confirm-field span {
  display: block;
  color: #111827;
  font-size: 12px;
  font-weight: 800;
}

.cancel-checks strong {
  display: block;
  margin-top: 6px;
  color: #111827;
  font-size: 22px;
}

.sms-field,
.confirm-field {
  display: grid;
  gap: 8px;
  margin-top: 18px;
}

.sms-field div {
  display: flex;
  gap: 10px;
}

.cancel-dialog .secondary-action {
  border-color: #e5e7eb;
  background: #f8fafc;
  color: #111827;
}

.sms-field input,
.confirm-field input {
  min-width: 0;
  flex: 1;
  height: 44px;
  border: 1px solid #111827;
  border-radius: 999px;
  background: #fff;
  color: #111827;
  outline: none;
  padding: 0 13px;
}

.confirm-field input {
  width: 100%;
}

.cancel-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 18px;
  margin-top: 20px;
}

.cancel-text-action {
  border: 0;
  background: transparent;
  color: #111827;
  font-size: 14px;
  font-weight: 700;
}

.danger-action.confirm {
  min-width: 84px;
  border: 0;
  background: #fb9ca5;
  color: #fff;
}

.danger-action.confirm:hover:not(:disabled) {
  background: #f8717d;
}

@media (max-width: 900px) {
  .profile-page {
    padding: 22px;
  }

  .profile-hero,
  .profile-grid {
    grid-template-columns: 1fr;
  }

  .profile-hero {
    align-items: start;
  }

  .hero-avatar {
    justify-items: start;
  }

  .danger-zone,
  .sms-field div,
  .cancel-dialog-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .danger-action {
    width: 100%;
  }
}
</style>
