import { apiRequest } from "./client"

export const PPT_TOOL_CODE = "banana_ppt_generator"

export type PptCreationType = "idea" | "outline" | "description" | "ppt_renovation"

export interface PptWorkflowStep {
  code: string
  name: string
  credits: number
  enabled: boolean
}

export interface PptWorkflow {
  integrationMode?: string
  customUiRoute?: string
  creationTypes?: string[]
  steps?: PptWorkflowStep[]
  features?: Record<string, boolean>
}

export interface ToolIntegrationView {
  integrationMode?: string
  pluginId?: string
  customUiRoute?: string
  apiPrefix?: string
  displayName?: string
  extension?: PptWorkflow
}

export interface PptProjectSummary {
  bindingId: number
  title?: string | null
  status: string
  creationType: string
  pageCount?: number | null
  updatedAt?: string
}

export interface PptProjectCreated {
  bindingId: number
  projectId: string
  status: string
}

export interface PptPageOutline {
  title?: string
  points?: string[]
}

export interface PptPage {
  id: string
  orderIndex?: number
  status?: string
  part?: string
  outlineContent?: PptPageOutline
  descriptionContent?: { text?: string }
  generatedImageUrl?: string
}

export interface PptProjectDetail {
  bindingId?: number
  projectId?: string
  status?: string
  creationType?: string
  creation_type?: string
  imageAspectRatio?: string
  ideaPrompt?: string
  idea_prompt?: string
  outlineText?: string
  outline_text?: string
  descriptionText?: string
  description_text?: string
  outlineRequirements?: string
  outline_requirements?: string
  templateStyle?: string
  template_style?: string
  templateImageUrl?: string
  template_image_url?: string
  pages?: PptPage[]
  [key: string]: unknown
}

export interface PptTaskResponse {
  taskId: string
  status?: string
  progress?: Record<string, unknown>
  errorMessage?: string
}

export interface PptExportResponse {
  downloadUrl?: string
  [key: string]: unknown
}

export function isPptWorkspaceTool(toolCode?: string | null, integrationMode?: string | null): boolean {
  if (toolCode === PPT_TOOL_CODE) return true
  return integrationMode === "PPT_WORKSPACE"
}

export function createPptProject(
  body: Record<string, unknown>,
  options?: { token?: string | null },
) {
  return apiRequest<PptProjectCreated>("POST", "/api/v1/ppt/projects", {
    body: { toolCode: PPT_TOOL_CODE, ...body },
    token: options?.token,
  })
}

export function listPptProjects(options?: { token?: string | null }) {
  return apiRequest<{ projects: PptProjectSummary[] }>("GET", "/api/v1/ppt/projects", {
    token: options?.token,
  })
}

export function fetchPptProject(bindingId: string | number, options?: { token?: string | null }) {
  return apiRequest<PptProjectDetail>("GET", `/api/v1/ppt/projects/${bindingId}`, {
    token: options?.token,
  })
}

export function deletePptProject(bindingId: string | number, options?: { token?: string | null }) {
  return apiRequest<void>("DELETE", `/api/v1/ppt/projects/${bindingId}`, { token: options?.token })
}

export function createPptRenovation(
  file: File,
  options?: { token?: string | null; clientRequestId?: string; toolCode?: string },
) {
  const form = new FormData()
  form.append("file", file)
  const query: Record<string, string> = {}
  if (options?.clientRequestId) query.clientRequestId = options.clientRequestId
  if (options?.toolCode) query.toolCode = options.toolCode
  else query.toolCode = PPT_TOOL_CODE
  return apiRequest<PptProjectCreated>("POST", "/api/v1/ppt/projects/renovation", {
    body: form,
    token: options?.token,
    query,
  })
}

