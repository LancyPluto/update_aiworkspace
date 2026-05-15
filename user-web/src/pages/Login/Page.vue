<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import { Boxes, KeyRound, Loader2, MessageSquareText, ShieldCheck, UserPlus, Zap } from "lucide-vue-next"
import { ApiBusinessError, sendSmsCode } from "@/api"
import type { SmsCodeScene } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

type Mode = "smsLogin" | "passwordLogin" | "register"

const mode = ref<Mode>("smsLogin")
const smsForm = reactive({
  phone: "",
  code: "",
  nickname: "",
})
const passwordForm = reactive({
  account: "user1",
  password: "123456",
})

const errorMsg = ref<string | null>(null)
const tipMsg = ref<string | null>(null)
const submitting = ref(false)
const sendingCode = ref(false)
const countdown = ref(0)
let countdownTimer: number | null = null

const title = computed(() => {
  if (mode.value === "passwordLogin") return "账号密码登录"
  return mode.value === "register" ? "手机号注册" : "手机号登录"
})

const subtitle = computed(() => {
  if (mode.value === "passwordLogin") return "使用账号或手机号和密码登录"
  return mode.value === "register" ? "验证手机号后自动创建账号并登录" : "输入短信验证码，安全进入工作台"
})

const scene = computed<SmsCodeScene>(() => (mode.value === "register" ? "REGISTER" : "LOGIN"))

function switchMode(nextMode: Mode) {
  mode.value = nextMode
  errorMsg.value = null
  tipMsg.value = null
  smsForm.code = ""
}

function safeInternalRedirect(redirect: string | null): string | { name: "ToolList" } {
  if (!redirect || !redirect.startsWith("/") || redirect.startsWith("//")) {
    return { name: "ToolList" }
  }
  return redirect
}

async function handleSendCode() {
  const phone = normalizePhone()
  if (!phone) return
  errorMsg.value = null
  tipMsg.value = null
  sendingCode.value = true
  try {
    const res = await sendSmsCode({ phone, scene: scene.value })
    tipMsg.value = res.debugCode ? `开发验证码：${res.debugCode}` : "验证码已发送，请查看手机短信"
    startCountdown(res.cooldownSeconds || 60)
  } catch (e) {
    errorMsg.value = resolveError(e, "验证码发送失败，请稍后重试")
  } finally {
    sendingCode.value = false
  }
}

async function handleSubmit() {
  if (mode.value === "passwordLogin") {
    await handlePasswordLogin()
  } else {
    await handleSmsAuth()
  }
}

async function handlePasswordLogin() {
  const account = passwordForm.account.trim()
  if (!account || !passwordForm.password) {
    errorMsg.value = "请输入账号和密码"
    return
  }
  await runAuthAction(async () => {
    await auth.login({ account, password: passwordForm.password })
  }, "登录失败，请稍后重试")
}

async function handleSmsAuth() {
  const phone = normalizePhone()
  if (!phone) return
  const code = smsForm.code.trim()
  if (!/^\d{6}$/.test(code)) {
    errorMsg.value = "请输入 6 位短信验证码"
    return
  }

  await runAuthAction(async () => {
    if (mode.value === "register") {
      await auth.smsRegister({
        phone,
        code,
        nickname: smsForm.nickname.trim() || undefined,
      })
    } else {
      await auth.smsLogin({ phone, code })
    }
  }, mode.value === "register" ? "注册失败，请稍后重试" : "登录失败，请稍后重试")
}

async function runAuthAction(action: () => Promise<void>, fallback: string) {
  errorMsg.value = null
  submitting.value = true
  try {
    await action()
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : null
    router.push(safeInternalRedirect(redirect))
  } catch (e) {
    errorMsg.value = resolveError(e, fallback)
  } finally {
    submitting.value = false
  }
}

function normalizePhone() {
  const phone = smsForm.phone.trim()
  if (!/^1\d{10}$/.test(phone)) {
    errorMsg.value = "请输入正确的 11 位手机号"
    return null
  }
  return phone
}

function resolveError(error: unknown, fallback: string) {
  if (error instanceof ApiBusinessError) return error.message
  return (error as Error).message || fallback
}

