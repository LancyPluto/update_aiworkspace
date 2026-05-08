<script setup>
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import * as tasksApi from '../api/tasks'
import { TASK_STATUS_LABEL } from '../constants/taskStatus'
import { pickTaskMeta } from '../utils/taskDisplay'
import { requestErrorMessage } from '../utils/errors'

const loading = ref(true)
const errorMsg = ref('')
const tasks = ref([])

onMounted(async () => {
  loading.value = true
  errorMsg.value = ''
  try {
    const list = await tasksApi.listTasks()
    tasks.value = Array.isArray(list) ? list : []
  } catch (e) {
    errorMsg.value = requestErrorMessage(e)
  } finally {
    loading.value = false
  }
})

function labelFor(status) {
  return TASK_STATUS_LABEL[status] || status || '—'
}

function rowKey(t, i) {
  return String(t.taskId ?? t.id ?? i)
}

function detailLink(t) {
  const id = t.taskId ?? t.id
  return `/tasks/${encodeURIComponent(String(id))}`
}
</script>

<template>
  <div class="page my-tasks-page">
    <div class="page-head">
      <h1 class="page-title">我的任务</h1>
      <p class="page-desc">查看历史任务与状态</p>
    </div>

    <p v-if="loading" class="state">加载中…</p>
    <p v-else-if="errorMsg" class="state error">{{ errorMsg }}</p>
    <div v-else-if="!tasks.length" class="empty">暂无任务</div>

    <div v-else class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>任务编号</th>
            <th>工具名称</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>完成时间</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(t, i) in tasks" :key="rowKey(t, i)">
            <td class="mono">{{ pickTaskMeta(t).id }}</td>
            <td>{{ pickTaskMeta(t).toolName || '—' }}</td>
            <td>{{ labelFor(pickTaskMeta(t).status) }}</td>
            <td>{{ pickTaskMeta(t).createdAt || '—' }}</td>
            <td>{{ pickTaskMeta(t).completedAt || '—' }}</td>
            <td>
              <RouterLink class="link" :to="detailLink(t)">进入详情</RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.my-tasks-page {
  text-align: left;
  padding: 24px;
  max-width: 1000px;
  margin: 0 auto;
}

.page-head {
  margin-bottom: 24px;
}

.page-title {
  font-size: 28px;
  margin: 0 0 8px;
}

.page-desc {
  margin: 0;
  color: var(--text);
}

.state {
  text-align: center;
  padding: 40px;
}

.state.error {
  color: #ef4444;
}

.empty {
  text-align: center;
  padding: 48px;
  border: 1px dashed var(--border);
  border-radius: 12px;
  color: var(--text);
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: 12px;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.table th,
.table td {
  padding: 12px 14px;
  text-align: left;
  border-bottom: 1px solid var(--border);
}

.table th {
  background: var(--social-bg);
  color: var(--text-h);
  font-weight: 500;
}

.table tr:last-child td {
  border-bottom: none;
}

.mono {
  font-family: var(--mono);
  font-size: 13px;
}

.link {
  color: var(--accent);
  text-decoration: none;
  white-space: nowrap;
}

.link:hover {
  text-decoration: underline;
}
</style>
