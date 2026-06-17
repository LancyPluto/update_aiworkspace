import { apiRequest } from "./client"
import type { PageResult } from "./types"

export type SubjectSyncStatus = "DRAFT" | "PENDING" | "READY" | "FAILED"

export type GenerationSubject = {
  subjectCode: string
  displayName: string
  description?: string | null
  providerCode: string
  vendorAccountRef: string
  referenceType: "image_refer" | "video_refer"
  previewUrl?: string | null
  referenceJson?: Record<string, unknown>
  upstreamElementId?: string | null
  syncTaskId?: string | null
  syncStatus: SubjectSyncStatus
  syncError?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export type CreateSubjectPayload = {
  displayName: string
  description?: string
  referenceType: "image_refer" | "video_refer"
  referenceJson: Record<string, unknown>
  vendorAccountRef?: string
}

export async function fetchSubjects(params: {
  token: string
  provider?: string
  status?: SubjectSyncStatus
  pageNo?: number
  pageSize?: number
}): Promise<PageResult<GenerationSubject>> {
  return apiRequest<PageResult<GenerationSubject>>("GET", "/api/v1/subjects", {
    token: params.token,
    query: {
      provider: params.provider,
      status: params.status,
      pageNo: params.pageNo,
      pageSize: params.pageSize,
    },
  })
}

export async function fetchSubjectDetail(params: {
  token: string
  subjectCode: string
}): Promise<GenerationSubject> {
  return apiRequest<GenerationSubject>("GET", `/api/v1/subjects/${encodeURIComponent(params.subjectCode)}`, {
    token: params.token,
  })
}

export async function createSubject(params: {
  token: string
  payload: CreateSubjectPayload
}): Promise<GenerationSubject> {
  return apiRequest<GenerationSubject>("POST", "/api/v1/subjects", {
    token: params.token,
    body: params.payload,
  })
}

export async function retrySubjectSync(params: {
  token: string
  subjectCode: string
}): Promise<GenerationSubject> {
  return apiRequest<GenerationSubject>("POST", `/api/v1/subjects/${encodeURIComponent(params.subjectCode)}/sync`, {
    token: params.token,
  })
}

export async function deleteSubject(params: {
  token: string
  subjectCode: string
}): Promise<void> {
  await apiRequest<null>("DELETE", `/api/v1/subjects/${encodeURIComponent(params.subjectCode)}`, {
    token: params.token,
  })
}
