import { defineStore } from "pinia"
import { computed, ref } from "vue"
import { pptApi, type PptJob, type PptProject } from "@/api/pptApi"

export const usePptWorkspaceStore = defineStore("ppt-workspace", () => {
  const projects = ref<PptProject[]>([])
  const currentProject = ref<PptProject | null>(null)
  const loading = ref(false)
  const error = ref("")

  const activeJob = computed<PptJob | null>(() =>
    currentProject.value?.recentJobs?.find((job) =>
      ["CREATED", "CREDIT_RESERVED", "SUBMITTED", "QUEUED", "RUNNING", "RECONCILING"].includes(job.status),
    ) || null,
  )

  async function loadProjects() {
    loading.value = true
    error.value = ""
    try {
      const page = await pptApi.listProjects()
      projects.value = page.list
      return page
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "项目加载失败"
      throw cause
    } finally {
      loading.value = false
    }
  }

  async function loadProject(projectId: number | string, quiet = false) {
    if (!quiet) loading.value = true
    error.value = ""
    try {
      currentProject.value = await pptApi.project(projectId)
      return currentProject.value
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "项目加载失败"
      throw cause
    } finally {
      if (!quiet) loading.value = false
    }
  }

  function patchJob(job: PptJob) {
    if (!currentProject.value || currentProject.value.projectId !== job.projectId) return
    const jobs = currentProject.value.recentJobs || []
    const index = jobs.findIndex((item) => item.jobId === job.jobId)
    if (index >= 0) jobs.splice(index, 1, job)
    else jobs.unshift(job)
  }

  function clearCurrentProject() {
    currentProject.value = null
  }

  return {
    projects,
    currentProject,
    activeJob,
    loading,
    error,
    loadProjects,
    loadProject,
    patchJob,
    clearCurrentProject,
  }
})
