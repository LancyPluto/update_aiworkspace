<script setup lang="ts">
import { Coin, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { fetchUsers, manualAddCredits } from '@/api/users'
import type { AdminMember } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const keyword = ref('')
const filterType = ref<string>('')
const users = ref<AdminMember[]>([])
const target = ref<AdminMember | null>(null)

const creditForm = reactive({
  amount: 100,
  reason: '运营手动加算力'
})

const filtered = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return users.value.filter((user) => {
    if (filterType.value && user.userType !== filterType.value) return false
    if (!value) return true
    return [user.username, user.nickname, user.userType, user.status].some((v) =>
      String(v || '').toLowerCase().includes(value)
    )
  })
})

const stats = computed(() => {
  const total = users.value.length
  const admin = users.value.filter((u) => u.userType === 'ADMIN').length
  const user = users.value.filter((u) => u.userType === 'USER').length
  const totalCredits = users.value.reduce((sum, u) => sum + (u.credits || 0), 0)
  return { total, admin, user, totalCredits }
})

async function loadUsers() {
  loading.value = true
  try {
    const response = await fetchUsers()
    users.value = response.list
  } finally {
    loading.value = false
  }
}

function openCreditDialog(row: AdminMember) {
  target.value = row
  creditForm.amount = 100
  creditForm.reason = '运营手动加算力'
  dialogVisible.value = true
}

async function submitCredit() {
  if (!target.value) return
  if (!creditForm.amount || creditForm.amount <= 0) {
    ElMessage.warning('加算力数量必须大于 0')
    return
  }
  submitting.value = true
  try {
    const result = await manualAddCredits(target.value.id, {
      amount: creditForm.amount,
      reason: creditForm.reason || '运营手动加算力'
    })
    ElMessage.success(`已为 ${target.value.nickname || target.value.username} 增加 ${result.amount} 算力（${result.balanceBefore} → ${result.balanceAfter}）`)
    dialogVisible.value = false
    await loadUsers()
  } finally {
    submitting.value = false
  }
}

function statusType(status: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DISABLED') return 'danger'
  return 'info'
}

function userTypeTag(userType: string) {
  return userType === 'ADMIN' ? 'warning' : 'primary'
}

onMounted(loadUsers)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">用户与算力</h1>
        <p class="page-subtitle">查看全部用户、当前算力余额，并支持运营手动加算力。</p>
      </div>
    </div>

    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <el-card class="stat-card" shadow="never"><span class="stat-label">用户总数</span><span class="stat-value">{{ stats.total }}</span></el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="never"><span class="stat-label">管理员</span><span class="stat-value warn">{{ stats.admin }}</span></el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="never"><span class="stat-label">普通用户</span><span class="stat-value">{{ stats.user }}</span></el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="never"><span class="stat-label">算力余额合计</span><span class="stat-value success">{{ stats.totalCredits }}</span></el-card>
      </el-col>
    </el-row>

    <el-card v-loading="loading" class="page-card" shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索用户名 / 昵称 / 状态" style="width: 280px" />
        <el-select v-model="filterType" placeholder="按角色筛选" clearable style="width: 160px">
          <el-option label="ADMIN" value="ADMIN" />
          <el-option label="USER" value="USER" />
        </el-select>
        <el-button :icon="Refresh" @click="loadUsers">刷新</el-button>
      </div>

      <el-table :data="filtered" row-key="id">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" min-width="160" />
        <el-table-column prop="nickname" label="昵称" min-width="160" />
        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="userTypeTag(row.userType)" size="small">{{ row.userType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="credits" label="算力余额" width="120" align="right">
          <template #default="{ row }">
            <strong>{{ row.credits ?? 0 }}</strong>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="200" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" :icon="Coin" size="small" @click="openCreditDialog(row)">
              加算力
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="手动加算力" width="480px">
      <el-form label-width="100px">
        <el-form-item label="目标用户">
          <span>{{ target?.nickname || target?.username }}（ID: {{ target?.id }}）</span>
        </el-form-item>
        <el-form-item label="当前余额">
          <strong>{{ target?.credits ?? 0 }}</strong>
        </el-form-item>
        <el-form-item label="增加数量" required>
          <el-input-number v-model="creditForm.amount" :min="1" :step="50" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="creditForm.reason" type="textarea" :rows="3" placeholder="将记入 credit_logs.reason" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCredit">确认加算力</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border: 0;
  border-radius: 12px;
}

.stat-label {
  color: #6b7280;
  font-size: 13px;
}

.stat-value {
  color: #111827;
  font-size: 24px;
  font-weight: 700;
}

.stat-value.warn {
  color: #d97706;
}

.stat-value.success {
  color: #16a34a;
}
</style>
