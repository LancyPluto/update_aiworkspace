<script setup lang="ts">
import {
  Box,
  Document,
  Files,
  Operation,
  SwitchButton,
  User,
  View
} from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { logout } from '@/api/auth'
import { clearSession, getCurrentUser } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const currentUser = computed(() => getCurrentUser())

const activeMenu = computed(() => {
  if (route.path.startsWith('/tasks')) return '/tasks'
  if (route.path.startsWith('/users')) return '/users'
  return '/tools'
})

async function handleLogout() {
  loading.value = true
  try {
    await logout()
  } catch {
    // 后端登出目前是空实现，前端仍然清理本地会话。
  } finally {
    clearSession()
    loading.value = false
    router.push({ name: 'login' })
  }
}
</script>

<template>
  <el-container class="admin-shell">
    <el-aside width="236px" class="sidebar">
      <div class="brand">
        <div class="brand-mark">AI</div>
        <div>
          <strong>工具市场后台</strong>
          <span>Admin Console</span>
        </div>
      </div>

      <el-menu :default-active="activeMenu" router class="side-menu">
        <el-menu-item index="/tools">
          <el-icon><Box /></el-icon>
          <span>工具管理</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><Files /></el-icon>
          <span>任务管理</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon>
          <span>用户与算力</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="breadcrumb">
          <el-icon><Operation /></el-icon>
          <span>成员4最小管理后台</span>
        </div>
        <div class="user-area">
          <el-tag type="success" effect="light">
            <el-icon><View /></el-icon>
            {{ currentUser?.userType || 'ADMIN' }}
          </el-tag>
          <span class="nickname">{{ currentUser?.nickname || currentUser?.username || '管理员' }}</span>
          <el-button :loading="loading" :icon="SwitchButton" text @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <el-main class="content">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-shell {
  min-height: 100vh;
}

.sidebar {
  background: #111827;
  color: #fff;
}

.brand {
  display: flex;
  gap: 12px;
  align-items: center;
  height: 72px;
  padding: 0 20px;
}

.brand-mark {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 12px;
  background: linear-gradient(135deg, #409eff, #67c23a);
  font-weight: 800;
}

.brand span {
  display: block;
  margin-top: 4px;
  color: #9ca3af;
  font-size: 12px;
}

.side-menu {
  border-right: 0;
  background: transparent;
}

.side-menu :deep(.el-menu-item) {
  color: #cbd5e1;
}

.side-menu :deep(.el-menu-item.is-active),
.side-menu :deep(.el-menu-item:hover) {
  background: #1f2937;
  color: #fff;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  border-bottom: 1px solid #eef0f4;
  background: #fff;
}

.breadcrumb,
.user-area {
  display: flex;
  gap: 10px;
  align-items: center;
}

.nickname {
  color: #374151;
  font-weight: 600;
}

.content {
  padding: 24px;
  background: #f5f7fb;
}
</style>