function startCountdown(seconds: number) {
  countdown.value = seconds
  if (countdownTimer !== null) {
    window.clearInterval(countdownTimer)
  }
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && countdownTimer !== null) {
      window.clearInterval(countdownTimer)
      countdownTimer = null
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (countdownTimer !== null) {
    window.clearInterval(countdownTimer)
  }
})
</script>

<template>
  <div class="login-layout">
    <aside class="hero-side">
      <div class="brand">
        <img src="/logo.svg" alt="AI Tool Market" class="brand-logo" />
        <span class="brand-name">AI Tool Market</span>
      </div>

      <div class="hero-content">
        <h1 class="hero-title">
          企业级 AI 工具中台
          <br />
          让每位成员都有专属 AI 助手
        </h1>
        <p class="hero-desc">
          统一管理工具、任务、算力与素材，让团队更快完成内容生产和知识沉淀。
        </p>
        <div class="stats-grid">
          <div class="stat-item">
            <Boxes class="stat-icon" />工具市场
          </div>
          <div class="stat-item">
            <ShieldCheck class="stat-icon" />安全会话
          </div>
          <div class="stat-item">
            <Zap class="stat-icon" />即时创作
          </div>
        </div>
      </div>

      <div class="footer-copyright">© 2026 智效科技</div>
    </aside>

    <main class="login-main">
      <div class="login-container">
        <div class="mobile-brand">
          <img src="/logo.svg" alt="AI Tool Market" class="brand-logo" />
          <span class="brand-name">AI Tool Market</span>
        </div>

        <div class="login-header">
          <h2 class="login-title">{{ title }}</h2>
          <p class="login-subtitle">{{ subtitle }}</p>
        </div>

        <div class="mode-tabs" role="tablist" aria-label="认证方式">
          <button type="button" :class="{ active: mode === 'smsLogin' }" @click="switchMode('smsLogin')">
            <MessageSquareText class="tab-icon" />
            短信登录
          </button>
          <button type="button" :class="{ active: mode === 'passwordLogin' }" @click="switchMode('passwordLogin')">
            <KeyRound class="tab-icon" />
            密码登录
          </button>
          <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">
            <UserPlus class="tab-icon" />
            注册
          </button>
        </div>

        <form class="login-card" @submit.prevent="handleSubmit">
          <template v-if="mode === 'passwordLogin'">
            <label class="input-group">
              <span class="input-label">账号或手机号</span>
              <input
                v-model="passwordForm.account"
                type="text"
                class="input-field"
                placeholder="请输入账号或手机号"
                autocomplete="username"
              />
            </label>

            <label class="input-group">
              <span class="input-label">登录密码</span>
              <input
                v-model="passwordForm.password"
                type="password"
                class="input-field"
                placeholder="请输入密码"
                autocomplete="current-password"
              />
            </label>
          </template>

          <template v-else>
            <label class="input-group">
              <span class="input-label">手机号</span>
              <input
                v-model="smsForm.phone"
                type="tel"
                class="input-field"
                placeholder="请输入 11 位手机号"
                autocomplete="tel"
                inputmode="numeric"
              />
            </label>

            <label v-if="mode === 'register'" class="input-group">
              <span class="input-label">昵称</span>
              <input
                v-model="smsForm.nickname"
                type="text"
                class="input-field"
                placeholder="可选"
                autocomplete="nickname"
              />
            </label>

            <label class="input-group">
              <span class="input-label">短信验证码</span>
              <div class="code-row">
                <input
                  v-model="smsForm.code"
                  type="text"
                  class="input-field"
                  placeholder="6 位验证码"
                  autocomplete="one-time-code"
                  inputmode="numeric"
                  maxlength="6"
                />
                <button type="button" class="code-btn" :disabled="sendingCode || countdown > 0" @click="handleSendCode">
                  <Loader2 v-if="sendingCode" class="spin-icon" />
                  <span v-else>{{ countdown > 0 ? `${countdown}s` : "获取验证码" }}</span>
                </button>
              </div>
            </label>
          </template>

          <p v-if="tipMsg" class="tip-message">{{ tipMsg }}</p>
          <p v-if="errorMsg" class="error-message">{{ errorMsg }}</p>

          <button type="submit" class="login-btn" :disabled="submitting">
            <Loader2 v-if="submitting" class="spin-icon" />
            {{ submitting ? "处理中..." : mode === "register" ? "注册并登录" : "登录工作台" }}
          </button>
        </form>

        <p class="footer-link">
          <RouterLink :to="{ name: 'ToolList' }" class="toolstore-link">
            进入 AI 工具市场
          </RouterLink>
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-layout {
  min-height: 100vh;
  display: grid;
  background: var(--background);
}

