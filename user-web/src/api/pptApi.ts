import { apiRequest, type RequestOptions } from "./client"

export type PptJobStatus =
  | "CREATED"
  | "CREDIT_RESERVED"
  | "SUBMITTED"
  | "QUEUED"
  | "RUNNING"
  | "RECONCILING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"

export interface PptSlideView {
  slideId?: string | number
  slideNo?: number
  title?: string
  description?: string
  previewUrl?: string
}

export interface PptDeckView {
  slides?: PptSlideView[]
  [key: string]: unknown
}

export interface PptJob {
  jobId: number
  projectId: number
  jobType: string
  status: PptJobStatus
  progress: number
  progressMessage?: string | null
  engineCode: string
  creditState: string
  reservedCredits: number
  actualCredits: number
  retryable: boolean
  error?: { code: string; message: string } | null
  result?: PptDeckView | null
  createdAt: string
  updatedAt: string
  finishedAt?: string | null
}

export interface PptExport {
  exportId: number
  projectId: number
  exportType: string
  status: string
  fileName?: string | null
  contentType?: string | null
  fileSize?: number | null
  downloadUrl?: string | null
  createdAt: string
}

export interface PptProject {
  projectId: number
  title: string
  topic: string
  creationType: string
  language: string
  aspectRatio: string
  pageCount: number
  status: string
  engineStrategy: string
  latestDeck?: PptDeckView | null
  recentJobs: PptJob[]
  exports: PptExport[]
  createdAt: string
  updatedAt: string
}

export interface PptProjectPage {
  list: PptProject[]
  total: number
  pageNo: number
  pageSize: number
  hasNext: boolean
}

export interface PptEngineCapability {
  engineCode: string
  displayName: string
  available: boolean
  visualGeneration: boolean
  nativeEditablePptx: boolean
  cancellation: boolean
  supportedJobs: string[]
}

export interface PptWorkbenchStatus {
  enabled: boolean
  message: string
}

export interface PptModelBinding {
  platformManaged: boolean
  textModel: {
    modelConfigId: number
    displayName: string
    provider: string
    modelName: string
  }
  imageModel: {
    modelConfigId: number
    displayName: string
    provider: string
    modelName: string
  }
}

export interface PptModelOption {
  modelConfigId: number
  displayName: string
  provider: string
  modelName: string
  recommended: boolean
}

export interface PptModelOptions {
  textModels: PptModelOption[]
  imageModels: PptModelOption[]
}

export interface CreatePptProjectInput {
  title: string
  topic: string
  creationType?: string
  language?: string
  aspectRatio?: string
  pageCount?: number
  toolCode?: string
  textModelConfigId?: number | null
  imageModelConfigId?: number | null
}

export interface SubmitPptJobInput {
  jobType: string
  clientRequestId: string
  payload?: Record<string, unknown>
}

const CJK_PATTERN = /[\u3400-\u9fff\uf900-\ufaff]/g

function cjkCount(value: string): number {
  return value.match(CJK_PATTERN)?.length ?? 0
}

/**
 * Repairs historical PPT text that was decoded as Latin-1 before being saved.
 * The conversion is deliberately conservative: it must be byte-reversible,
 * valid UTF-8, and produce more CJK characters than the source value.
 */
export function repairPptMojibake(value: string): string {
  if (!value || !/[\u0080-\u00ff]/.test(value)) return value
  const bytes = new Uint8Array(value.length)
  for (let index = 0; index < value.length; index += 1) {
    const codePoint = value.charCodeAt(index)
    if (codePoint > 0xff) return value
    bytes[index] = codePoint
  }
  try {
    const repaired = new TextDecoder("utf-8", { fatal: true }).decode(bytes)
    return cjkCount(repaired) > cjkCount(value) ? repaired : value
  } catch {
    return value
  }
}

function normalizeDeck(deck?: PptDeckView | null): PptDeckView | null | undefined {
  if (!deck) return deck
  return {
    ...deck,
    slides: deck.slides?.map((slide) => ({
      ...slide,
      title: slide.title ? repairPptMojibake(slide.title) : slide.title,
      description: slide.description ? repairPptMojibake(slide.description) : slide.description,
    })),
  }
}

