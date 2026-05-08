<script setup lang="ts">
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { cancelAdminTask, fetchAdminTaskDetail, retryAdminTask } from '@/api/tasks'
import type { AdminTaskDetail } from '@/types'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref<AdminTaskDetail | null>(null)

const paramsText = computed(() => JSON.stringify(detail.value?.params || {}, null, 2))
const resultText = computed(() => {
  if (!detail.value?.result) return ''
  return detail.value.result.contentText || JSON.stringify(detail.value.result, null, 2)
})

function statusType(status: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PROCESSING') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'primary'
}

function logType(eventType: string) {
  if (eventType.includes('FAIL')) return 'danger'
  if (eventType.includes('SUCCESS')) return 'success'
  if (eventType.includes('CANCEL')) return 'info'
  if (eventType.includes('RETRY')) return 'warning'
  return 'primary'
}

function creditTagType(logType: string) {
  if (logType === 'DEDUCT') return 'danger'
  if (logType === 'MANUAL_ADD') return 'success'
  if (logType === 'RELEASE') return 'success'
  return 'info'
}

async function loadDetail() {
  loading.value = true
  try {
    detail.value = await fetchAdminTaskDetail(Number(route.params.taskId))
  } finally {
    loading.value = false
  }
}

async function retry() {
  if (!detail.value) return
  await ElMessageBox.confirm('将该任务置为 QUEUED，由 Worker 重新执行。', '重试任务', { type: 'warning' })
  await retryAdminTask(detail.value.taskId)
  ElMessage.success('已重新入队')
  await loadDetail()
}

async function cancel() {
  if (!detail.value) return
  await ElMessageBox.confirm('将该任务置为 CANCELLED，已扣算力暂不退还。', '取消任务', { type: 'warning' })
  await cancelAdminTask(detail.value.taskId)
  ElMessage.success('任务已取消')
  await loadDetail()
}

onMounted(loadDetail)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">任务详情</h1>
        <p class="page-subtitle">展示任务全部信息：基础参数 / 生成结果 / 任务日志 / 算力流水。</p>
      </div>
      <div>
        <el-button :icon="Refresh" @click="loadDetail">刷新</el-button>
        <el-button :icon="ArrowLeft" @click="router.push('/tasks')">返回列表</el-button>
      </div>
    </div>

    <el-card v-loading="loading" class="page-card" shadow="never">
      <el-empty v-if="!detail" description="暂无任务详情" />
      <template v-else>
        <el-descriptions :column="3" border title="基础信息">
          <el-descriptions-item label="任务号">{{ detail.taskNo }}</el-descriptions-item>
          <el-descriptions-item label="任务 ID">{{ detail.taskId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)">{{ detail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="工具编码">{{ detail.toolCode }}</el-descriptions-item>
          <el-descriptions-item label="工具">{{ detail.toolName }}</el-descriptions-item>
          <el-descriptions-item label="用户">
            <span>{{ detail.userNickname || '-' }}</span>
            <span v-if="detail.userId" class="muted">（{{ detail.userId }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="进度">{{ detail.progress }}% / {{ detail.progressMessage || '-' }}</el-descriptions-item>
          <el-descriptions-item label="消耗算力">{{ detail.consumedCredits ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ detail.finishedAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="错误码">
            <span v-if="detail.errorCode" class="error-code">{{ detail.errorCode }}</span>
            <span v-else class="muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="错误信息" :span="2">
            <span v-if="detail.errorMessage" class="error-message">{{ detail.errorMessage }}</span>
            <span v-else class="muted">-</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="actions">
          <el-button v-if="detail.status === 'FAILED'" type="primary" @click="retry">重试任务</el-button>
          <el-button v-if="['QUEUED','PROCESSING'].includes(detail.status)" type="warning" @click="cancel">
            取消任务
          </el-button>
        </div>

        <el-divider />
        <el-row :gutter="16">
          <el-col :span="12">
            <h3>用户输入参数</h3>
            <el-input :model-value="paramsText" type="textarea" :rows="10" readonly />
          </el-col>
          <el-col :span="12">
            <h3>生成结果</h3>
            <el-input
              :model-value="resultText || (detail.status === 'SUCCESS' ? '' : '任务尚未生成结果')"
              type="textarea"
              :rows="10"
              readonly
            />
          </el-col>
        </el-row>

        <el-divider />
        <el-row :gutter="16">
          <el-col :span="14">
            <h3>任务日志（ai_task_logs）</h3>
            <el-empty v-if="!detail.logs || detail.logs.length === 0" description="暂无任务日志" />
            <el-timeline v-else>
              <el-timeline-item
                v-for="log in detail.logs"
                :key="log.id"
                :timestamp="log.createdAt"
                :type="logType(log.eventType)"
              >
                <strong>{{ log.eventType }}</strong>
                <span class="muted">（{{ log.fromStatus || '-' }} → {{ log.toStatus || '-' }}）</span>
                <p style="margin: 4px 0 0">{{ log.message || '' }}</p>
              </el-timeline-item>
            </el-timeline>
          </el-col>
          <el-col :span="10">
            <h3>算力流水（credit_logs）</h3>
            <el-empty v-if="!detail.creditLogs || detail.creditLogs.length === 0" description="暂无算力流水" />
            <el-table v-else :data="detail.creditLogs" size="small">
              <el-table-column label="类型" width="120">
                <template #default="{ row }">
                  <el-tag :type="creditTagType(row.logType)" size="small">{{ row.logType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="amount" label="数量" width="80" align="right" />
              <el-table-column label="余额变化" width="160">
                <template #default="{ row }">
                  <span class="muted">{{ row.balanceBefore }} → {{ row.balanceAfter }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="备注" min-width="160" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="时间" width="180" />
            </el-table>
          </el-col>
        </el-row>
      </template>
    </el-card>
  </section>
</template>

<style scoped>
.muted {
  color: #6b7280;
  font-size: 12px;
}

.error-code {
  color: #b91c1c;
  font-weight: 600;
}

.error-message {
  display: block;
  color: #b91c1c;
  white-space: pre-wrap;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}
</style>
