<script setup lang="ts">
import { ArrowLeft, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchFieldSchemas } from '@/api/fieldSchemas'
import {
  createPrompt,
  createPromptVersion,
  fetchPromptVersions,
  fetchPrompts,
  publishPromptVersion,
  testGenerate
} from '@/api/prompts'
import type { FieldSchema, PromptRecord, PromptVersionRecord } from '@/types'

const route = useRoute()
const router = useRouter()
const toolId = Number(route.params.toolId)
const promptsLoading = ref(false)
const versionsLoading = ref(false)
const submitting = ref(false)
const testing = ref(false)

const prompts = ref<PromptRecord[]>([])
const selectedPromptId = ref<number | null>(null)
const versions = ref<PromptVersionRecord[]>([])
const activeFields = ref<FieldSchema | null>(null)
const testParams = reactive<Record<string, string>>({})
const testOutput = ref('')

const newPromptForm = reactive({
  promptCode: '',
  promptName: ''
})

const draft = reactive({
  versionNo: 'v1',
  systemPrompt: '你是一个专业的 AI 工具助手。',
  userPromptTemplate: '请根据下列输入生成结果：\n{{productName}}\n{{targetCustomer}}',
  outputFormat: 'MARKDOWN'
})

const selectedPrompt = computed(() =>
  prompts.value.find((p) => p.id === selectedPromptId.value) || null
)

async function loadPrompts() {
  promptsLoading.value = true
  try {
    prompts.value = await fetchPrompts(toolId)
    if (prompts.value.length > 0 && !selectedPromptId.value) {
      selectedPromptId.value = prompts.value[0].id
    }
  } finally {
    promptsLoading.value = false
  }
}

async function loadVersions(promptId: number | null) {
  if (!promptId) {
    versions.value = []
    return
  }
  versionsLoading.value = true
  try {
    versions.value = await fetchPromptVersions(promptId)
  } finally {
    versionsLoading.value = false
  }
}

async function loadActiveFields() {
  try {
    const list = await fetchFieldSchemas(toolId)
    activeFields.value = list.find((s) => s.status === 'ACTIVE') || null
    if (activeFields.value) {
      for (const field of activeFields.value.fields) {
        if (testParams[field.fieldKey] == null) {
          testParams[field.fieldKey] = ''
        }
      }
    }
  } catch {
    activeFields.value = null
  }
}

async function submitNewPrompt() {
  if (!newPromptForm.promptCode || !newPromptForm.promptName) {
    ElMessage.warning('请填写 Prompt 编码与名称')
    return
  }
  const created = await createPrompt(toolId, {
    promptCode: newPromptForm.promptCode.trim(),
    promptName: newPromptForm.promptName.trim()
  })
  ElMessage.success(`Prompt ${created.promptName} 已创建`)
  newPromptForm.promptCode = ''
  newPromptForm.promptName = ''
  await loadPrompts()
  selectedPromptId.value = created.id
}

async function submitVersion() {
  if (!selectedPromptId.value) {
    ElMessage.warning('请先选择或创建 Prompt')
    return
  }
  if (!draft.versionNo.trim() || !draft.userPromptTemplate.trim()) {
    ElMessage.warning('版本号和 userPromptTemplate 必填')
    return
  }
  submitting.value = true
  try {
    const version = await createPromptVersion(selectedPromptId.value, {
      versionNo: draft.versionNo.trim(),
      systemPrompt: draft.systemPrompt,
      userPromptTemplate: draft.userPromptTemplate,
      outputFormat: draft.outputFormat || 'MARKDOWN'
    })
    ElMessage.success(`Prompt 版本 ${version.versionNo} 已保存为 DRAFT`)
    await loadVersions(selectedPromptId.value)
  } finally {
    submitting.value = false
  }
}

async function runTest(version: PromptVersionRecord) {
  testing.value = true
  try {
    const params: Record<string, unknown> = {}
    for (const key of Object.keys(testParams)) {
      params[key] = testParams[key]
    }
    const response = await testGenerate(version.id, params)
    testOutput.value = response.output
    ElMessage.success(`已用 ${version.versionNo} 完成模板渲染（仅做占位替换，不会调模型）`)
  } finally {
    testing.value = false
  }
}

async function publishVersion(version: PromptVersionRecord) {
  await ElMessageBox.confirm(
    `将 ${version.versionNo} 发布为 ACTIVE，原 ACTIVE 自动 INACTIVE，工具发布需要至少 1 条 ACTIVE 版本。`,
    '发布 Prompt 版本',
    { type: 'warning' }
  )
  await publishPromptVersion(version.id)
  ElMessage.success('Prompt 版本已发布')
  await loadPrompts()
  await loadVersions(selectedPromptId.value)
}

