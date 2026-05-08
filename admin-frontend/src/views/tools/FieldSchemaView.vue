<script setup lang="ts">
import { ArrowLeft, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { createFieldSchema, fetchFieldSchemas, publishFieldSchema } from '@/api/fieldSchemas'
import type { FieldItem, FieldSchema } from '@/types'

const route = useRoute()
const router = useRouter()
const toolId = Number(route.params.toolId)
const loading = ref(false)
const submitting = ref(false)
const schemas = ref<FieldSchema[]>([])

const draft = reactive<{ schemaVersion: string; fields: FieldItem[] }>({
  schemaVersion: 'v1',
  fields: [createDefaultRow(1)]
})

const activeSchema = computed(() => schemas.value.find((s) => s.status === 'ACTIVE') || null)

function createDefaultRow(sortOrder: number): FieldItem {
  return {
    fieldKey: '',
    fieldName: '',
    fieldType: 'text',
    placeholder: '',
    required: true,
    optionsJson: '',
    sortOrder
  }
}

function addField() {
  draft.fields.push(createDefaultRow(draft.fields.length + 1))
}

function removeField(index: number) {
  draft.fields.splice(index, 1)
}

function importFromActive() {
  if (!activeSchema.value) {
    ElMessage.info('当前没有 ACTIVE 字段 Schema 可复制')
    return
  }
  draft.schemaVersion = nextVersion(activeSchema.value.schemaVersion)
  draft.fields = activeSchema.value.fields.map((f, idx) => ({
    fieldKey: f.fieldKey,
    fieldName: f.fieldName,
    fieldType: f.fieldType,
    placeholder: f.placeholder || '',
    required: !!f.required,
    optionsJson: f.optionsJson || '',
    sortOrder: f.sortOrder ?? idx + 1
  }))
  ElMessage.success(`已基于 ACTIVE ${activeSchema.value.schemaVersion} 复制为草稿`)
}

function nextVersion(prev: string) {
  const match = prev?.match(/v(\d+)(?:\.(\d+))?/)
  if (!match) return prev + '+1'
  const major = Number(match[1])
  const minor = match[2] != null ? Number(match[2]) : 0
  return `v${major}.${minor + 1}`
}

async function loadSchemas() {
  loading.value = true
  try {
    schemas.value = await fetchFieldSchemas(toolId)
  } finally {
    loading.value = false
  }
}

function validateDraft() {
  if (!draft.schemaVersion.trim()) {
    ElMessage.warning('请输入版本号')
    return false
  }
  if (draft.fields.length === 0) {
    ElMessage.warning('至少配置 1 个字段')
    return false
  }
  for (const [idx, f] of draft.fields.entries()) {
    if (!f.fieldKey || !f.fieldName) {
      ElMessage.warning(`第 ${idx + 1} 行缺 fieldKey 或 fieldName`)
      return false
    }
    if (f.fieldType === 'select' && f.optionsJson) {
      try {
        const parsed = JSON.parse(f.optionsJson)
        if (!Array.isArray(parsed)) {
          ElMessage.warning(`第 ${idx + 1} 行 optionsJson 需要是数组`)
          return false
        }
      } catch {
        ElMessage.warning(`第 ${idx + 1} 行 optionsJson 不是合法的 JSON`)
        return false
      }
    }
  }
  return true
}

async function submit() {
  if (!validateDraft()) return
  submitting.value = true
  try {
    const created = await createFieldSchema(toolId, {
      schemaVersion: draft.schemaVersion.trim(),
      fields: draft.fields.map((f) => ({
        ...f,
        fieldKey: f.fieldKey.trim(),
        fieldName: f.fieldName.trim(),
        placeholder: f.placeholder?.trim() || '',
        optionsJson: f.optionsJson?.trim() || ''
      }))
    })
    ElMessage.success(`字段 Schema ${created.schemaVersion} 已保存为 DRAFT`)
    await loadSchemas()
  } finally {
    submitting.value = false
  }
}

async function publish(schema: FieldSchema) {
  await ElMessageBox.confirm(
    `将版本 ${schema.schemaVersion} 设为 ACTIVE，原 ACTIVE 自动 INACTIVE。`,
    '发布字段 Schema',
    { type: 'warning' }
  )
  await publishFieldSchema(schema.id)
  ElMessage.success(`字段 Schema ${schema.schemaVersion} 已发布`)
  loadSchemas()
}

function statusType(status: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

onMounted(loadSchemas)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">字段配置</h1>
        <p class="page-subtitle">配置工具的用户输入字段：仅支持 text / textarea / select / number；发布前最少 1 个字段。</p>
      </div>
      <el-button :icon="ArrowLeft" @click="router.push('/tools')">返回工具列表</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="15">
        <el-card class="page-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>新建 / 编辑 DRAFT Schema</span>
              <el-button size="small" @click="importFromActive">基于 ACTIVE 复制</el-button>
            </div>
          </template>

          <el-form label-width="110px">
            <el-form-item label="版本号" required>
              <el-input v-model="draft.schemaVersion" placeholder="如 v1.1" style="width: 200px" />
              <span class="muted tip">同一工具下版本号需唯一。</span>
            </el-form-item>
          </el-form>

          <el-table :data="draft.fields" border>
            <el-table-column label="fieldKey" min-width="140">
              <template #default="{ row }"><el-input v-model="row.fieldKey" placeholder="productName" /></template>
            </el-table-column>
            <el-table-column label="fieldName" min-width="140">
              <template #default="{ row }"><el-input v-model="row.fieldName" placeholder="产品名称" /></template>
            </el-table-column>
            <el-table-column label="fieldType" width="140">
              <template #default="{ row }">
                <el-select v-model="row.fieldType">
                  <el-option label="text" value="text" />
                  <el-option label="textarea" value="textarea" />
                  <el-option label="select" value="select" />
                  <el-option label="number" value="number" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="必填" width="80">
              <template #default="{ row }"><el-switch v-model="row.required" /></template>
            </el-table-column>
            <el-table-column label="placeholder" min-width="160">
              <template #default="{ row }"><el-input v-model="row.placeholder" placeholder="可选提示" /></template>
            </el-table-column>
            <el-table-column label="optionsJson" min-width="200">
              <template #default="{ row }">
                <el-input
                  v-model="row.optionsJson"
                  :disabled="row.fieldType !== 'select'"
                  placeholder='[{"label":"种草","value":"种草"}]'
                />
              </template>
            </el-table-column>
            <el-table-column label="排序" width="90">
              <template #default="{ row }"><el-input-number v-model="row.sortOrder" :min="0" size="small" /></template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button type="danger" link @click="removeField($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="actions">
            <el-button :icon="Plus" @click="addField">添加字段</el-button>
            <el-button type="primary" :loading="submitting" @click="submit">保存为 DRAFT</el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :span="9">
        <el-card v-loading="loading" class="page-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>历史 Schema</span>
              <el-button :icon="Refresh" size="small" @click="loadSchemas">刷新</el-button>
            </div>
          </template>
          <el-empty v-if="schemas.length === 0" description="暂无字段 Schema" />
          <el-collapse v-else>
            <el-collapse-item v-for="schema in schemas" :key="schema.id" :name="schema.id">
              <template #title>
                <div class="schema-row">
                  <strong>{{ schema.schemaVersion }}</strong>
                  <el-tag :type="statusType(schema.status)" size="small">{{ schema.status }}</el-tag>
                </div>
              </template>
              <el-descriptions :column="1" size="small">
                <el-descriptions-item label="字段数">{{ schema.fields.length }}</el-descriptions-item>
                <el-descriptions-item label="创建于">{{ schema.createdAt || '-' }}</el-descriptions-item>
                <el-descriptions-item label="更新于">{{ schema.updatedAt || '-' }}</el-descriptions-item>
              </el-descriptions>
              <el-table :data="schema.fields" size="small" style="margin-top: 8px">
                <el-table-column prop="fieldKey" label="key" />
                <el-table-column prop="fieldName" label="名称" />
                <el-table-column prop="fieldType" label="类型" width="80" />
                <el-table-column prop="required" label="必填" width="60">
                  <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
                </el-table-column>
              </el-table>
              <div class="schema-foot">
                <el-button
                  v-if="schema.status !== 'ACTIVE'"
                  size="small"
                  type="success"
                  @click="publish(schema)"
                >
                  发布该版本
                </el-button>
              </div>
            </el-collapse-item>
          </el-collapse>
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>

<style scoped>
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.schema-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.schema-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.tip {
  margin-left: 8px;
  font-size: 12px;
}
</style>