@media (min-width: 1024px) {
  .login-layout {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
}

.hero-side {
  display: none;
  flex-direction: column;
  justify-content: space-between;
  padding: 3rem;
  border-right: 1px solid var(--border);
  background: color-mix(in srgb, var(--card) 92%, var(--primary) 8%);
}

@media (min-width: 1024px) {
  .hero-side {
    display: flex;
  }
}

.brand,
.mobile-brand {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.brand-logo {
  height: 2.25rem;
  width: 2.25rem;
  border-radius: 0.5rem;
  object-fit: contain;
}

.brand-name {
  font-size: 1.125rem;
  font-weight: 600;
}

.hero-content {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  margin: 2rem 0;
}

.hero-title {
  font-size: 2.25rem;
  font-weight: 600;
  line-height: 1.2;
}

.hero-desc {
  max-width: 28rem;
  font-size: 0.875rem;
  color: var(--muted-foreground);
  line-height: 1.625;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1rem;
  max-width: 28rem;
  padding-top: 1rem;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  background: var(--card);
  padding: 0.75rem;
  font-size: 0.75rem;
}

.stat-icon,
.tab-icon,
.spin-icon {
  height: 1rem;
  width: 1rem;
}

.stat-icon {
  color: var(--primary);
}

.footer-copyright {
  font-size: 0.75rem;
  color: var(--muted-foreground);
}

.login-main {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
}

@media (min-width: 640px) {
  .login-main {
    padding: 3rem;
  }
}

.login-container {
  width: 100%;
  max-width: 28rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

@media (min-width: 1024px) {
  .mobile-brand {
    display: none;
  }
}

.login-header {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.login-title {
  font-size: 1.5rem;
  font-weight: 600;
}

.login-subtitle {
  font-size: 0.875rem;
  color: var(--muted-foreground);
}

.mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.25rem;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  padding: 0.25rem;
  background: var(--muted);
}

.mode-tabs button {
  display: inline-flex;
  min-height: 2.5rem;
  align-items: center;
  justify-content: center;
  gap: 0.375rem;
  border: none;
  border-radius: 0.375rem;
  background: transparent;
  color: var(--muted-foreground);
  font-size: 0.8125rem;
  cursor: pointer;
}

.mode-tabs button.active {
  background: var(--background);
  color: var(--foreground);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

.login-card {
  border-radius: 0.75rem;
  border: 1px solid var(--border);
  background: var(--card);
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.input-group {
  display: block;
}

.input-label {
  display: block;
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
  font-weight: 500;
}

.input-field {
  height: 2.5rem;
  min-width: 0;
  width: 100%;
  border-radius: 0.375rem;
  border: 1px solid var(--border);
  background: var(--background);
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
}

.input-field:focus {
  outline: 2px solid var(--ring);
  outline-offset: 2px;
}

.code-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 7rem;
  gap: 0.5rem;
}

.code-btn {
  display: inline-flex;
  height: 2.5rem;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: 0.375rem;
  background: var(--background);
  color: var(--foreground);
  font-size: 0.875rem;
  cursor: pointer;
}

.code-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.tip-message,
.error-message {
  font-size: 0.8rem;
  padding: 0.5rem;
  border-radius: 0.375rem;
  overflow-wrap: anywhere;
}

.tip-message {
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--primary) 30%, transparent);
}

.error-message {
  color: var(--destructive);
  background: color-mix(in srgb, var(--destructive) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--destructive) 35%, transparent);
}

.login-btn {
  display: inline-flex;
  height: 2.75rem;
  width: 100%;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  border-radius: 0.375rem;
  background: var(--primary);
  padding: 0 1rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--primary-foreground);
  border: none;
  cursor: pointer;
}

.login-btn:hover {
  opacity: 0.9;
}

.login-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.footer-link {
  text-align: center;
  font-size: 0.75rem;
  color: var(--muted-foreground);
}

.toolstore-link {
  color: var(--primary);
  text-decoration: none;
}

.toolstore-link:hover {
  text-decoration: underline;
}
</style>
