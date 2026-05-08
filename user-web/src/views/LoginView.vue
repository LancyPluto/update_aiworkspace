<script setup>
  import { ref, computed } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { useAuthStore } from '../stores/auth'
  import { requestErrorMessage } from '../utils/errors'

  const route = useRoute()
  const router = useRouter()
  const auth = useAuthStore()

  const mode = ref('login')
  const loading = ref(false)
  const errorMsg = ref('')

  const form = ref({
    username: '',
    password: '',
    email: '',
  })

  const title = computed(() => (mode.value === 'login' ? '登录' : '注册'))

  async function submit() {
    errorMsg.value = ''
    loading.value = true
    try {
      //登录模式
      if (mode.value === 'login') {
        await auth.login({
          account: form.value.username.trim(),
          password: form.value.password,
        })
        const redirect = /** @type {string} */ (route.query.redirect || '/tools')
        router.replace(redirect)
      } 
      //注册模式
      else {
        await auth.register({
          username: form.value.username.trim(),
          password: form.value.password,
          email: form.value.email.trim() || undefined,
        })
        await auth.login({
          account: form.value.username.trim(),
          password: form.value.password,
        })
        router.replace('/tools')
      }
    } catch (e) {
      errorMsg.value = requestErrorMessage(e)
    } finally {
      loading.value = false
    }
  }


  function switchMode(m) {
    mode.value = m
    errorMsg.value = ''
  }
</script>

<template>
  <div class="login-page">
    <div class="card">
      <h1 class="page-title">{{ title }}</h1>
      <p class="hint">使用账号访问工具与任务</p>

      <div class="tabs">
        <button
          type="button"
          :class="{ active: mode === 'login' }"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          :class="{ active: mode === 'register' }"
          @click="switchMode('register')"
        >
          注册
        </button>
      </div>

      <form class="form" @submit.prevent="submit">
        <label class="field">
          <span>用户名</span>
          <input
            v-model="form.username"
            type="text"
            autocomplete="username"
            required
            placeholder="用户名"
          />
        </label>
        <label v-if="mode === 'register'" class="field">
          <span>邮箱（可选）</span>
          <input
            v-model="form.email"
            type="email"
            autocomplete="email"
            placeholder="you@example.com"
          />
        </label>
        <label class="field">
          <span>密码</span>
          <input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            required
            minlength="6"
            placeholder="至少 6 位"
          />
        </label>

        <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

        <button type="submit" class="btn-primary" :disabled="loading">
          {{ loading ? '提交中…' : mode === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
  .login-page {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 40px 20px;
  }

  .card {
    width: 100%;
    max-width: 400px;
    text-align: left;
    padding: 32px;
    border: 1px solid var(--border);
    border-radius: 12px;
    box-shadow: var(--shadow);
  }

  .page-title {
    font-size: 28px;
    margin: 0 0 8px;
  }

  .hint {
    margin: 0 0 24px;
    color: var(--text);
    font-size: 15px;
  }

  .tabs {
    display: flex;
    gap: 8px;
    margin-bottom: 24px;
  }

  .tabs button {
    flex: 1;
    padding: 10px;
    border: 1px solid var(--border);
    background: var(--bg);
    border-radius: 8px;
    cursor: pointer;
    font: inherit;
    color: var(--text);
  }

  .tabs button.active {
    border-color: var(--accent-border);
    background: var(--accent-bg);
    color: var(--text-h);
  }

  .form {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: 6px;
    font-size: 14px;
    color: var(--text-h);
  }

  .field input {
    padding: 10px 12px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--bg);
    color: var(--text-h);
    font: inherit;
  }

  .error {
    color: #ef4444;
    margin: 0;
    font-size: 14px;
  }

  .btn-primary {
    margin-top: 8px;
    padding: 12px 16px;
    border: none;
    border-radius: 8px;
    background: var(--accent);
    color: #fff;
    font: inherit;
    font-weight: 500;
    cursor: pointer;
  }

  .btn-primary:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
</style>
