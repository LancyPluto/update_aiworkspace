<script setup lang="ts">
import { reactive, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import { Sparkles, ShieldCheck, Zap, Boxes, Loader2 } from "lucide-vue-next"
import { useAuthStore } from "@/store/authStore"
import { ApiBusinessError } from "@/api"

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

type AuthMode = "login" | "register"
const mode = ref<AuthMode>("login")

const form = reactive({
  account: "user1",
  password: "123456",
})

const registerForm = reactive({
  username: "",
  nickname: "",
  password: "",
  confirmPassword: "",
})

const errorMsg = ref<string | null>(null)
const submitting = ref(false)

watch(mode, () => {
  errorMsg.value = null
})

function safeInternalRedirect(redirect: string | null): string | { name: "ToolList" } {
  if (!redirect || !redirect.startsWith("/") || redirect.startsWith("//")) {
    return { name: "ToolList" }
  }
  return redirect
}

async function handleLogin() {
  if (!form.account || !form.password) {
    errorMsg.value = "请输入账号和密码"
    return
  }
  errorMsg.value = null
  submitting.value = true
  try {
    await auth.login({ account: form.account, password: form.password })
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : null
    router.push(safeInternalRedirect(redirect))
  } catch (e) {
    if (e instanceof ApiBusinessError) {
      errorMsg.value = e.message
    } else {
      errorMsg.value = (e as Error).message || "登录失败，请稍后重试"
    }
  } finally {
    submitting.value = false
  }
}

async function handleRegister() {
  const u = registerForm.username.trim()
  if (!u) {
    errorMsg.value = "请输入用户名"
    return
  }
  if (!registerForm.password) {
    errorMsg.value = "请设置密码"
    return
  }
  if (registerForm.password.length < 6) {
    errorMsg.value = "密码至少 6 位"
    return
  }
  if (registerForm.password.length > 64) {
    errorMsg.value = "密码最长 64 位"
    return
  }
  if (registerForm.password !== registerForm.confirmPassword) {
    errorMsg.value = "两次输入的密码不一致"
    return
  }
  errorMsg.value = null
  submitting.value = true
  try {
    const nick = registerForm.nickname.trim()
    await auth.register({
      username: u,
      password: registerForm.password,
      ...(nick ? { nickname: nick } : {}),
    })
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : null
    router.push(safeInternalRedirect(redirect))
  } catch (e) {
    if (e instanceof ApiBusinessError) {
      errorMsg.value = e.message
    } else {
      errorMsg.value = (e as Error).message || "注册失败，请稍后重试"
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-layout">
    <aside class="hero-side">
      <div class="blur-bg" aria-hidden>
        <div class="blur-circle top-left" />
        <div class="blur-circle bottom-right" />
      </div>
      <div class="brand">
        <div class="brand-icon">
          <Sparkles class="icon" />
        </div>
        <span class="brand-name">智效 AI 工作台</span>
      </div>

      <div class="hero-content">
        <h1 class="hero-title">
          企业级 AI 工具中台
          <br />
          让每位员工都拥有专属 AI 助手
        </h1>
        <p class="hero-desc">
          统一管理 Prompt、工具、算力与素材，按部门精细化分配权限与配额，沉淀企业知识资产。
        </p>
        <div class="stats-grid">
          <div class="stat-item">
            <Boxes class="stat-icon" />120+ 工具
          </div>
          <div class="stat-item">
            <ShieldCheck class="stat-icon" />企业合规
          </div>
          <div class="stat-item">
            <Zap class="stat-icon" />多模型路由
          </div>
        </div>
      </div>

      <div class="footer-copyright">© 2026 智效科技 · 已服务 500+ 企业客户</div>
    </aside>

    <main class="login-main">
      <div class="login-container">
        <div class="mobile-brand">
          <div class="brand-icon">
            <Sparkles class="icon" />
          </div>
          <span class="brand-name">智效 AI 工作台</span>
        </div>

        <div class="login-header">
          <h2 class="login-title">{{ mode === "login" ? "登录账号" : "注册账号" }}</h2>
          <p class="login-subtitle">
            {{
              mode === "login"
                ? "使用企业账号登录，开始你的 AI 工作流"
                : "创建新账号，加入智效 AI 工作台"
            }}
          </p>
        </div>

        <div class="auth-mode-tabs" role="tablist" aria-label="登录或注册">
          <button
            type="button"
            role="tab"
            class="auth-tab"
            :class="{ active: mode === 'login' }"
            :aria-selected="mode === 'login'"
            @click="mode = 'login'"
          >
            登录
          </button>
          <button
            type="button"
            role="tab"
            class="auth-tab"
            :class="{ active: mode === 'register' }"
            :aria-selected="mode === 'register'"
            @click="mode = 'register'"
          >
            注册
          </button>
        </div>

        <form v-if="mode === 'login'" class="login-card" @submit.prevent="handleLogin">
          <label class="input-group">
            <span class="input-label">账号</span>
            <input
              v-model="form.account"
              type="text"
              class="input-field"
              placeholder="请输入账号"
              autocomplete="username"
            />
          </label>
          <label class="input-group">
            <span class="input-label">登录密码</span>
            <input
              v-model="form.password"
              type="password"
              class="input-field"
              placeholder="请输入密码"
              autocomplete="current-password"
            />
          </label>

          <p v-if="errorMsg" class="error-message">{{ errorMsg }}</p>

          <button
            type="submit"
            class="login-btn"
            :disabled="submitting"
          >
            <Loader2 v-if="submitting" class="spin-icon" />
            {{ submitting ? "登录中..." : "登录工作台" }}
          </button>
        </form>

        <form v-else class="login-card" @submit.prevent="handleRegister">
          <label class="input-group">
            <span class="input-label">用户名</span>
            <input
              v-model="registerForm.username"
              type="text"
              class="input-field"
              placeholder="用于登录，不可与他人重复"
              autocomplete="username"
            />
          </label>
          <label class="input-group">
            <span class="input-label">昵称（可选）</span>
            <input
              v-model="registerForm.nickname"
              type="text"
              class="input-field"
              placeholder="不填则默认与用户名相同"
              autocomplete="nickname"
            />
          </label>
          <label class="input-group">
            <span class="input-label">密码</span>
            <input
              v-model="registerForm.password"
              type="password"
              class="input-field"
              placeholder="至少 6 位，最长 64 位"
              autocomplete="new-password"
            />
          </label>
          <label class="input-group">
            <span class="input-label">确认密码</span>
            <input
              v-model="registerForm.confirmPassword"
              type="password"
              class="input-field"
              placeholder="再次输入密码"
              autocomplete="new-password"
            />
          </label>

          <p v-if="errorMsg" class="error-message">{{ errorMsg }}</p>

          <button
            type="submit"
            class="login-btn"
            :disabled="submitting"
          >
            <Loader2 v-if="submitting" class="spin-icon" />
            {{ submitting ? "提交中..." : "创建账号并登录" }}
          </button>
        </form>

        <p class="footer-link">
          <RouterLink :to="{ name: 'ToolList' }" class="toolstore-link">
            进入 AI 工具超市
          </RouterLink>
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
  /* 布局 */
  .login-layout {
    min-height: 100vh;
    display: grid;
    background-color: var(--background);
  }
  @media (min-width: 1024px) {
    .login-layout {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  /* 左侧品牌区 */
  .hero-side {
    position: relative;
    display: none;
    flex-direction: column;
    justify-content: space-between;
    padding: 3rem;
    background: linear-gradient(to bottom right, var(--card), var(--accent) / 0.3);
    border-right: 1px solid var(--border);
    overflow: hidden;
  }
  @media (min-width: 1024px) {
    .hero-side {
      display: flex;
    }
  }

  .blur-bg {
    position: absolute;
    inset: 0;
    opacity: 0.2;
    pointer-events: none;
  }
  .blur-circle {
    position: absolute;
    width: 18rem;
    height: 18rem;
    border-radius: 9999px;
    filter: blur(3rem);
  }
  .blur-circle.top-left {
    top: 5rem;
    left: 5rem;
    background-color: var(--primary);
  }
  .blur-circle.bottom-right {
    bottom: 2.5rem;
    right: 2.5rem;
    background-color: var(--accent);
  }

  .brand {
    position: relative;
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .brand-icon {
    display: flex;
    height: 2.25rem;
    width: 2.25rem;
    align-items: center;
    justify-content: center;
    border-radius: 0.5rem;
    background-color: var(--primary);
    color: var(--primary-foreground);
  }
  .icon {
    height: 1.25rem;
    width: 1.25rem;
  }
  .brand-name {
    font-size: 1.125rem;
    font-weight: 600;
    letter-spacing: -0.025em;
  }

  .hero-content {
    position: relative;
    margin: 2rem 0;
    display: flex;
    flex-direction: column;
    gap: 1.5rem;
  }
  .hero-title {
    font-size: 2.25rem;
    font-weight: 600;
    line-height: 1.2;
    text-wrap: balance;
  }
  .hero-desc {
    font-size: 0.875rem;
    color: var(--muted-foreground);
    line-height: 1.625;
    max-width: 28rem;
  }
  .stats-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 1rem;
    padding-top: 1rem;
    max-width: 28rem;
  }
  .stat-item {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    border-radius: 0.5rem;
    border: 1px solid var(--border);
    background-color: var(--card) / 0.6;
    backdrop-filter: blur(4px);
    padding: 0.75rem;
    font-size: 0.75rem;
  }
  .stat-icon {
    height: 1rem;
    width: 1rem;
    color: var(--primary);
  }

  .footer-copyright {
    position: relative;
    font-size: 0.75rem;
    color: var(--muted-foreground);
  }

  /* 右侧登录区 */
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
    gap: 2rem;
  }
  .mobile-brand {
    display: flex;
    align-items: center;
    gap: 0.5rem;
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
    letter-spacing: -0.025em;
  }
  .login-subtitle {
    font-size: 0.875rem;
    color: var(--muted-foreground);
  }

  .auth-mode-tabs {
    display: flex;
    gap: 0.25rem;
    padding: 0.25rem;
    border-radius: 0.5rem;
    border: 1px solid var(--border);
    background-color: var(--muted) / 0.35;
  }
  .auth-tab {
    flex: 1;
    border: none;
    border-radius: 0.375rem;
    padding: 0.5rem 0.75rem;
    font-size: 0.875rem;
    font-weight: 500;
    color: var(--muted-foreground);
    background: transparent;
    cursor: pointer;
  }
  .auth-tab:hover {
    color: var(--foreground);
  }
  .auth-tab.active {
    background-color: var(--card);
    color: var(--foreground);
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
  }

  .login-card {
    border-radius: 0.75rem;
    border: 1px solid var(--border);
    background-color: var(--card);
    padding: 1.5rem;
    display: flex;
    flex-direction: column;
    gap: 1rem;
    box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  }
  .input-group {
    display: block;
  }
  .input-label {
    display: block;
    font-size: 0.875rem;
    font-weight: 500;
    margin-bottom: 0.5rem;
  }
  .input-field {
    display: flex;
    height: 2.5rem;
    width: 100%;
    border-radius: 0.375rem;
    border: 1px solid var(--border);
    background-color: var(--background);
    padding: 0.5rem 0.75rem;
    font-size: 0.875rem;
  }
  .input-field:focus {
    outline: none;
    box-shadow: 2px solid var(--ring);
  }
  .error-message {
    color: var(--destructive);
    font-size: 0.8rem;
    padding: 0.5rem;
    border-radius: 0.375rem;
    background-color: var(--destructive) / 0.1;
    border: 1px solid var(--destructive) / 0.3;
  }
  .login-btn {
    display: inline-flex;
    height: 2.75rem;
    width: 100%;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    border-radius: 0.375rem;
    background-color: var(--primary);
    padding: 0 1rem;
    font-size: 0.875rem;
    font-weight: 500;
    color: var(--primary-foreground);
    text-decoration: none;
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
    height: 1rem;
    width: 1rem;
    animation: spin 1s linear infinite;
  }
  @keyframes spin {
    to { transform: rotate(360deg); }
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
