<script setup lang="ts">
import { Refresh, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { cancelAdminTask, fetchAdminTasks, retryAdminTask } from '@/api/tasks'
import type { AdminTaskRow } from '@/types'

const router = useRouter()
const loading = ref(false)
const tasks = ref<AdminTaskRow[]>([])

const filters = reactive<{ status: string; toolCode: string; userId: string }>({
  status: '',
  toolCode: '',
  userId: ''
})

function statusType(status: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PROCESSING') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'primary'
}

async function loadTasks() {
  loading.value = true
  try {
    const userIdNum = filters.userId ? Number(filters.userId) : undefined
    const response = await fetchAdminTasks({
      status: filters.status || undefined,
      toolCode: filters.toolCode || undefined,
      userId: Number.isFinite(userIdNum) ? userIdNum : undefined
    })
    tasks.value = response.list
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filters.status = ''
  filters.toolCode = ''
  filters.userId = ''
  loadTasks()
}

async function retry(row: AdminTaskRow) {
  await ElMessageBox.confirm(`将任务 ${row.taskNo} 重新置为 QUEUED，由 Worker 重新执行。`, '重试任务', {
    type: 'warning'
  })
  await retryAdminTask(row.taskId)
  ElMessage.success('已重新入队')
  loadTasks()
}

async function cancel(row: AdminTaskRow) {
  await ElMessageBox.confirm(`将任务 ${row.taskNo} 置为 CANCELLED，已扣算力暂不退还。`, '取消任务', {
    type: 'warning'
  })
  await cancelAdminTask(row.taskId)
  ElMessage.success('任务已取消')
  loadTasks()
}

onMounted(loadTasks)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">任务列表</h1>
        <p class="page-subtitle">查看全部用户的任务，支持按状态/工具/用户筛选，可重试与取消。</p>
      </div>
    </div>

    <el-card class="page-card" shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 160px">
          <el-option label="QUEUED" value="QUEUED" />
          <el-option label="PROCESSING" value="PROCESSING" />
          <el-option label="SUCCESS" value="SUCCESS" />
          <el-option label="FAILED" value="FAILED" />
          <el-option label="CANCELLED" value="CANCELLED" />
        </el-select>
        <el-input v-model="filters.toolCode" clearable placeholder="工具编码" style="width: 200px" />
        <el-input v-model="filters.userId" clearable placeholder="用户 ID（数字）" style="width: 180px" />
        <el-button type="primary" @click="loadTasks">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
        <el-button :icon="Refresh" @click="loadTasks">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="tasks" row-key="taskId">
        <el-table-column prop="taskId" label="taskId" width="90" />
        <el-table-column prop="taskNo" label="任务号" min-width="180" show-overflow-tooltip />
        <el-table-column label="用户" width="160">
          <template #default="{ row }">
            <span>{{ row.userNickname || `#${row.userId ?? '-'}` }}</span>
            <span v-if="row.userId" class="muted">（{{ row.userId }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="toolCode" label="工具编码" min-width="160" />
        <el-table-column prop="toolName" label="工具" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="160">
          <template #default="{ row }">
            <el-progress :percentage="row.progress || 0" :stroke-width="8" />
          </template>
        </el-table-column>
        <el-table-column label="消耗算力" width="100" align="right">
          <template #default="{ row }">
            <span>{{ row.consumedCredits ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column prop="finishedAt" label="完成时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :icon="View" @click="router.push(`/tasks/${row.taskId}`)">详情</el-button>
            <el-button v-if="row.status === 'FAILED'" size="small" @click="retry(row)">重试</el-button>
            <el-button v-if="['QUEUED','PROCESSING'].includes(row.status)" size="small" type="warning" @click="cancel(row)">
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<style scoped>
.muted {
  color: #6b7280;
  font-size: 12px;
}
</style>
