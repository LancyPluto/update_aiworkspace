<script setup lang="ts">
import { Coin } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'

import { fetchUsers, manualAddCredits } from '@/api/users'
import type { AdminMember } from '@/types'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const dialogVisible = ref(false)
const keyword = ref('')
const users = ref<AdminMember[]>([])
const selectedUser = ref<AdminMember | null>(null)
const creditForm = reactive({
  amount: 100,
  reason: '运营手动加算力'
})

const filteredUsers = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return users.value
  return users.value.filter((user) =>
    [user.username, user.nickname, user.userType, user.status].some((item) =>
      String(item || '').toLowerCase().includes(value)
    )
  )
})

async function loadUsers() {
  loading.value = true
  try {
    const response = await fetchUsers()
    users.value = response.list
  } catch {
    users.value = []
  } finally {
    loading.value = false
  }
}

function openCreditDialog(user: AdminMember) {
  selectedUser.value = user
  creditForm.amount = 100
  creditForm.reason = '运营手动加算力'
  dialogVisible.value = true
}

async function submitCredit() {
  if (!selectedUser.value) return
  await manualAddCredits(selectedUser.value.id, {
    amount: creditForm.amount,
    reason: creditForm.reason
  })
  ElMessage.success('算力已增加')
  dialogVisible.value = false
  loadUsers()
}

onMounted(loadUsers)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">用户与算力</h1>
        <p class="page-subtitle">查看用户列表，并通过弹窗进行手动加算力。</p>
      </div>
    </div>

    <el-alert
      title="后端当前未提供后台用户接口时，这里会显示空态或 404 提示；接口路径已按文档对接。"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <el-card class="page-card" shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索用户名、昵称、角色或状态" style="width: 320px" />
        <el-button @click="loadUsers">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="filteredUsers" row-key="id">
        <el-table-column prop="id" label="用户 ID" width="100" />
        <el-table-column prop="username" label="用户名" min-width="160" />
        <el-table-column prop="nickname" label="昵称" min-width="160" />
        <el-table-column prop="userType" label="角色" width="120" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="credits" label="当前算力" width="120" />
        <el-table-column prop="createdAt" label="注册时间" width="180" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :icon="Coin" @click="openCreditDialog(row)">手动加算力</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="手动加算力" width="460px">
      <el-form label-width="90px">
        <el-form-item label="用户">
          <span>{{ selectedUser?.nickname || selectedUser?.username }}</span>
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
