<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchTaskDetail } from '@/api/tasks'
import type { AdminTaskDetail } from '@/types'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref<AdminTaskDetail | null>(null)

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
        <p class="page-subtitle">展示任务基础信息、输入参数、生成结果、失败原因、日志和算力流水。</p>
      </div>
      <el-button :icon="ArrowLeft" @click="router.push('/tasks')">返回列表</el-button>
    </div>

    <el-card v-loading="loading" class="page-card" shadow="never">
      <el-empty v-if="!detail" description="暂无任务详情或接口未实现" />
      <template v-else>
        <el-descriptions :column="3" border title="基础信息">
          <el-descriptions-item label="任务号">{{ detail.taskNo }}</el-descriptions-item>
          <el-descriptions-item label="用户">{{ detail.userNickname }}</el-descriptions-item>
          <el-descriptions-item label="工具">{{ detail.toolName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
          <el-descriptions-item label="消耗算力">{{ detail.creditCost }}</el-descriptions-item>
          <el-descriptions-item label="错误码">{{ detail.errorCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ detail.completedAt || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider />
        <el-row :gutter="16">
          <el-col :span="12">
            <h3>用户输入参数</h3>
            <el-input :model-value="JSON.stringify(detail.inputParams || {}, null, 2)" type="textarea" :rows="10" readonly />
          </el-col>
          <el-col :span="12">
            <h3>生成结果 / 失败原因</h3>
            <el-input :model-value="detail.result || detail.failReason || '-'" type="textarea" :rows="10" readonly />
          </el-col>
        </el-row>

        <el-divider />
        <el-row :gutter="16">
          <el-col :span="12">
            <h3>任务日志</h3>
            <el-timeline>
              <el-timeline-item v-for="(log, index) in detail.logs || []" :key="index">{{ log }}</el-timeline-item>
            </el-timeline>
          </el-col>
          <el-col :span="12">
            <h3>算力流水</h3>
            <el-table :data="detail.creditLogs || []">
              <el-table-column prop="type" label="类型" />
              <el-table-column prop="amount" label="数量" />
              <el-table-column prop="createdAt" label="时间" />
            </el-table>
          </el-col>
        </el-row>
      </template>
    </el-card>
  </section>
</template>
