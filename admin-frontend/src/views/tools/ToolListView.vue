<script setup lang="ts">
import { Edit, Plus, Setting, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchAdminTools, offlineTool, publishTool } from '@/api/tools'
import type { ToolSummary } from '@/types'

const router = useRouter()
const loading = ref(false)
const keyword = ref('')
const tools = ref<ToolSummary[]>([])

const filteredTools = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return tools.value
  return tools.value.filter((tool) =>
    [tool.toolCode, tool.toolName, tool.categoryName, tool.status].some((item) =>
      String(item || '').toLowerCase().includes(value)
    )
  )
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
  await ElMessageBox.confirm(title, '状态变更', { type: 'warning' })
  if (action === 'publish') {
    await publishTool(row.id)
    ElMessage.success('工具已发布')
  } else {
    await offlineTool(row.id)
    ElMessage.success('工具已下线')
  }
  loadTools()
}

onMounted(loadTools)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">工具列表</h1>
        <p class="page-subtitle">创建、编辑、发布和下线 AI 工具。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="router.push('/tools/new')">新增工具</el-button>
    </div>

    <el-card class="page-card" shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索工具名称、编码、分类或状态" style="width: 320px" />
        <el-button @click="loadTools">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="filteredTools" row-key="id">
        <el-table-column prop="toolCode" label="工具编码" min-width="140" />
        <el-table-column label="工具名称" min-width="180">
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
        <el-table-column prop="estimatedCreditCost" label="预估算力" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="360" fixed="right">
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
</style>
