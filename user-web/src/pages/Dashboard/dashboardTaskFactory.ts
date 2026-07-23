import type { TaskDetail, TaskStatus, ToolSummary } from "@/api/types"

export function buildDashboardTaskParams(options: {
  prompt: string
  params: Record<string, unknown>
  coreFieldKey?: string | null
  includePromptAliases?: boolean
  attachments?: Array<string | number>
}) {
  const content = options.prompt.trim()
  const taskParams: Record<string, unknown> = {
    ...options.params,
    attachments: options.attachments || [],
  }
  if (options.includePromptAliases !== false) {
    taskParams.prompt = content
    taskParams.text = content
  }
  if (options.coreFieldKey && content) taskParams[options.coreFieldKey] = content
  return taskParams
}

export function buildOptimisticDashboardTask(options: {
  taskId: number
  taskNo: string
  status: TaskStatus
  tool: ToolSummary
  params: Record<string, unknown>
  selectedModality: string
  userId: number
  modelConfigId?: number | null
  modelConfigName?: string | null
}): TaskDetail {
  return {
    taskId: options.taskId,
    taskNo: options.taskNo,
    status: options.status,
    progress: options.status === "CREATED" || options.status === "QUEUED" ? 5 : 0,
    progressMessage: "任务已创建，正在排队生成",
    userId: options.userId,
    toolCode: options.tool.toolCode,
    toolName: options.tool.toolName,
    modelConfigId: options.modelConfigId,
    modelConfigName: options.modelConfigName,
    toolType: options.tool.toolType || undefined,
    inputModality: options.tool.inputModality || undefined,
    outputModality: options.tool.outputModality || options.selectedModality,
    params: options.params,
    result: null,
    createdAt: new Date().toISOString(),
    finishedAt: null,
  }
}
