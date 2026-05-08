<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import {
  createPrompt,
  createPromptVersion,
  fetchPrompts,
  publishPromptVersion,
  testGenerate,
  type PromptRecord
} from '@/api/prompts'

const route = useRoute()
const toolId = Number(route.params.toolId)
const loading = ref(false)
const prompts = ref<PromptRecord[]>([])
const testOutput = ref('')
const form = reactive({
  promptCode: 'default',
  promptName: '默认 Prompt',
  content: '你是一个专业 AI 工具。请根据用户输入：{{prompt}}，输出高质量结果。',
  versionNo: 'v1'
})

async function loadPrompts() {
  loading.value = true
  try {
    prompts.value = await fetchPrompts(toolId)
  } catch {
    prompts.value = []
  } finally {
    loading.value = false
  }
}

async function submitDraft() {
  const prompt = await createPrompt(toolId, {
    promptCode: form.promptCode,
    promptName: form.promptName,
    content: form.content
  })
  const version = await createPromptVersion(prompt.id, {
    versionNo: form.versionNo,
    content: form.content
  })
  ElMessage.success(`Prompt 草稿已保存，版本 ${version.versionNo}`)
  loadPrompts()
}

async function runTest(versionId?: number) {
  if (!versionId) {
    ElMessage.info('请先创建版本后再测试生成')
    return
  }
  const response = await testGenerate(versionId, { prompt: '测试输入' })
  testOutput.value = response.output
}

async function publish(versionId?: number) {
  if (!versionId) {
    ElMessage.info('暂无可发布版本')
    return
  }
  await publishPromptVersion(versionId)
  ElMessage.success('Prompt 版本已发布')
  loadPrompts()
}

onMounted(loadPrompts)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">Prompt 配置</h1>
        <p class="page-subtitle">创建 Prompt 草稿、测试生成并发布 ACTIVE Prompt。</p>
      </div>
    </div>

    <el-alert
      title="如果当前提示 404，表示后端 Prompt 接口尚未实现；页面已按成员4文档接口路径完成。"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card class="page-card" shadow="never">
          <template #header>创建 Prompt 草稿</template>
          <el-form label-width="110px">
            <el-form-item label="Prompt 编码"><el-input v-model="form.promptCode" /></el-form-item>
            <el-form-item label="Prompt 名称"><el-input v-model="form.promptName" /></el-form-item>
            <el-form-item label="版本号"><el-input v-model="form.versionNo" /></el-form-item>
            <el-form-item label="Prompt 内容">
              <el-input v-model="form.content" type="textarea" :rows="12" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="submitDraft">保存草稿并创建版本</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card v-loading="loading" class="page-card" shadow="never">
          <template #header>当前 Prompt</template>
          <el-empty v-if="prompts.length === 0" description="暂无 Prompt 或接口未实现" />
          <el-space v-else direction="vertical" fill style="width: 100%">
            <el-card v-for="item in prompts" :key="item.id" shadow="never">
              <div class="prompt-item">
                <div>
                  <strong>{{ item.promptName }}</strong>
                  <p class="muted">{{ item.promptCode }} / {{ item.status }}</p>
                </div>
                <div>
                  <el-button size="small" @click="runTest(item.activeVersionId)">测试生成</el-button>
                  <el-button size="small" type="success" @click="publish(item.activeVersionId)">发布</el-button>
                </div>
              </div>
            </el-card>
          </el-space>
          <el-divider />
          <h4>测试输出</h4>
          <el-input v-model="testOutput" type="textarea" :rows="7" placeholder="测试生成结果会显示在这里" />
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>

<style scoped>
.prompt-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}
</style>
