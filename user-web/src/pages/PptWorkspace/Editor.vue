<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { ArrowLeft, Loader2 } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { ApiBusinessError } from "@/api/client"
import {
  PPT_TOOL_CODE,
  fetchPptProject,
  type PptProjectDetail,
  type PptWorkflowStep,
} from "@/api/pptApi"
import { fetchToolByCode } from "@/api/toolApi"
import type { PptWorkflow } from "@/api/pptApi"
import OutlineStep from "@/pages/PptWorkspace/steps/OutlineStep.vue"
import DescriptionsStep from "@/pages/PptWorkspace/steps/DescriptionsStep.vue"
import PreviewStep from "@/pages/PptWorkspace/steps/PreviewStep.vue"
import ExportStep from "@/pages/PptWorkspace/steps/ExportStep.vue"
import { normalizeProjectDetail } from "@/utils/pptProjectUtils"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  bindingId: string
}>()

type EditorStep = "outline" | "descriptions" | "preview" | "export"

const STEPS: { id: EditorStep; label: string }[] = [
  { id: "outline", label: "大纲" },
  { id: "descriptions", label: "描述" },
  { id: "preview", label: "预览" },
  { id: "export", label: "导出" },
]

const auth = useAuthStore()

const loading = ref(true)
const error = ref<string | null>(null)
const project = ref<PptProjectDetail | null>(null)
const activeStep = ref<EditorStep>("outline")
const workflowSteps = ref<PptWorkflowStep[]>([])

const pageCount = computed(() => project.value?.pages?.length ?? 0)
const projectTitle = computed(
  () => (project.value?.title as string | undefined) || `项目 #${props.bindingId}`,
)

function inferStepFromStatus(status?: string): EditorStep {
  if (!status) return "outline"
  const s = status.toUpperCase()
  if (s.includes("IMAGE") || s === "IMAGES_READY" || s === "COMPLETED") return "preview"
  if (s.includes("DESCRIPTION")) return "descriptions"
  return "outline"
}

function onProjectRefreshed(detail: PptProjectDetail) {
  project.value = detail
}

async function loadMeta() {
  loading.value = true
  error.value = null
  try {
    const [detail, toolRes] = await Promise.all([
      fetchPptProject(props.bindingId, { token: auth.token }),
      fetchToolByCode(PPT_TOOL_CODE, { token: auth.token }).catch(() => null),
    ])
    project.value = normalizeProjectDetail(detail)
    activeStep.value = inferStepFromStatus(project.value.status)
    const wf = toolRes?.integration?.extension as PptWorkflow | undefined
    workflowSteps.value = wf?.steps?.filter((s) => s.enabled) ?? []
  } catch (e) {
    error.value = e instanceof ApiBusinessError ? e.message : (e as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(loadMeta)
</script>

<template>
  <AppShell :title="projectTitle" description="PPT 项目编辑">
    <div class="mx-auto max-w-6xl space-y-6 px-6 py-6">
      <nav class="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <RouterLink
          :to="userRoutes.pptWorkspace()"
          class="inline-flex items-center gap-1 hover:text-foreground"
        >
          <ArrowLeft class="h-3 w-3" />
          工作台
        </RouterLink>
        <span>/</span>
        <span class="text-foreground">编辑 #{{ bindingId }}</span>
      </nav>

      <div v-if="loading && !project" class="flex justify-center py-12">
        <Loader2 class="h-8 w-8 animate-spin text-muted-foreground" />
      </div>

      <template v-else>
        <div
          v-if="error"
          class="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive"
        >
          {{ error }}
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <button
            v-for="step in STEPS"
            :key="step.id"
            type="button"
            class="rounded-lg px-4 py-2 text-sm font-medium transition-colors"
            :class="
              activeStep === step.id
                ? 'bg-primary text-primary-foreground'
                : 'bg-secondary text-muted-foreground hover:text-foreground'
            "
            @click="activeStep = step.id"
          >
            {{ step.label }}
          </button>
          <span class="ml-auto text-xs text-muted-foreground">
            状态 {{ project?.status || "—" }} · {{ pageCount }} 页
          </span>
        </div>

        <OutlineStep
          v-if="activeStep === 'outline'"
          :binding-id="bindingId"
          @refreshed="onProjectRefreshed"
          @go-descriptions="activeStep = 'descriptions'"
        />

        <DescriptionsStep
          v-else-if="activeStep === 'descriptions'"
          :binding-id="bindingId"
          @refreshed="onProjectRefreshed"
          @go-outline="activeStep = 'outline'"
          @go-preview="activeStep = 'preview'"
        />

        <PreviewStep
          v-else-if="activeStep === 'preview'"
          :binding-id="bindingId"
          @refreshed="onProjectRefreshed"
          @go-descriptions="activeStep = 'descriptions'"
          @go-export="activeStep = 'export'"
        />

        <ExportStep
          v-else-if="activeStep === 'export'"
          :binding-id="bindingId"
          :workflow-steps="workflowSteps"
          @refreshed="onProjectRefreshed"
          @go-preview="activeStep = 'preview'"
        />
      </template>
    </div>
  </AppShell>
</template>