function normalizeJob(job: PptJob): PptJob {
  return {
    ...job,
    progressMessage: job.progressMessage ? repairPptMojibake(job.progressMessage) : job.progressMessage,
    result: normalizeDeck(job.result),
  }
}

function normalizeProject(project: PptProject): PptProject {
  return {
    ...project,
    latestDeck: normalizeDeck(project.latestDeck),
    recentJobs: project.recentJobs?.map(normalizeJob) ?? project.recentJobs,
  }
}

export function createPptApi(request: typeof apiRequest = apiRequest) {
  return {
    status: (options?: RequestOptions) =>
      request<PptWorkbenchStatus>("GET", "/api/v2/ppt/status", options),
    capabilities: (options?: RequestOptions) =>
      request<PptEngineCapability[]>("GET", "/api/v2/ppt/capabilities", options),
    modelOptions: (options?: RequestOptions) =>
      request<PptModelOptions>("GET", "/api/v2/ppt/model-options", options),
    listProjects: (pageNo = 1, pageSize = 24, options?: RequestOptions) =>
      request<PptProjectPage>("GET", "/api/v2/ppt/projects", {
        ...options,
        query: { ...options?.query, pageNo, pageSize },
      }).then((page) => ({ ...page, list: page.list?.map(normalizeProject) ?? page.list })),
    createProject: (body: CreatePptProjectInput, options?: RequestOptions) =>
      request<PptProject>("POST", "/api/v2/ppt/projects", { ...options, body }).then(normalizeProject),
    project: (projectId: number | string, options?: RequestOptions) =>
      request<PptProject>("GET", `/api/v2/ppt/projects/${encodeURIComponent(projectId)}`, options).then(normalizeProject),
    modelBinding: (projectId: number | string, options?: RequestOptions) =>
      request<PptModelBinding>(
        "GET",
        `/api/v2/ppt/projects/${encodeURIComponent(projectId)}/model-binding`,
        options,
      ),
    updateModelBinding: (
      projectId: number | string,
      body: { textModelConfigId: number | null; imageModelConfigId: number | null },
      options?: RequestOptions,
    ) =>
      request<PptModelBinding>(
        "PUT",
        `/api/v2/ppt/projects/${encodeURIComponent(projectId)}/model-binding`,
        { ...options, body },
      ),
    deleteProject: (projectId: number | string, options?: RequestOptions) =>
      request<void>("DELETE", `/api/v2/ppt/projects/${encodeURIComponent(projectId)}`, options),
    submitJob: (projectId: number | string, body: SubmitPptJobInput, options?: RequestOptions) =>
      request<PptJob>("POST", `/api/v2/ppt/projects/${encodeURIComponent(projectId)}/jobs`, {
        ...options,
        body,
      }).then(normalizeJob),
    job: (jobId: number | string, options?: RequestOptions) =>
      request<PptJob>("GET", `/api/v2/ppt/jobs/${encodeURIComponent(jobId)}`, options).then(normalizeJob),
    retryJob: (jobId: number | string, clientRequestId: string, options?: RequestOptions) =>
      request<PptJob>("POST", `/api/v2/ppt/jobs/${encodeURIComponent(jobId)}/retry`, {
        ...options,
        body: { clientRequestId },
      }).then(normalizeJob),
    cancelJob: (jobId: number | string, options?: RequestOptions) =>
      request<PptJob>("POST", `/api/v2/ppt/jobs/${encodeURIComponent(jobId)}/cancel`, options).then(normalizeJob),
    exports: (projectId: number | string, options?: RequestOptions) =>
      request<PptExport[]>("GET", `/api/v2/ppt/projects/${encodeURIComponent(projectId)}/exports`, options),
  }
}

export const pptApi = createPptApi()

export function pptClientRequestId(action: string): string {
  const random = typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `ppt-${action}-${random}`
}