export function generateOutline(
  bindingId: string | number,
  body?: Record<string, unknown>,
  options?: { token?: string | null; clientRequestId?: string },
) {
  const query: Record<string, string> = {}
  if (options?.clientRequestId) query.clientRequestId = options.clientRequestId
  return apiRequest<unknown>("POST", `/api/v1/ppt/projects/${bindingId}/generate/outline`, {
    body: body ?? {},
    token: options?.token,
    query,
  })
}

export function generateDescriptions(
  bindingId: string | number,
  options?: { token?: string | null; clientRequestId?: string },
) {
  const query: Record<string, string> = {}
  if (options?.clientRequestId) query.clientRequestId = options.clientRequestId
  return apiRequest<PptTaskResponse>("POST", `/api/v1/ppt/projects/${bindingId}/generate/descriptions`, {
    token: options?.token,
    query,
  })
}

export function generateImages(
  bindingId: string | number,
  body?: Record<string, unknown>,
  options?: { token?: string | null; clientRequestId?: string },
) {
  const query: Record<string, string> = {}
  if (options?.clientRequestId) query.clientRequestId = options.clientRequestId
  return apiRequest<PptTaskResponse>("POST", `/api/v1/ppt/projects/${bindingId}/generate/images`, {
    body: body ?? {},
    token: options?.token,
    query,
  })
}

export function pollPptTask(
  bindingId: string | number,
  taskId: string,
  options?: { token?: string | null },
) {
  return apiRequest<PptTaskResponse>("GET", `/api/v1/ppt/projects/${bindingId}/tasks/${taskId}`, {
    token: options?.token,
  })
}

export interface PptExportFileItem {
  filename: string
  type?: string
  size?: number
  modifiedAt?: string
  modified_at?: string
  downloadUrl?: string
  download_url?: string
}

export function listPptExports(bindingId: string | number, options?: { token?: string | null }) {
  return apiRequest<{ files?: PptExportFileItem[] }>("GET", `/api/v1/ppt/projects/${bindingId}/exports`, {
    token: options?.token,
  })
}

export function exportPptx(
  bindingId: string | number,
  options?: { token?: string | null; filename?: string; clientRequestId?: string },
) {
  const query: Record<string, string> = {}
  if (options?.filename) query.filename = options.filename
  if (options?.clientRequestId) query.clientRequestId = options.clientRequestId
  return apiRequest<PptExportResponse>("GET", `/api/v1/ppt/projects/${bindingId}/export/pptx`, {
    token: options?.token,
    query,
  })
}

export function updatePageOutline(
  bindingId: string | number,
  pageId: string,
  outlineContent: PptPageOutline,
  options?: { token?: string | null },
) {
  return apiRequest<unknown>("PUT", `/api/v1/ppt/projects/${bindingId}/pages/${pageId}/outline`, {
    body: { outline_content: outlineContent },
    token: options?.token,
  })
}

export function refineOutline(
  bindingId: string | number,
  userRequirement: string,
  options?: { token?: string | null; previousRequirements?: string[] },
) {
  return apiRequest<{ pages?: unknown[]; message?: string }>(
    "POST",
    `/api/v1/ppt/projects/${bindingId}/refine/outline`,
    {
      body: {
        user_requirement: userRequirement,
        previous_requirements: options?.previousRequirements ?? [],
        language: "zh",
      },
      token: options?.token,
    },
  )
}

export function addPptPage(
  bindingId: string | number,
  body: Record<string, unknown>,
  options?: { token?: string | null },
) {
  return apiRequest<unknown>("POST", `/api/v1/ppt/projects/${bindingId}/pages`, {
    body,
    token: options?.token,
  })
}

export function deletePptPage(
  bindingId: string | number,
  pageId: string,
  options?: { token?: string | null },
) {
  return apiRequest<void>("DELETE", `/api/v1/ppt/projects/${bindingId}/pages/${pageId}`, {
    token: options?.token,
  })
}

