<script setup lang="ts">
import { Coin } from '@element-plus/icons-vue'
import { onMounted, reactive, ref } from 'vue'

import { fetchMe } from '@/api/auth'
import { fetchCreditAccount, manualAddCredits } from '@/api/users'
import type { AdminUser, CreditAccount } from '@/types'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const dialogVisible = ref(false)
const currentUser = ref<AdminUser | null>(null)
const creditAccount = ref<CreditAccount | null>(null)
const creditForm = reactive({
  amount: 100,
  reason: '运营手动加算力'
})

async function loadAccount() {
  loading.value = true
  try {
    const [user, account] = await Promise.all([fetchMe(), fetchCreditAccount()])
    currentUser.value = user
    creditAccount.value = account
  } finally {
    loading.value = false
  }
}

function openCreditDialog() {
  creditForm.amount = 100
  creditForm.reason = '运营手动加算力'
  dialogVisible.value = true
}

async function submitCredit() {
  if (!currentUser.value) return
  await manualAddCredits(currentUser.value.id, {
    amount: creditForm.amount,
    reason: creditForm.reason
  })
  ElMessage.success('算力已增加')
  dialogVisible.value = false
  loadAccount()
}

onMounted(loadAccount)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">用户与算力</h1>
        <p class="page-subtitle">当前后端已提供当前账号资料和算力账户查询。</p>
      </div>
    </div>

    <el-alert
      title="成员4文档要求 GET /api/admin/v1/users 和手动加算力接口；当前后端尚未提供后台用户列表/加算力接口，页面先展示真实可用的当前账号与算力账户。"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <el-row :gutter="16">
      <el-col :span="10">
        <el-card v-loading="loading" class="page-card" shadow="never">
          <template #header>当前管理员</template>
          <el-empty v-if="!currentUser" description="暂无当前用户信息" />
          <el-descriptions v-else :column="1" border>
            <el-descriptions-item label="用户 ID">{{ currentUser.id }}</el-descriptions-item>
            <el-descriptions-item label="用户名">{{ currentUser.username }}</el-descriptions-item>
            <el-descriptions-item label="昵称">{{ currentUser.nickname }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ currentUser.userType }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card v-loading="loading" class="page-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>当前算力账户</span>
              <el-button @click="loadAccount">刷新</el-button>
            </div>
          </template>
          <el-empty v-if="!creditAccount" description="暂无算力账户信息" />
          <el-descriptions v-else :column="2" border>
            <el-descriptions-item label="账户 ID">{{ creditAccount.accountId }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ creditAccount.status }}</el-descriptions-item>
            <el-descriptions-item label="可用余额">{{ creditAccount.balance }}</el-descriptions-item>
            <el-descriptions-item label="冻结算力">{{ creditAccount.frozen }}</el-descriptions-item>
            <el-descriptions-item label="累计发放">{{ creditAccount.totalGranted }}</el-descriptions-item>
            <el-descriptions-item label="累计消耗">{{ creditAccount.totalConsumed }}</el-descriptions-item>
          </el-descriptions>
          <div class="credit-actions">
            <el-button type="primary" :icon="Coin" disabled @click="openCreditDialog">手动加算力</el-button>
            <span class="muted">等待后端补齐 /api/admin/v1/users/{userId}/credits/manual-add 后启用</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialogVisible" title="手动加算力" width="460px">
      <el-form label-width="90px">
        <el-form-item label="用户">
          <span>{{ currentUser?.nickname || currentUser?.username }}</span>
        </el-form-item>
        <el-form-item label="增加数量">
          <el-input-number v-model="creditForm.amount" :min="1" :step="10" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="creditForm.reason" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCredit">确认加算力</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.credit-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 18px;
}
</style>
