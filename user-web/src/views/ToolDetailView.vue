<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as toolsApi from '../api/tools'
import * as tasksApi from '../api/tasks'
import { normalizeToolForForm } from '../utils/toolSchema'
import { requestErrorMessage, isInsufficientCredits } from '../utils/errors'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const submitting = ref(false)
const errorMsg = ref('')
const shelfWarning = ref('')
const rawTool = ref(null)
const formValues = reactive({})

const normalized = computed(() => {
  if (!rawTool.value) return null
  return normalizeToolForForm(rawTool.value)
})

const toolCode = computed(() => route.params.toolCode)

onMounted(async () => {
  await loadTool()
})

async function loadTool() {
  loading.value = true
  errorMsg.value = ''
  shelfWarning.value = ''
  try {
    const data = await toolsApi.getTool(String(toolCode.value))
    rawTool.value = data
    const n = normalizeToolForForm(data)
    Object.keys(formValues).forEach((k) => delete formValues[k])
    for (const f of n.fields) {
      formValues[f.key] = ''
    }
    const inactive =
      n.status === 'OFFLINE' ||
      n.status === 'OFF_SHELF' ||
      n.status === 'DISABLED' ||
      n.status === 'INACTIVE'
    if (inactive) {
      shelfWarning.value = '该工具已下架或暂不可用'
    }
  } catch (e) {
    errorMsg.value = requestErrorMessage(e)
    rawTool.value = null
  } finally {
    loading.value = false
  }
}

function validate() {
  const n = normalized.value
  if (!n) return '工具信息缺失'
  for (const f of n.fields) {
    const v = formValues[f.key]
    if (f.required) {
      if (v === '' || v === null || v === undefined) {
        return `请填写「${f.label}」`
      }
    }
    if (f.type === 'number' && v !== '' && v != null) {
      if (Number.isNaN(Number(v))) return `「${f.label}」需为有效数字`
    }
  }
  return ''
}

async function submitTask() {
  const msg = validate()
  if (msg) {
    errorMsg.value = msg
    return
  }
  const n = normalized.value
  if (!n || shelfWarning.value) return

  submitting.value = true
  errorMsg.value = ''
  try {
    const params = {}
    for (const f of n.fields) {
      let v = formValues[f.key]
      if (f.type === 'number') {
        const n = v === '' || v == null ? NaN : Number(v)
        params[f.key] = Number.isNaN(n) ? null : n
      } else {
        params[f.key] = v
      }
    }
    const clientRequestId = crypto.randomUUID()
    const res = await tasksApi.createTask({
      toolCode: n.toolCode,
      params,
      clientRequestId,
    })
    const taskId =
      res.taskId ?? res.id ?? res.data?.taskId ?? res.data?.id
    if (!taskId) {
      throw new Error('创建任务成功但未返回任务编号')
    }
    router.push({
      name: 'task-status',
      params: { taskId: String(taskId) },
    })
  } catch (e) {
    if (isInsufficientCredits(e)) {
      errorMsg.value = '算力不足，请充值或稍后再试'
    } else {
      errorMsg.value = requestErrorMessage(e)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page detail-page">
    <p v-if="loading" class="state">加载中…</p>
    <template v-else-if="normalized">
      <div class="hero">
        <div class="cover-side">
          <img
            v-if="normalized.coverUrl"
            :src="normalized.coverUrl"
            :alt="normalized.toolName"
            class="cover"
          />
          <div v-else class="cover placeholder" />
        </div>
        <div class="info">
          <p v-if="normalized.categoryName" class="cat">{{
            normalized.categoryName
          }}</p>
          <h1 class="title">{{ normalized.toolName }}</h1>
          <p class="desc">{{ normalized.description }}</p>
          <p v-if="normalized.estimatedCreditCost != null" class="credit-line">
            预估消耗约 <strong>{{ normalized.estimatedCreditCost }}</strong> 算力
          </p>
        </div>
      </div>

      <p v-if="shelfWarning" class="banner warn">{{ shelfWarning }}</p>

      <section class="panel">
        <h2 class="panel-title">填写参数</h2>
        <form class="dyn-form" @submit.prevent="submitTask">
          <label
            v-for="f in normalized.fields"
            :key="f.key"
            class="field"
          >
            <span>{{ f.label }}{{ f.required ? ' *' : '' }}</span>
            <input
              v-if="f.type === 'text'"
              v-model="formValues[f.key]"
              type="text"
              :placeholder="f.placeholder"
              :required="f.required"
            />
            <textarea
              v-else-if="f.type === 'textarea'"
              v-model="formValues[f.key]"
              rows="4"
              :placeholder="f.placeholder"
              :required="f.required"
            />
            <input
              v-else-if="f.type === 'number'"
              v-model="formValues[f.key]"
              type="number"
              :placeholder="f.placeholder"
              :min="f.min"
              :max="f.max"
              :step="f.step"
              :required="f.required"
            />
            <select
              v-else-if="f.type === 'select'"
              v-model="formValues[f.key]"
              :required="f.required"
            >
              <option disabled value="">请选择</option>
              <option
                v-for="opt in f.options"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </option>
            </select>
          </label>

          <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

          <button
            type="submit"
            class="btn-primary"
            :disabled="submitting || !!shelfWarning"
          >
            {{ submitting ? '提交中…' : '创建任务' }}
          </button>
        </form>
      </section>
    </template>
    <p v-else class="state error">{{ errorMsg || '未找到工具' }}</p>
  </div>
</template>

<style scoped>
.detail-page {
  text-align: left;
  padding: 24px;
  max-width: 720px;
  margin: 0 auto;
}

.state {
  text-align: center;
  padding: 40px;
}

.state.error {
  color: #ef4444;
}

.hero {
  display: grid;
  grid-template-columns: 160px 1fr;
  gap: 24px;
  margin-bottom: 24px;
}

@media (max-width: 600px) {
  .hero {
    grid-template-columns: 1fr;
  }
}

.cover-side {
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--border);
}

.cover {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  display: block;
}

.cover.placeholder {
  aspect-ratio: 1;
  background: linear-gradient(
    135deg,
    var(--accent-bg),
    var(--code-bg)
  );
}

.cat {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--accent);
}

.title {
  font-size: 26px;
  margin: 0 0 12px;
}

.desc {
  margin: 0;
  color: var(--text);
  line-height: 1.5;
}

.credit-line {
  margin: 16px 0 0;
  font-size: 14px;
}

.banner {
  padding: 12px 16px;
  border-radius: 8px;
  margin-bottom: 20px;
  font-size: 14px;
}

.banner.warn {
  background: rgba(239, 68, 68, 0.12);
  color: #b91c1c;
}

.panel {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 24px;
}

.panel-title {
  font-size: 18px;
  margin: 0 0 20px;
}

.dyn-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  color: var(--text-h);
}

.field input,
.field textarea,
.field select {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg);
  color: var(--text-h);
  font: inherit;
}

.error {
  color: #ef4444;
  margin: 0;
  font-size: 14px;
}

.btn-primary {
  align-self: flex-start;
  padding: 12px 24px;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  font: inherit;
  font-weight: 500;
  cursor: pointer;
  margin-top: 8px;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
