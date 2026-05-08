<script setup lang="ts">
import { View } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchTasks } from '@/api/tasks'
import type { AdminTask } from '@/types'

const router = useRouter()
const loading = ref(false)
const keyword = ref('')
const tasks = ref<AdminTask[]>([])

const filteredTasks = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return tasks.value
  return tasks.value.filter((task) =>
    [task.taskNo, task.toolCode, task.toolName, task.status, task.progressMessage].some((item) =>
      String(item || '').toLowerCase().includes(value)
    )
  )
})

function statusType(status: string) {
  if (status === 'SUCCESS' || status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

async function loadTasks() {
  loading.value = true
  try {
    const response = await fetchTasks()
    tasks.value = response.list
  } catch {
    tasks.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadTasks)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">任务列表</h1>
        <p class="page-subtitle">查看当前登录账号可见的 AI 任务、状态、进度和生成结果。</p>
      </div>
    </div>

    <el-alert
      title="当前后端提供的是 /api/v1/tasks（按登录用户查询）；文档里的后台全量任务 /api/admin/v1/tasks 尚未提供。"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <el-card class="page-card" shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索任务号、工具编码、工具名称或状态" style="width: 360px" />
        <el-button @click="loadTasks">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="filteredTasks" row-key="taskId">
        <el-table-column prop="taskId" label="taskId" width="90" />
        <el-table-column prop="taskNo" label="任务号" min-width="160" />
        <el-table-column prop="toolCode" label="工具编码" min-width="150" />
        <el-table-column prop="toolName" label="工具名称" min-width="160" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress :percentage="row.progress || 0" :stroke-width="8" />
          </template>
        </el-table-column>
        <el-table-column prop="progressMessage" label="进度说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column prop="finishedAt" label="完成时间" width="180" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :icon="View" @click="router.push(`/tasks/${row.taskId}`)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>
