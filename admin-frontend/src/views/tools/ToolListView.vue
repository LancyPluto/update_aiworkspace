<script setup lang="ts">
import { Edit, Plus, Refresh, Setting, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchAdminTools, offlineTool, publishTool } from '@/api/tools'
import type { ToolSummary } from '@/types'

const router = useRouter()
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref<string>('')
const tools = ref<ToolSummary[]>([])

const filteredTools = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return tools.value.filter((tool) => {
    if (statusFilter.value && tool.status !== statusFilter.value) return false
    if (!value) return true
    return [tool.toolCode, tool.toolName, tool.categoryName, tool.description].some((item) =>
      String(item || '').toLowerCase().includes(value)
    )
  })
})

const stats = computed(() => {
  const total = tools.value.length
  const online = tools.value.filter((t) => t.status === 'ONLINE').length
  const draft = tools.value.filter((t) => t.status === 'DRAFT').length
  const offline = tools.value.filter((t) => t.status === 'OFFLINE').length
  return { total, online, draft, offline }
})

function statusType(status: string) {
  if (status === 'ONLINE') return 'success'
  if (status === 'OFFLINE') return 'info'
  return 'warning'
}

async function loadTools() {
  loading.value = true
  try {
    const response = await fetchAdminTools()
    tools.value = response.list
  } finally {
    loading.value = false
  }
}

async function changeStatus(row: ToolSummary, action: 'publish' | 'offline') {
  const title = action === 'publish' ? '确认发布工具？' : '确认下线工具？'
  const tip = action === 'publish'
    ? '发布前需配置好字段 Schema 与 ACTIVE Prompt 版本，否则后端会拒绝。'
    : '下线后用户端将不再看到该工具，已存在的任务不受影响。'
  await ElMessageBox.confirm(tip, title, { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' })
  try {
    if (action === 'publish') {
      await publishTool(row.id)
      ElMessage.success('工具已发布上线')
    } else {
      await offlineTool(row.id)
      ElMessage.success('工具已下线')
    }
    loadTools()
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : '操作失败'
    ElMessage.error(message)
  }
}

onMounted(loadTools)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">工具列表</h1>
        <p class="page-subtitle">创建、编辑、发布和下线 AI 工具，发布前请确保字段 Schema 与 Prompt 都为 ACTIVE。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="router.push('/tools/new')">新增工具</el-button>
    </div>

    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <el-card class="stat-card" shadow="never"><span class="stat-label">总数</span><span class="stat-value">{{ stats.total }}</span></el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="never"><span class="stat-label">已上线</span><span class="stat-value online">{{ stats.online }}</span></el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="never"><span class="stat-label">草稿</span><span class="stat-value draft">{{ stats.draft }}</span></el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="never"><span class="stat-label">已下线</span><span class="stat-value offline">{{ stats.offline }}</span></el-card>
      </el-col>
    </el-row>

    <el-card class="page-card" shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索工具名称、编码、分类或描述" style="width: 320px" />
        <el-select v-model="statusFilter" placeholder="按状态筛选" clearable style="width: 160px">
          <el-option label="DRAFT" value="DRAFT" />
          <el-option label="ONLINE" value="ONLINE" />
          <el-option label="OFFLINE" value="OFFLINE" />
        </el-select>
        <el-button :icon="Refresh" @click="loadTools">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="filteredTools" row-key="id">
        <el-table-column prop="toolCode" label="工具编码" min-width="160" />
        <el-table-column label="工具名称" min-width="200">
          <template #default="{ row }">
            <div class="tool-name">
              <el-avatar :size="32" shape="square" :src="row.coverUrl">
                {{ row.toolName?.slice(0, 1) }}
              </el-avatar>
              <span>{{ row.toolName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="140" />
        <el-table-column prop="estimatedCreditCost" label="预估算力" width="110" align="right" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" @click="router.push(`/tools/${row.id}/edit`)">编辑</el-button>
            <el-button size="small" :icon="Setting" @click="router.push(`/tools/${row.id}/fields`)">字段</el-button>
            <el-button size="small" :icon="VideoPlay" @click="router.push(`/tools/${row.id}/prompts`)">Prompt</el-button>
            <el-button v-if="row.status !== 'ONLINE'" size="small" type="success" @click="changeStatus(row, 'publish')">
              发布
            </el-button>
            <el-button v-else size="small" type="warning" @click="changeStatus(row, 'offline')">下线</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<style scoped>
.tool-name {
  display: flex;
  gap: 10px;
  align-items: center;
  font-weight: 600;
}

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

.stat-value.online {
  color: #16a34a;
}

.stat-value.draft {
  color: #d97706;
}

.stat-value.offline {
  color: #6b7280;
}
</style>
