<script setup>
  import { computed, onMounted, ref } from 'vue'
  import { useRoute } from 'vue-router'
  import * as tasksApi from '../api/tasks'
  import {
    pickTaskResult,
    formatResultForDisplay,
    pickTaskMeta,
  } from '../utils/taskDisplay'
  import { TASK_STATUS_LABEL } from '../constants/taskStatus'
  import { requestErrorMessage } from '../utils/errors'

  const route = useRoute()
  const taskId = computed(() => String(route.params.taskId))

  const loading = ref(true)
  const errorMsg = ref('')
  const task = ref(null)

  const meta = computed(() => pickTaskMeta(task.value || {}))

  const statusLabel = computed(
    () => TASK_STATUS_LABEL[meta.value.status] || meta.value.status || '—',
  )

  const resultText = computed(() => {
    const r = pickTaskResult(task.value || {})
    return formatResultForDisplay(r)
  })

  onMounted(async () => {
    loading.value = true
    errorMsg.value = ''
    try {
      const data = await tasksApi.getTask(taskId.value)
      task.value = data
    } catch (e) {
      errorMsg.value = requestErrorMessage(e)
    } finally {
      loading.value = false
    }
  })
</script>

<template>
  <div class="page result-page">
    <h1 class="page-title">任务结果</h1>

    <p v-if="loading" class="state">加载中…</p>
    <p v-else-if="errorMsg" class="state error">{{ errorMsg }}</p>

    <template v-else-if="task">
      <dl class="meta-grid">
        <div>
          <dt>任务编号</dt>
          <dd class="mono">{{ meta.id }}</dd>
        </div>
        <div>
          <dt>工具名称</dt>
          <dd>{{ meta.toolName || '—' }}</dd>
        </div>
        <div>
          <dt>状态</dt>
          <dd>{{ statusLabel }}</dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>{{ meta.createdAt || '—' }}</dd>
        </div>
        <div>
          <dt>完成时间</dt>
          <dd>{{ meta.completedAt || '—' }}</dd>
        </div>
      </dl>

      <section class="output-section">
        <h2>输出内容</h2>
        <pre class="output">{{ resultText || '（无输出）' }}</pre>
      </section>
    </template>
  </div>
</template>

<style scoped>
.result-page {
  text-align: left;
  padding: 24px;
  max-width: 800px;
  margin: 0 auto;
}

.page-title {
  font-size: 26px;
  margin: 0 0 24px;
}

.state {
  text-align: center;
  padding: 40px;
  color: var(--text);
}

.state.error {
  color: #ef4444;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
  margin: 0 0 28px;
}

.meta-grid dt {
  font-size: 13px;
  color: var(--text);
  margin: 0 0 4px;
}

.meta-grid dd {
  margin: 0;
  font-size: 15px;
  color: var(--text-h);
}

.output-section h2 {
  font-size: 18px;
  margin: 0 0 12px;
}

.output {
  margin: 0;
  padding: 20px;
  border-radius: 12px;
  background: var(--code-bg);
  color: var(--text-h);
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  border: 1px solid var(--border);
}
</style>
