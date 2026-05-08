<script setup>
import { RouterLink } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const router = useRouter()

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <header class="app-header">
    <RouterLink class="brand" to="/tools">AI 工具市场</RouterLink>
    <nav class="nav" v-if="auth.isLoggedIn">
      <RouterLink to="/tools">工具</RouterLink>
      <RouterLink to="/my-tasks">我的任务</RouterLink>
      <button type="button" class="btn-text" @click="logout">退出</button>
    </nav>
  </header>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border);
  background: var(--bg);
  position: sticky;
  top: 0;
  z-index: 10;
}

.brand {
  font-weight: 600;
  color: var(--text-h);
  text-decoration: none;
  font-size: 18px;
}

.brand:hover {
  color: var(--accent);
}

.nav {
  display: flex;
  align-items: center;
  gap: 20px;
}

.nav a {
  color: var(--text);
  text-decoration: none;
  font-size: 15px;
}

.nav a.router-link-active {
  color: var(--accent);
  font-weight: 500;
}

.btn-text {
  background: none;
  border: none;
  color: var(--text);
  cursor: pointer;
  font: inherit;
  font-size: 15px;
  padding: 0;
}

.btn-text:hover {
  color: var(--accent);
}
</style>
