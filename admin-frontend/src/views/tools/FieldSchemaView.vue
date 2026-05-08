<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import { createFieldSchema, fetchFieldSchemas, publishFieldSchema } from '@/api/fieldSchemas'
import type { FieldItem, FieldSchema } from '@/types'

const route = useRoute()
const toolId = Number(route.params.toolId)
const loading = ref(false)
const schemas = ref<FieldSchema[]>([])
const form = reactive({
  schemaVersion: 'v1',
  items: [
    {
      fieldKey: 'prompt',
      fieldName: '用户需求',
      fieldType: 'textarea',
      placeholder: '请输入需要 AI 处理的内容',
      required: true,
      optionsJson: '',
      sortOrder: 1
    }
  ] as FieldItem[]
})

function addField() {
  form.items.push({
    fieldKey: '',
    fieldName: '',
    fieldType: 'text',
    placeholder: '',
    required: false,
    optionsJson: '',
    sortOrder: form.items.length + 1
  })
}

function removeField(index: number) {
  form.items.splice(index, 1)
}

async function loadSchemas() {
  loading.value = true
  try {
    schemas.value = await fetchFieldSchemas(toolId)
  } catch {
    schemas.value = []
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (form.items.some((item) => !item.fieldKey || !item.fieldName || !item.fieldType)) {
    ElMessage.warning('请补全字段 Key、名称和类型')
    return
  }
  await createFieldSchema(toolId, { schemaVersion: form.schemaVersion, items: form.items })
  ElMessage.success('字段 Schema 已保存')
  loadSchemas()
}

async function publish(schemaId: number) {
  await publishFieldSchema(schemaId)
  ElMessage.success('字段 Schema 已发布')
  loadSchemas()
}

onMounted(loadSchemas)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">字段配置</h1>
        <p class="page-subtitle">配置工具的用户输入字段，本周仅支持 text、textarea、select、number。</p>
      </div>
    </div>

    <el-alert
      title="如果当前提示 404，表示后端字段 Schema 接口尚未实现；页面已按成员4文档路径完成对接。"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <el-row :gutter="16">
      <el-col :span="15">
        <el-card class="page-card" shadow="never">
          <template #header>新建字段 Schema</template>
          <el-form label-width="110px">
            <el-form-item label="版本号">
              <el-input v-model="form.schemaVersion" placeholder="v1" />
            </el-form-item>
          </el-form>

          <el-table :data="form.items" border>
            <el-table-column label="fieldKey" min-width="150">
              <template #default="{ row }"><el-input v-model="row.fieldKey" /></template>
            </el-table-column>
            <el-table-column label="fieldName" min-width="150">
              <template #default="{ row }"><el-input v-model="row.fieldName" /></template>
            </el-table-column>
            <el-table-column label="fieldType" width="150">
              <template #default="{ row }">
                <el-select v-model="row.fieldType">
                  <el-option label="text" value="text" />
                  <el-option label="textarea" value="textarea" />
                  <el-option label="select" value="select" />
                  <el-option label="number" value="number" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="required" width="110">
              <template #default="{ row }"><el-switch v-model="row.required" /></template>
            </el-table-column>
            <el-table-column label="placeholder" min-width="180">
              <template #default="{ row }"><el-input v-model="row.placeholder" /></template>
            </el-table-column>
            <el-table-column label="optionsJson" min-width="180">
              <template #default="{ row }"><el-input v-model="row.optionsJson" placeholder='["A","B"]' /></template>
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
            <el-button type="primary" @click="submit">保存 Schema</el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :span="9">
        <el-card v-loading="loading" class="page-card" shadow="never">
          <template #header>历史 Schema</template>
          <el-empty v-if="schemas.length === 0" description="暂无字段 Schema 或接口未实现" />
          <el-timeline v-else>
            <el-timeline-item v-for="schema in schemas" :key="schema.id" :timestamp="schema.status">
              <div class="schema-row">
                <strong>{{ schema.schemaVersion }}</strong>
                <el-button v-if="schema.status !== 'ACTIVE'" type="success" link @click="publish(schema.id)">发布</el-button>
              </div>
            </el-timeline-item>
          </el-timeline>
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

.schema-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