export function updatePptProject(
  bindingId: string | number,
  body: Record<string, unknown>,
  options?: { token?: string | null },
) {
  return apiRequest<PptProjectDetail>("PUT", `/api/v1/ppt/projects/${bindingId}`, {
    body,
    token: options?.token,
  })
}

export function updatePageDescription(
  bindingId: string | number,
  pageId: string,
  text: string,
  options?: { token?: string | null },
) {
  return apiRequest<unknown>("PUT", `/api/v1/ppt/projects/${bindingId}/pages/${pageId}/description`, {
    body: { description_content: { text } },
    token: options?.token,
  })
}

export function refineDescriptions(
  bindingId: string | number,
  userRequirement: string,
  options?: { token?: string | null; previousRequirements?: string[] },
) {
  return apiRequest<{ pages?: unknown[]; message?: string }>(
    "POST",
    `/api/v1/ppt/projects/${bindingId}/refine/descriptions`,
    {
      body: {
        user_requirement: userRequirement,
        previous_requirements: options?.previousRequirements ?? [],
        language: "zh",
      },
      token: options?.token,
    },
  )
}

export function generatePageDescription(
  bindingId: string | number,
  pageId: string,
  options?: { token?: string | null; forceRegenerate?: boolean },
) {
  return apiRequest<unknown>(
    "POST",
    `/api/v1/ppt/projects/${bindingId}/pages/${pageId}/generate/description`,
    {
      body: { force_regenerate: options?.forceRegenerate ?? false, language: "zh" },
      token: options?.token,
    },
  )
}

export function generatePageImage(
  bindingId: string | number,
  pageId: string,
  options?: {
    token?: string | null
    forceRegenerate?: boolean
    useTemplate?: boolean
  },
) {
  return apiRequest<PptTaskResponse>(
    "POST",
    `/api/v1/ppt/projects/${bindingId}/pages/${pageId}/generate/image`,
    {
      body: {
        force_regenerate: options?.forceRegenerate ?? false,
        use_template: options?.useTemplate ?? true,
        language: "zh",
      },
      token: options?.token,
    },
  )
}

export function exportEditablePptx(
  bindingId: string | number,
  body?: Record<string, unknown>,
  options?: { token?: string | null; clientRequestId?: string },
) {
  const query: Record<string, string> = {}
  if (options?.clientRequestId) query.clientRequestId = options.clientRequestId
  return apiRequest<PptTaskResponse>(
    "POST",
    `/api/v1/ppt/projects/${bindingId}/export/editable-pptx`,
    {
      body: body ?? {},
      token: options?.token,
      query,
    },
  )
}

export function exportPdf(
  bindingId: string | number,
  options?: { token?: string | null; filename?: string; clientRequestId?: string },
) {
  const query: Record<string, string> = {}
  if (options?.filename) query.filename = options.filename
  if (options?.clientRequestId) query.clientRequestId = options.clientRequestId
  return apiRequest<PptExportResponse>("GET", `/api/v1/ppt/projects/${bindingId}/export/pdf`, {
    token: options?.token,
    query,
  })
}

export function exportImages(
  bindingId: string | number,
  options?: { token?: string | null; pageIds?: string },
) {
  const query: Record<string, string> = {}
  if (options?.pageIds) query.page_ids = options.pageIds
  return apiRequest<PptExportResponse>("GET", `/api/v1/ppt/projects/${bindingId}/export/images`, {
    token: options?.token,
    query,
  })
}

export function uploadPptTemplate(
  bindingId: string | number,
  file: File,
  options?: { token?: string | null },
) {
  const form = new FormData()
  form.append("template_image", file)
  return apiRequest<unknown>("POST", `/api/v1/ppt/projects/${bindingId}/template`, {
    body: form,
    token: options?.token,
  })
}

export function deletePptTemplate(
  bindingId: string | number,
  options?: { token?: string | null },
) {
  return apiRequest<PptProjectDetail>("DELETE", `/api/v1/ppt/projects/${bindingId}/template`, {
    token: options?.token,
  })
}
