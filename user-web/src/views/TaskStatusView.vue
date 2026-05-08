<script setup>
import { onMounted, onUnmounted, ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as tasksApi from '../api/tasks'
import {
  TASK_STATUS_LABEL,
  TERMINAL_STATUSES,
  pollIntervalMs,
} from '../constants/taskStatus'
import {
  pickTaskResult,
  formatResultForDisplay,
  pickFailureReason,
} from '../utils/taskDisplay'
import { requestErrorMessage } from '../utils/errors'

const route = useRoute()
const router = useRouter()

const taskId = computed(() => String(route.params.taskId))

const loading = ref(true)
const errorMsg = ref('')
const statusPayload = ref(null)
const taskDetail = ref(null)

let pollTimer = null

const status = computed(
  () => String(statusPayload.value?.status ?? taskDetail.value?.status ?? ''),
)

const statusLabel = computed(
  () => TASK_STATUS_LABEL[status.value] || status.value || '—',
)

const progress = computed(() => {
  const p = statusPayload.value?.progress ?? taskDetail.value?.progress
  if (p == null) return null
  const n = Number(p)
  if (Number.isNaN(n)) return null
  if (n > 1) return Math.min(n, 100)
  return n * 100
})

const progressMessage = computed(
  () =>
    statusPayload.value?.progressMessage ??
    taskDetail.value?.progressMessage ??
    '',
)

const failureFriendly = computed(() => {
  if (status.value !== 'FAILED') return ''
  return (
    pickFailureReason(taskDetail.value || {}) ||
    '生成失败，请稍后重试或联系管理员'
  )
})

const resultText = computed(() => {
  if (status.value !== 'SUCCESS') return ''
  const r = pickTaskResult(taskDetail.value || {})
  return formatResultForDisplay(r)
})

const isTerminal = computed(() => TERMINAL_STATUSES.has(status.value))

function clearPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function fetchBoth() {
  const id = taskId.value
  const [st, detail] = await Promise.all([
    tasksApi.getTaskStatus(id).catch(() => null),
    tasksApi.getTask(id).catch(() => null),
  ])
  if (st && typeof st === 'object') statusPayload.value = st
  if (detail && typeof detail === 'object') taskDetail.value = detail
}

function schedulePoll() {
  clearPoll()
  const s =
    statusPayload.value?.status ??
    taskDetail.value?.status ??
    ''
  const ms = pollIntervalMs(s)
  if (ms == null || TERMINAL_STATUSES.has(s)) return
  pollTimer = window.setTimeout(async () => {
    try {
      await fetchBoth()
      schedulePoll()
    } catch {
      schedulePoll()
    }
  }, ms)
}

onMounted(async () => {
  loading.value = true
  errorMsg.value = ''
  try {
    await fetchBoth()
    if (!statusPayload.value && !taskDetail.value) {
      errorMsg.value = '无法加载任务状态'
    }
    schedulePoll()
  } catch (e) {
    errorMsg.value = requestErrorMessage(e)
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  clearPoll()
})

watch(taskId, async () => {
  loading.value = true
  statusPayload.value = null
  taskDetail.value = null
  errorMsg.value = ''
  clearPoll()
  try {
    await fetchBoth()
    schedulePoll()
  } catch (e) {
    errorMsg.value = requestErrorMessage(e)
  } finally {
    loading.value = false
  }
})

watch(isTerminal, (done) => {
  if (done) clearPoll()
})

function goResultPage() {
  router.push({
    name: 'task-result',
    params: { taskId: taskId.value },
  })
}
</script>

<template>
  <div class="page task-status-page">
    <h1 class="page-title">任务状态</h1>
    <p class="mono task-id">任务编号：{{ taskId }}</p>

    <p v-if="loading" class="state">加载中…</p>
    <p v-else-if="errorMsg" class="state error">{{ errorMsg }}</p>

    <template v-else>
      <div class="status-card">
        <div class="row">
          <span class="label">当前状态</span>
          <span class="value strong">{{ statusLabel }}</span>
        </div>
        <div v-if="progress != null" class="row">
          <span class="label">进度</span>
          <span class="value">{{ Math.round(progress) }}%</span>
        </div>
        <div v-if="progressMessage" class="row block">
          <span class="label">说明</span>
          <span class="value">{{ progressMessage }}</span>
        </div>
      </div>

      <p v-if="status === 'FAILED'" class="banner fail">{{ failureFriendly }}</p>

      <section v-if="status === 'SUCCESS' && resultText" class="result-preview">
        <h2>生成结果</h2>
        <pre class="result-body">{{ resultText }}</pre>
        <button type="button" class="btn-secondary" @click="goResultPage">
          查看完整结果页
        </button>
      </section>

      <p v-if="!isTerminal" class="poll-hint">正在自动刷新状态…</p>
    </template>
  </div>
</template>

<style scoped>
.task-status-page {
  text-align: left;
  padding: 24px;
  max-width: 720px;
  margin: 0 auto;
}

.page-title {
  font-size: 26px;
  margin: 0 0 8px;
}

.task-id {
  margin: 0 0 24px;
  font-size: 14px;
  color: var(--text);
}

.state {
  padding: 24px;
  text-align: center;
  color: var(--text);
}

.state.error {
  color: #ef4444;
}

.status-card {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
}

.row {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  align-items: baseline;
}

.row:last-child {
  margin-bottom: 0;
}

.row.block {
  flex-direction: column;
  gap: 6px;
}

.label {
  color: var(--text);
  min-width: 88px;
  font-size: 14px;
}

.value {
  color: var(--text-h);
  font-size: 15px;
}

.value.strong {
  font-weight: 600;
  color: var(--accent);
}

.banner {
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 14px;
  margin-bottom: 16px;
}

.banner.fail {
  background: rgba(239, 68, 68, 0.1);
  color: #b91c1c;
}

.result-preview {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
  margin-top: 8px;
}

.result-preview h2 {
  font-size: 18px;
  margin: 0 0 12px;
}

.result-body {
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
  padding: 16px;
  border-radius: 8px;
  background: var(--code-bg);
  color: var(--text-h);
  font-size: 14px;
  line-height: 1.5;
  max-height: 360px;
  overflow: auto;
  margin: 0 0 16px;
}

.btn-secondary {
  padding: 10px 16px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--bg);
  color: var(--text-h);
  font: inherit;
  cursor: pointer;
}

.btn-secondary:hover {
  border-color: var(--accent-border);
}

.poll-hint {
  margin-top: 16px;
  font-size: 14px;
  color: var(--text);
}
</style>
