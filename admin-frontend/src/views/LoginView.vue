<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { login } from '@/api/auth'
import { setSession } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({
  account: 'admin',
  password: '123456'
})

async function submit() {
  if (!form.account || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const response = await login(form.account, form.password)
    if (response.user.userType !== 'ADMIN') {
      ElMessage.error('普通用户不能访问后台')
      return
    }
    setSession(response.accessToken, response.user)
    ElMessage.success('登录成功')
    router.push((route.query.redirect as string) || '/tools')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="hero">
        <div class="logo">AI</div>
        <h1>AI 工具市场管理后台</h1>
        <p>配置工具、发布 Prompt、查看任务与用户算力的 V1 最小后台。</p>
      </div>

      <el-card class="panel" shadow="never">
        <h2>管理员登录</h2>
        <p class="muted">默认账号 admin / 123456，实际以数据库初始化数据为准。</p>
        <el-form label-position="top" @keyup.enter="submit">
          <el-form-item label="账号">
            <el-input v-model="form.account" :prefix-icon="User" placeholder="请输入管理员账号" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" :prefix-icon="Lock" placeholder="请输入密码" show-password type="password" />
          </el-form-item>
          <el-button type="primary" size="large" :loading="loading" class="submit" @click="submit">
            登录后台
          </el-button>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  background:
    radial-gradient(circle at 20% 20%, rgb(64 158 255 / 18%), transparent 28%),
    linear-gradient(135deg, #101827, #1f2937);
}

.login-card {
  display: grid;
  grid-template-columns: 1fr 420px;
  width: min(980px, 92vw);
  overflow: hidden;
  border-radius: 24px;
  background: rgb(255 255 255 / 8%);
  box-shadow: 0 30px 80px rgb(0 0 0 / 28%);
}

.hero {
  padding: 64px;
  color: #fff;
}

.logo {
  display: grid;
  width: 64px;
  height: 64px;
  margin-bottom: 36px;
  place-items: center;
  border-radius: 18px;
  background: linear-gradient(135deg, #409eff, #67c23a);
  font-size: 24px;
  font-weight: 800;
}

.hero h1 {
  margin: 0 0 16px;
  font-size: 34px;
}

.hero p {
  max-width: 420px;
  color: #cbd5e1;
  line-height: 1.8;
}

.panel {
  padding: 28px 20px;
  border: 0;
  border-radius: 0;
}

.panel h2 {
  margin: 0 0 8px;
}

.submit {
  width: 100%;
  margin-top: 12px;
}
</style>
