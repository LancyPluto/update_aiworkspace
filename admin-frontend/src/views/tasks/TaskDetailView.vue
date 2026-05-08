<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchTaskDetail } from '@/api/tasks'
import type { AdminTaskDetail } from '@/types'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref<AdminTaskDetail | null>(null)
const paramsText = computed(() => JSON.stringify(detail.value?.params || {}, null, 2))
const resultText = computed(() => {
  if (!detail.value?.result) return '-'
  return detail.value.result.contentText || JSON.stringify(detail.value.result, null, 2)
})

async function loadDetail() {
  loading.value = true
  try {
    detail.value = await fetchTaskDetail(Number(route.params.taskId))
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
}

onMounted(loadDetail)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">任务详情</h1>
        <p class="page-subtitle">展示当前后端返回的任务基础信息、输入参数和生成结果。</p>
      </div>
      <el-button :icon="ArrowLeft" @click="router.push('/tasks')">返回列表</el-button>
    </div>

    <el-card v-loading="loading" class="page-card" shadow="never">
      <el-empty v-if="!detail" description="暂无任务详情或接口未实现" />
      <template v-else>
        <el-descriptions :column="3" border title="基础信息">
          <el-descriptions-item label="任务号">{{ detail.taskNo }}</el-descriptions-item>
          <el-descriptions-item label="工具编码">{{ detail.toolCode }}</el-descriptions-item>
          <el-descriptions-item label="工具">{{ detail.toolName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
          <el-descriptions-item label="进度">{{ detail.progress }}%</el-descriptions-item>
          <el-descriptions-item label="进度说明">{{ detail.progressMessage || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ detail.finishedAt || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider />
        <el-row :gutter="16">
          <el-col :span="12">
            <h3>用户输入参数</h3>
            <el-input :model-value="paramsText" type="textarea" :rows="10" readonly />
          </el-col>
          <el-col :span="12">
            <h3>生成结果</h3>
            <el-input :model-value="resultText" type="textarea" :rows="10" readonly />
          </el-col>
        </el-row>

        <el-divider />
        <el-alert
          title="任务日志、失败原因和算力流水字段在成员4文档中有要求，但当前后端 TaskDetailResponse 尚未返回这些字段。"
          type="info"
          show-icon
          :closable="false"
        />
      </template>
    </el-card>
  </section>
</template>
