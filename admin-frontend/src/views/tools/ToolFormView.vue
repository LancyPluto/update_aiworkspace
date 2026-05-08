<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { createTool, fetchAdminTools, fetchToolCategories, updateTool } from '@/api/tools'
import type { ToolCategory, UpsertToolPayload } from '@/types'

const route = useRoute()
const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const categories = ref<ToolCategory[]>([])
const toolId = computed(() => Number(route.params.toolId || 0))
const isEdit = computed(() => Boolean(toolId.value))

const form = reactive<UpsertToolPayload>({
  toolCode: '',
  toolName: '',
  categoryId: null,
  description: '',
  coverUrl: '',
  estimatedCreditCost: 0
})

const rules: FormRules = {
  toolName: [{ required: true, message: '请输入工具名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  estimatedCreditCost: [{ required: true, type: 'number', min: 0, message: '算力消耗不能小于 0', trigger: 'change' }]
}

async function loadInitialData() {
  loading.value = true
  try {
    categories.value = await fetchToolCategories()
    if (isEdit.value) {
      const response = await fetchAdminTools()
      const current = response.list.find((item) => item.id === toolId.value)
      if (!current) {
        ElMessage.error('工具不存在')
        router.push('/tools')
        return
      }
      Object.assign(form, {
        toolCode: current.toolCode,
        toolName: current.toolName,
        categoryId: current.categoryId,
        description: current.description || '',
        coverUrl: current.coverUrl || '',
        estimatedCreditCost: current.estimatedCreditCost
      })
    }
  } finally {
    loading.value = false
  }
}

async function submit() {
  await formRef.value?.validate()
  loading.value = true
  try {
    if (isEdit.value) {
      await updateTool(toolId.value, form)
      ElMessage.success('工具已更新')
    } else {
      await createTool(form)
      ElMessage.success('工具已创建')
    }
    router.push('/tools')
  } finally {
    loading.value = false
  }
}

onMounted(loadInitialData)
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ isEdit ? '编辑工具' : '新增工具' }}</h1>
        <p class="page-subtitle">维护工具基础信息，发布前还需完成字段 Schema 与 Prompt 配置。</p>
      </div>
      <el-button :icon="ArrowLeft" @click="router.push('/tools')">返回列表</el-button>
    </div>

    <el-card v-loading="loading" class="page-card form-card" shadow="never">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="工具编码" prop="toolCode">
          <el-input v-model="form.toolCode" placeholder="如 image_text_extract，可留空由后端生成" />
        </el-form-item>
        <el-form-item label="工具名称" prop="toolName">
          <el-input v-model="form.toolName" placeholder="请输入工具名称" />
        </el-form-item>
        <el-form-item label="所属分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="请选择分类" filterable style="width: 100%">
            <el-option v-for="item in categories" :key="item.id" :label="item.categoryName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="封面 URL" prop="coverUrl">
          <el-input v-model="form.coverUrl" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="预估算力" prop="estimatedCreditCost">
          <el-input-number v-model="form.estimatedCreditCost" :min="0" :step="1" />
        </el-form-item>
        <el-form-item label="工具描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="5" placeholder="说明工具用途、适用场景和输入要求" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">{{ isEdit ? '保存修改' : '创建工具' }}</el-button>
          <el-button @click="router.push('/tools')">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </section>
</template>