function statusType(status: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

watch(selectedPromptId, async (id) => {
  await loadVersions(id)
})

onMounted(async () => {
  await Promise.all([loadPrompts(), loadActiveFields()])
  await loadVersions(selectedPromptId.value)
})
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">Prompt 配置</h1>
        <p class="page-subtitle">维护工具 Prompt 与版本，发布前需有至少 1 条 ACTIVE 版本。</p>
      </div>
      <el-button :icon="ArrowLeft" @click="router.push('/tools')">返回工具列表</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="9">
        <el-card v-loading="promptsLoading" class="page-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>Prompt 列表</span>
              <el-button :icon="Refresh" size="small" @click="loadPrompts">刷新</el-button>
            </div>
          </template>

          <el-empty v-if="prompts.length === 0" description="暂无 Prompt，请先创建 Prompt 主档" />
          <el-radio-group v-else v-model="selectedPromptId" class="prompt-list">
            <el-radio
              v-for="item in prompts"
              :key="item.id"
              :value="item.id"
              :label="item.id"
              border
              size="large"
              class="prompt-card"
            >
              <div class="prompt-row">
                <div>
                  <strong>{{ item.promptName }}</strong>
                  <p class="muted">{{ item.promptCode }}</p>
                </div>
                <el-tag :type="statusType(item.status)" size="small">{{ item.status }}</el-tag>
              </div>
            </el-radio>
          </el-radio-group>

          <el-divider content-position="left">新建 Prompt</el-divider>
          <el-form :model="newPromptForm" label-width="100px" size="small">
            <el-form-item label="编码">
              <el-input v-model="newPromptForm.promptCode" placeholder="如 main / variant_A" />
            </el-form-item>
            <el-form-item label="名称">
              <el-input v-model="newPromptForm.promptName" placeholder="如 主版 Prompt" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :icon="Plus" size="small" @click="submitNewPrompt">创建</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card class="page-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>新建 Prompt 版本{{ selectedPrompt ? `（${selectedPrompt.promptName}）` : '' }}</span>
            </div>
          </template>
          <el-form label-width="120px" :disabled="!selectedPromptId">
            <el-form-item label="版本号">
              <el-input v-model="draft.versionNo" placeholder="v1.1" style="max-width: 240px" />
            </el-form-item>
            <el-form-item label="systemPrompt">
              <el-input v-model="draft.systemPrompt" type="textarea" :rows="3" placeholder="可选，系统设定" />
            </el-form-item>
            <el-form-item label="userPromptTemplate">
              <el-input
                v-model="draft.userPromptTemplate"
                type="textarea"
                :rows="8"
                placeholder="使用 {{fieldKey}} 引用字段，如 {{productName}}"
              />
            </el-form-item>
            <el-form-item label="outputFormat">
              <el-select v-model="draft.outputFormat" style="max-width: 240px">
                <el-option label="MARKDOWN" value="MARKDOWN" />
                <el-option label="JSON" value="JSON" />
                <el-option label="TEXT" value="TEXT" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="submitVersion">保存为 DRAFT</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-loading="versionsLoading" class="page-card version-card" shadow="never">
          <template #header>历史版本</template>
          <el-empty v-if="versions.length === 0" description="选择一个 Prompt 后展示版本" />
          <el-table v-else :data="versions" size="small">
            <el-table-column prop="versionNo" label="版本" width="100" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="outputFormat" label="格式" width="120" />
            <el-table-column prop="createdAt" label="创建时间" width="180" />
            <el-table-column prop="publishedAt" label="发布时间" width="180" />
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button size="small" :loading="testing" @click="runTest(row)">试运行</el-button>
                <el-button
                  v-if="row.status !== 'ACTIVE'"
                  size="small"
                  type="success"
                  @click="publishVersion(row)"
                >
                  发布
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card class="page-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>试运行参数（基于 ACTIVE 字段 Schema）</span>
              <el-button :icon="Refresh" size="small" @click="loadActiveFields">同步字段</el-button>
            </div>
          </template>
          <el-empty
            v-if="!activeFields || activeFields.fields.length === 0"
            description="未找到 ACTIVE 字段 Schema，请先在「字段配置」页发布字段。"
          />
          <el-form v-else label-width="140px">
            <el-form-item v-for="field in activeFields.fields" :key="field.fieldKey" :label="field.fieldKey">
              <el-input v-model="testParams[field.fieldKey]" :placeholder="field.placeholder || field.fieldName" />
            </el-form-item>
          </el-form>
          <el-divider />
          <h4 style="margin: 0 0 8px">渲染结果</h4>
          <el-input v-model="testOutput" type="textarea" :rows="8" placeholder="点击版本「试运行」后展示" readonly />
          <p class="muted" style="margin-top: 8px">
            注：当前 test-generate 仅做 <code>&#123;&#123;key&#125;&#125;</code> 占位替换，不会真实调用模型。
          </p>
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.prompt-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.prompt-list :deep(.el-radio) {
  width: 100%;
  height: auto;
  padding: 12px 14px;
  margin: 0;
}

.prompt-list :deep(.el-radio__label) {
  width: 100%;
  padding-left: 8px;
}

.prompt-card .prompt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.muted {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
}

.version-card {
  margin-top: 16px;
}
</style>
